package com.lowaltitude.inspection.mall.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.mall.application.MallCartException.Error;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCartMapper;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCartMapper.AddedRow;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCartMapper.CartRow;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCartMapper.OfferRow;
import org.springframework.transaction.annotation.Transactional;

/**
 * 购物车和结算预览（契约 contracts/openapi-mall-cart.yaml）。规则（2026-11-17 用户确认按原型做）：
 * 只有标了价格的“购买”商品能进购物车，租赁和询价商品不进；一次只结算一个商家。
 * 购物车不存价格，每次读取都按当前价格和当前城市重新判断，不能买的放进“失效商品”。
 * 结算预览只读，不建订单：下单要等统一交易模块（支付渠道 C1、收款主体 C2）。
 */
public class MallCartService {

    static final int MAX_LINES = 50;
    static final int MAX_QUANTITY = 99;
    static final int MAX_CHECKOUT_LINES = 20;
    private static final String LOCK_NAME = "mall-cart:";
    private static final String CITY_CODE_PATTERN = "^[A-Z0-9]{4,16}$";

    private final MallCartMapper mapper;
    private final Clock clock;
    private final InquiryQuotes inquiryQuotes;

    MallCartService(MallCartMapper mapper, Clock clock) {
        this(mapper, clock, (accountId, quoteId) -> {
            throw new MallInquiryException(MallInquiryException.Error.NOT_FOUND);
        });
    }

    MallCartService(MallCartMapper mapper, Clock clock, InquiryQuotes inquiryQuotes) {
        this.mapper = mapper;
        this.clock = clock;
        this.inquiryQuotes = inquiryQuotes;
    }

    /** 按询价报价下单时取那份报价（F4，由询价服务实现）：不是选定的、过期的由它抛询价的业务异常。 */
    interface InquiryQuotes {
        MallInquiryOrderQuote orderQuote(UUID accountId, UUID quoteId);
    }

    // ---- 购物车 ----

    @Transactional(readOnly = true)
    public CartView read(UUID accountId, String cityCode) {
        requireAccount(accountId);
        requireCityCode(cityCode);
        Map<UUID, List<CartItemView>> groups = new LinkedHashMap<>();
        Map<UUID, MallMerchantView> merchants = new LinkedHashMap<>();
        List<CartItemView> invalid = new ArrayList<>();
        for (CartRow row : mapper.findCart(accountId)) {
            CartItemView item = toItem(row, cityCode);
            if (item.invalidReason() != null) {
                invalid.add(item);
                continue;
            }
            merchants.putIfAbsent(item.merchant().organizationId(), item.merchant());
            groups.computeIfAbsent(item.merchant().organizationId(), id -> new ArrayList<>()).add(item);
        }
        List<CartGroupView> groupViews = groups.entrySet().stream()
                .map(entry -> new CartGroupView(merchants.get(entry.getKey()), entry.getValue()))
                .toList();
        int validCount = groupViews.stream().mapToInt(group -> group.items().size()).sum();
        return new CartView(groupViews, invalid, validCount);
    }

    /** 购物车角标：当前城市能买的商品有几种（不按件数）。 */
    @Transactional(readOnly = true)
    public CartCountView count(UUID accountId, String cityCode) {
        return new CartCountView(read(accountId, cityCode).validCount());
    }

    @Transactional
    public CartAddedView add(UUID accountId, UUID offerId, int quantity, String cityCode) {
        requireAccount(accountId);
        requireCityCode(cityCode);
        if (offerId == null || quantity < 1 || quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException("cart item is invalid");
        }
        OfferRow offer = mapper.findOffer(offerId);
        if (offer == null) {
            throw new MallCartException(Error.NOT_FOUND);
        }
        CartInvalidReason reason = invalidReason(offer, cityCode);
        if (reason == CartInvalidReason.NOT_PURCHASABLE) {
            throw new MallCartException(Error.NOT_PURCHASABLE);
        }
        if (reason == CartInvalidReason.NOT_SOLD_IN_CITY) {
            throw new MallCartException(Error.NOT_SOLD_IN_CITY);
        }
        if (reason != null) {
            throw new MallCartException(Error.NOT_FOUND);
        }
        mapper.lockOwner(LOCK_NAME, accountId);
        if (mapper.countOffer(accountId, offerId) == 0 && mapper.countLines(accountId) >= MAX_LINES) {
            throw new MallCartException(Error.CART_FULL);
        }
        AddedRow added = mapper.add(accountId, offerId, quantity, clock.instant());
        return new CartAddedView(added.cartItemId(), added.quantity(), count(accountId, cityCode).count());
    }

    @Transactional
    public CartQuantityView setQuantity(UUID accountId, UUID cartItemId, int quantity) {
        requireAccount(accountId);
        if (cartItemId == null || quantity < 1 || quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException("quantity is invalid");
        }
        if (mapper.setQuantity(accountId, cartItemId, quantity, clock.instant()) == 0) {
            throw new MallCartException(Error.NOT_FOUND);
        }
        return new CartQuantityView(cartItemId, quantity);
    }

    /** 删掉指定的几行；不是这个账号的行不受影响，也不报错。 */
    @Transactional
    public CartRemovedView remove(UUID accountId, List<UUID> cartItemIds) {
        requireAccount(accountId);
        if (cartItemIds == null || cartItemIds.isEmpty() || cartItemIds.size() > MAX_LINES
                || cartItemIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("cart items are invalid");
        }
        return new CartRemovedView(mapper.remove(accountId, List.copyOf(cartItemIds)));
    }

    /** “清空失效”：删掉在当前城市不能买的全部行。 */
    @Transactional
    public CartRemovedView removeInvalid(UUID accountId, String cityCode) {
        List<UUID> invalid = read(accountId, cityCode).invalidItems().stream()
                .map(CartItemView::cartItemId)
                .toList();
        return new CartRemovedView(invalid.isEmpty() ? 0 : mapper.remove(accountId, invalid));
    }

    // ---- 结算预览 ----

    /**
     * 确认订单页的内容：同一个商家的几样商品、可选的配送方式、金额。
     * 来源二选一：购物车里的几行（cartItemIds），或商品页“立即购买”的一个供给（offerId + quantity）。
     * 地址不在这里：由调用方按账号另读。
     */
    @Transactional(readOnly = true)
    public CheckoutPreview preview(
            UUID accountId,
            String cityCode,
            List<UUID> cartItemIds,
            UUID offerId,
            Integer quantity,
            String deliveryModeCode
    ) {
        return preview(accountId, cityCode, cartItemIds, offerId, quantity, deliveryModeCode, null);
    }

    /**
     * 同上，第三种来源（F4）：选定的询价报价（inquiryQuoteId）。数量是询价单的、单价是报价的，都不能改；
     * 规格写报价里的型号配置；配送方式按这家供给支持的。三种来源只能给一种。
     */
    @Transactional(readOnly = true)
    public CheckoutPreview preview(
            UUID accountId,
            String cityCode,
            List<UUID> cartItemIds,
            UUID offerId,
            Integer quantity,
            String deliveryModeCode,
            UUID inquiryQuoteId
    ) {
        requireAccount(accountId);
        requireCityCode(cityCode);
        boolean fromCart = cartItemIds != null && !cartItemIds.isEmpty();
        int sources = (fromCart ? 1 : 0) + (offerId != null ? 1 : 0) + (inquiryQuoteId != null ? 1 : 0);
        if (sources != 1) {
            throw new IllegalArgumentException("checkout needs cart items, one offer or one inquiry quote");
        }
        DeliveryMode wanted = DeliveryMode.parse(deliveryModeCode);
        List<Line> lines = fromCart
                ? cartLines(accountId, cartItemIds)
                : offerId != null ? List.of(directLine(offerId, quantity)) : List.of(quotedLine(accountId, inquiryQuoteId));

        List<CheckoutLineView> views = new ArrayList<>();
        for (Line line : lines) {
            CartInvalidReason reason = invalidReason(line.offer(), cityCode);
            // 询价商品本来就不是“能直接买”的：按报价下单时只看上下架和城市。
            if (reason != null && !(line.quoted() && reason == CartInvalidReason.NOT_PURCHASABLE
                    && soldInCity(line.offer(), cityCode))) {
                throw new MallCartException(Error.ITEM_UNAVAILABLE);
            }
            views.add(toLine(line));
        }
        long merchants = lines.stream().map(line -> line.offer().merchantId()).distinct().count();
        if (merchants != 1) {
            throw new MallCartException(Error.MULTIPLE_MERCHANTS);
        }

        OfferRow first = lines.getFirst().offer();
        boolean expressAvailable = lines.stream().allMatch(line -> line.offer().expressAvailable());
        // 去掉首尾空格再比：同一个自提点不因为录入时多一个空格变成两个。
        List<String> pickupPoints = lines.stream()
                .map(line -> blankToNull(line.offer().pickupPoint()))
                .distinct()
                .toList();
        String pickupPoint = pickupPoints.size() == 1 ? blankToNull(pickupPoints.getFirst()) : null;
        List<DeliveryOptionView> options = new ArrayList<>();
        if (expressAvailable) {
            options.add(new DeliveryOptionView(DeliveryMode.EXPRESS, joinDistinct(lines, Line::shippingNote)));
        }
        if (pickupPoint != null) {
            options.add(new DeliveryOptionView(DeliveryMode.PICKUP, pickupPoint));
        }
        DeliveryMode mode = options.stream().anyMatch(option -> option.mode() == wanted)
                ? wanted
                : options.isEmpty() ? null : options.getFirst().mode();

        long goods = views.stream().mapToLong(CheckoutLineView::subtotalMinor).sum();
        long shipping = mode == DeliveryMode.EXPRESS
                ? lines.stream().mapToLong(line -> line.offer().shippingFeeMinor()).max().orElse(0)
                : 0;
        return new CheckoutPreview(
                new MallMerchantView(
                        first.merchantId(), first.merchantName(), MallMerchantKind.valueOf(first.merchantKind())),
                views,
                views.stream().mapToInt(CheckoutLineView::quantity).sum(),
                joinDistinct(lines, line -> line.offer().fulfilledFrom()),
                options,
                mode,
                new CheckoutAmountsView(goods, shipping, goods + shipping, "CNY"));
    }

    private List<Line> cartLines(UUID accountId, List<UUID> cartItemIds) {
        if (cartItemIds.size() > MAX_CHECKOUT_LINES || cartItemIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("cart items are invalid");
        }
        List<UUID> wanted = cartItemIds.stream().distinct().toList();
        Map<UUID, CartRow> rows = mapper.findCart(accountId).stream()
                .collect(Collectors.toMap(CartRow::cartItemId, row -> row));
        List<Line> lines = new ArrayList<>();
        for (UUID id : wanted) {
            CartRow row = rows.get(id);
            if (row == null) {
                throw new MallCartException(Error.NOT_FOUND);
            }
            lines.add(new Line(row.cartItemId(), row.offer(), row.quantity()));
        }
        return lines;
    }

    private Line directLine(UUID offerId, Integer quantity) {
        if (quantity == null || quantity < 1 || quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException("quantity is invalid");
        }
        OfferRow offer = mapper.findOffer(offerId);
        if (offer == null) {
            throw new MallCartException(Error.NOT_FOUND);
        }
        if (!isPurchasable(offer)) {
            throw new MallCartException(Error.NOT_PURCHASABLE);
        }
        return new Line(null, offer, quantity);
    }

    private Line quotedLine(UUID accountId, UUID inquiryQuoteId) {
        MallInquiryOrderQuote quote = inquiryQuotes.orderQuote(accountId, inquiryQuoteId);
        OfferRow offer = mapper.findOffer(quote.offerId());
        if (offer == null) {
            throw new MallCartException(Error.ITEM_UNAVAILABLE);
        }
        return new Line(null, offer, quote.quantity(), quote.unitAmountMinor(), quote.model());
    }

    /** 商品、规格、供给都在售，并且在这个城市（或全国）卖。 */
    private static boolean soldInCity(OfferRow offer, String cityCode) {
        String offerCity = blankToNull(offer.offerCityCode());
        return "PUBLISHED".equals(offer.productStatus()) && "ACTIVE".equals(offer.skuStatus())
                && "ACTIVE".equals(offer.offerStatus()) && (offerCity == null || offerCity.equals(cityCode));
    }

    // ---- 规则 ----

    /** 这个供给在这个城市现在能不能买；能买返回 null。 */
    static CartInvalidReason invalidReason(OfferRow offer, String cityCode) {
        if (!"PUBLISHED".equals(offer.productStatus()) || !"ACTIVE".equals(offer.skuStatus())) {
            return CartInvalidReason.PRODUCT_OFFLINE;
        }
        if (!"ACTIVE".equals(offer.offerStatus())) {
            return CartInvalidReason.OFFER_UNAVAILABLE;
        }
        if (!isPurchasable(offer)) {
            return CartInvalidReason.NOT_PURCHASABLE;
        }
        String offerCity = blankToNull(offer.offerCityCode());
        if (offerCity != null && !offerCity.equals(cityCode)) {
            return CartInvalidReason.NOT_SOLD_IN_CITY;
        }
        return null;
    }

    /** 只有按件标价的“购买”商品能进购物车；租赁按天计价、询价没有价格，都不进。 */
    private static boolean isPurchasable(OfferRow offer) {
        return MallSaleMode.BUY.name().equals(offer.saleMode())
                && offer.unitAmountMinor() != null
                && MallPriceUnit.ITEM.name().equals(offer.priceUnit());
    }

    // ---- 转换 ----

    private static CartItemView toItem(CartRow row, String cityCode) {
        OfferRow offer = row.offer();
        CartInvalidReason reason = invalidReason(offer, cityCode);
        List<DeliveryMode> modes = new ArrayList<>();
        if (offer.expressAvailable()) {
            modes.add(DeliveryMode.EXPRESS);
        }
        if (blankToNull(offer.pickupPoint()) != null) {
            modes.add(DeliveryMode.PICKUP);
        }
        return new CartItemView(
                row.cartItemId(),
                offer.offerId(),
                offer.productId(),
                offer.productName(),
                offer.skuName(),
                MallCatalogReadService.secureMedia(offer.coverUrl(), offer.productName()),
                new MallMerchantView(
                        offer.merchantId(), offer.merchantName(), MallMerchantKind.valueOf(offer.merchantKind())),
                reason == null ? offer.unitAmountMinor() : null,
                row.quantity(),
                blankToNull(offer.shippingNote()),
                modes,
                reason);
    }

    private static CheckoutLineView toLine(Line line) {
        OfferRow offer = line.offer();
        long unit = line.quoted() ? line.quotedUnitMinor() : offer.unitAmountMinor();
        return new CheckoutLineView(
                line.cartItemId(),
                offer.offerId(),
                offer.productId(),
                offer.productName(),
                line.quoted() ? line.quotedModel() : offer.skuName(),
                MallCatalogReadService.secureMedia(offer.coverUrl(), offer.productName()),
                unit,
                line.quantity(),
                Math.multiplyExact(unit, (long) line.quantity()));
    }

    private static String joinDistinct(List<Line> lines, java.util.function.Function<Line, String> value) {
        String joined = lines.stream()
                .map(value)
                .map(MallCartService::blankToNull)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining("；"));
        return joined.isEmpty() ? null : joined;
    }

    private static void requireAccount(UUID accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("account is required");
        }
    }

    private static void requireCityCode(String cityCode) {
        if (cityCode == null || !cityCode.matches(CITY_CODE_PATTERN)) {
            throw new IllegalArgumentException("city code is invalid");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 一行：购物车行、单个供给，或按询价报价（quotedUnitMinor、quotedModel 不为空）。 */
    private record Line(UUID cartItemId, OfferRow offer, int quantity, Long quotedUnitMinor, String quotedModel) {
        Line(UUID cartItemId, OfferRow offer, int quantity) {
            this(cartItemId, offer, quantity, null, null);
        }

        boolean quoted() {
            return quotedUnitMinor != null;
        }

        String shippingNote() {
            return offer.shippingNote();
        }
    }
}

/** 购物车里一样商品为什么不能买了。 */
enum CartInvalidReason {
    /** 商品或规格已下架 */
    PRODUCT_OFFLINE,
    /** 这个商家不再卖了 */
    OFFER_UNAVAILABLE,
    /** 变成了租赁、询价或没有价格 */
    NOT_PURCHASABLE,
    /** 当前城市不卖 */
    NOT_SOLD_IN_CITY
}

enum DeliveryMode {
    EXPRESS,
    PICKUP;

    static DeliveryMode parse(String code) {
        if (code == null || code.isBlank()) {
            return EXPRESS;
        }
        try {
            return valueOf(code.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("delivery mode is invalid");
        }
    }
}

record CartView(List<CartGroupView> groups, List<CartItemView> invalidItems, int validCount) {
}

record CartGroupView(MallMerchantView merchant, List<CartItemView> items) {
}

record CartItemView(
        UUID cartItemId,
        UUID offerId,
        UUID productId,
        String productName,
        String skuName,
        MallMediaView coverImage,
        MallMerchantView merchant,
        Long unitAmountMinor,
        int quantity,
        String shippingNote,
        List<DeliveryMode> deliveryModes,
        CartInvalidReason invalidReason
) {
}

record CartCountView(int count) {
}

record CartAddedView(UUID cartItemId, int quantity, int cartCount) {
}

record CartQuantityView(UUID cartItemId, int quantity) {
}

record CartRemovedView(int removed) {
}

record CheckoutLineView(
        UUID cartItemId,
        UUID offerId,
        UUID productId,
        String productName,
        String skuName,
        MallMediaView coverImage,
        long unitAmountMinor,
        int quantity,
        long subtotalMinor
) {
}

record DeliveryOptionView(DeliveryMode mode, String note) {
}

record CheckoutAmountsView(long goodsMinor, long shippingMinor, long payableMinor, String currency) {
}

/** 结算预览里和地址无关的部分；地址由控制器另读后合在一起返回。 */
record CheckoutPreview(
        MallMerchantView merchant,
        List<CheckoutLineView> lines,
        int pieceCount,
        String fulfilledFrom,
        List<DeliveryOptionView> deliveryOptions,
        DeliveryMode deliveryMode,
        CheckoutAmountsView amounts
) {
}
