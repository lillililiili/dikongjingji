package com.lowaltitude.inspection.trade.application;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.trade.application.TradeCheckoutPort.CheckoutQuote;
import com.lowaltitude.inspection.trade.application.TradeCheckoutPort.CheckoutRequest;
import com.lowaltitude.inspection.trade.application.TradeCheckoutPort.QuotedLine;
import com.lowaltitude.inspection.trade.application.TradeException.Error;
import com.lowaltitude.inspection.trade.application.TradeViews.MerchantAccountView;
import com.lowaltitude.inspection.trade.application.TradeViews.SubmitResult;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeAmountsView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeDeliveryView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeMediaView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeMerchantView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeOrderLineView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeOrderPageView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeOrderView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeRefundView;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper.CloseCauseRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper.DeliveryRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper.ExpiredRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper.KeyRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper.LineRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper.MerchantAccountRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper.OrderRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper.PickupRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper.RefundRow;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单（E02，契约 contracts/openapi-trade.yaml）：提交、我的订单、详情、取消未付款订单、超时关闭、商家收款账户登记。
 * 付款、发货、退款在 E04、E05 接上。规则见 docs/redesign/03-App实现任务计划.md §19.7。
 */
public class TradeOrderService {

    /** 未付款订单多久关闭（用户 2026-11-18 确认 30 分钟）。 */
    static final Duration PAY_WINDOW = Duration.ofMinutes(30);
    static final int MAX_PAGE_SIZE = 50;
    static final int MAX_PAGE = 10_000;
    static final int MAX_NOTE_LENGTH = 50;
    static final int EXPIRE_BATCH = 100;
    /** 发货后多久自动确认收货（用户 2026-11-18 确认 10 天）。 */
    static final Duration AUTO_RECEIPT_AFTER = Duration.ofDays(10);
    /** 取货码连续输错多少次后锁定。 */
    static final int MAX_PICKUP_FAILURES = 10;
    static final int MAX_CARRIER_LENGTH = 40;
    static final int MAX_TRACKING_LENGTH = 40;
    private static final ZoneId ORDER_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter ORDER_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    static final String CITY_CODE_PATTERN = "^[A-Z0-9]{4,16}$";
    private static final String MALL_ORDER_PREFIX = "MO";
    /** 业务来源订单的配送方式（E09）：不发货。 */
    private static final String NO_DELIVERY = "NONE";
    /** 买家已经付过款的状态：商家从这时起能看到完整手机号和收货地址。 */
    private static final Set<String> PAID_STATUSES = Set.of(
            TradeOrderStatus.PAID.name(),
            TradeOrderStatus.SHIPPED.name(),
            TradeOrderStatus.COMPLETED.name(),
            TradeOrderStatus.REFUNDING.name(),
            TradeOrderStatus.REFUNDED.name(),
            TradeOrderStatus.REFUND_FAILED.name());
    /** 契约 TradeOrder.closeReason 的取值，和 trade_state_transition.cause 的写法一样。 */
    private static final Set<String> CLOSE_REASONS = Set.of("BUYER_CANCEL", "TIMEOUT");

    private final TradeOrderMapper mapper;
    private final TradeCheckoutPort checkout;
    private final Clock clock;
    private final Supplier<String> orderNoSuffix;
    private final TradeSourceEvents events;
    private final TradeBuyerEvents buyerEvents;

    /** 只有商城订单（没有业务来源的监听者）。 */
    TradeOrderService(
            TradeOrderMapper mapper,
            TradeCheckoutPort checkout,
            Clock clock,
            Supplier<String> orderNoSuffix
    ) {
        this(mapper, checkout, clock, orderNoSuffix, TradeSourceEvents.none());
    }

    TradeOrderService(
            TradeOrderMapper mapper,
            TradeCheckoutPort checkout,
            Clock clock,
            Supplier<String> orderNoSuffix,
            TradeSourceEvents events
    ) {
        this(mapper, checkout, clock, orderNoSuffix, events, TradeBuyerEvents.none());
    }

    /** @param buyerEvents 发货时告诉买家（R6-5） */
    TradeOrderService(
            TradeOrderMapper mapper,
            TradeCheckoutPort checkout,
            Clock clock,
            Supplier<String> orderNoSuffix,
            TradeSourceEvents events,
            TradeBuyerEvents buyerEvents
    ) {
        this.mapper = mapper;
        this.checkout = checkout;
        this.clock = clock;
        this.orderNoSuffix = orderNoSuffix;
        this.events = events;
        this.buyerEvents = buyerEvents;
    }

    // ---- 提交订单 ----

    /**
     * 提交订单。服务端重新报价，和页面看到的应付不一致时拒绝；同一个幂等键重放返回第一次建的订单。
     * 快照商品、单价、配送和商家当时的收款账户；来自购物车的行在同一事务里删掉。
     */
    @Transactional
    public SubmitResult submit(UUID buyerId, UUID idempotencyKey, SubmitCommand command) {
        requireBuyer(buyerId);
        SubmitCommand normalized = validate(idempotencyKey, command);
        String requestHash = hash(normalized);

        mapper.lockBuyer(buyerId);
        KeyRow replayed = mapper.findByKey(buyerId, idempotencyKey);
        if (replayed != null) {
            if (!requestHash.equals(replayed.requestHash().trim())) {
                throw new TradeException(Error.IDEMPOTENCY_CONFLICT);
            }
            return new SubmitResult(read(buyerId, replayed.id()), false);
        }

        CheckoutRequest request = new CheckoutRequest(
                normalized.cityCode(),
                normalized.cartItemIds(),
                normalized.offerId(),
                normalized.quantity(),
                normalized.deliveryMode(),
                normalized.addressId(),
                normalized.inquiryQuoteId());
        CheckoutQuote quote = checkout.quote(buyerId, request);
        requirePricedLines(quote);
        // 一份询价报价同时只能有一张订单：上一张关闭了（没付款）才能再下。买家锁挡住了同一个人同时提交两次。
        if (quote.previousOrderId() != null) {
            OrderRow previous = mapper.findOrder(quote.previousOrderId());
            if (previous != null && !TradeOrderStatus.CLOSED.name().equals(previous.status())) {
                throw new TradeException(Error.QUOTE_ALREADY_ORDERED);
            }
        }
        // 商家收不了款时，买家改配送方式、填地址、刷新价格都没有用，所以最先判断。
        MerchantAccountRow account = mapper.findMerchantAccount(quote.merchantId());
        if (account == null || !account.active()) {
            throw new TradeException(Error.MERCHANT_PAYMENT_UNAVAILABLE);
        }
        if (quote.deliveryMode() == null || !quote.deliveryMode().equals(normalized.deliveryMode())) {
            throw new TradeException(Error.DELIVERY_UNAVAILABLE);
        }
        if ("EXPRESS".equals(quote.deliveryMode()) && quote.address() == null) {
            throw new TradeException(Error.ADDRESS_REQUIRED);
        }
        if (quote.payableMinor() != normalized.expectedPayableMinor()) {
            throw new TradeException(Error.AMOUNT_CHANGED);
        }

        Instant now = clock.instant();
        UUID orderId = mapper.insertOrder(
                newOrderNo(MALL_ORDER_PREFIX, now),
                buyerId,
                quote.merchantId(),
                quote.merchantKind(),
                quote.merchantName(),
                account.subMchid(),
                normalized.cityCode(),
                quote.deliveryMode(),
                quote.goodsMinor(),
                quote.shippingMinor(),
                quote.payableMinor(),
                normalized.buyerNote(),
                now.plus(PAY_WINDOW),
                idempotencyKey,
                requestHash,
                now);
        int sort = 0;
        for (QuotedLine line : quote.lines()) {
            mapper.insertLine(
                    orderId,
                    line.productId(),
                    line.offerId(),
                    line.productName(),
                    line.skuName(),
                    line.coverUrl(),
                    line.unitAmountMinor(),
                    line.quantity(),
                    line.subtotalMinor(),
                    sort,
                    now);
            sort += 10;
        }
        TradeCheckoutPort.AddressSnapshot address = quote.address();
        boolean express = "EXPRESS".equals(quote.deliveryMode());
        mapper.insertDelivery(
                orderId,
                quote.deliveryMode(),
                express ? address.recipientName() : null,
                express ? address.phone() : null,
                express ? address.provinceName() : null,
                express ? address.cityName() : null,
                express ? address.detail() : null,
                express ? null : quote.pickupPoint(),
                now);
        mapper.insertTransition(orderId, null, TradeOrderStatus.PENDING_PAYMENT.name(), "SUBMIT", buyerId, now);
        if (!normalized.cartItemIds().isEmpty()) {
            checkout.removeCartLines(buyerId, normalized.cartItemIds());
        }
        checkout.orderPlaced(buyerId, request, orderId);
        return new SubmitResult(read(buyerId, orderId), true);
    }

    // ---- 读 ----

    @Transactional(readOnly = true)
    public TradeOrderView read(UUID buyerId, UUID orderId) {
        requireBuyer(buyerId);
        if (orderId == null) {
            throw new IllegalArgumentException("order id is required");
        }
        OrderRow row = mapper.findBuyerOrder(buyerId, orderId);
        if (row == null) {
            throw new TradeException(Error.NOT_FOUND);
        }
        return assemble(List.of(row), true).getFirst();
    }

    @Transactional(readOnly = true)
    public TradeOrderPageView list(UUID buyerId, String tab, int page, int size) {
        requireBuyer(buyerId);
        if (page < 1 || page > MAX_PAGE || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
        List<String> statuses = TradeOrderStatus.ofTab(tab);
        long total = mapper.countBuyerOrders(buyerId, statuses);
        List<OrderRow> rows = mapper.findBuyerOrders(buyerId, statuses, size, (page - 1) * size);
        return new TradeOrderPageView(page, size, total, (long) page * size < total, assemble(rows, true));
    }

    // ---- 取消与超时 ----

    /**
     * 买家取消。待付款：关闭（同一张已关闭、退款中、已退款的单再取消一次没有变化）。已付款未发货的要退款，
     * 抛 REFUND_REQUIRED 交给退款服务；发起过支付的抛 PAYMENT_IN_PROGRESS 交给支付服务先查单。
     * version 是页面上看到的订单版本，对不上说明订单已经变了。
     * 业务来源（E09 收费报名）的订单：待付款的同样可以关（关闭后通知业务模块释放名额）；已付款的不能在这里取消，
     * 退款由业务单的退改发起。
     */
    @Transactional
    public TradeOrderView cancel(UUID buyerId, UUID orderId, int version) {
        requireBuyer(buyerId);
        if (orderId == null) {
            throw new IllegalArgumentException("order id is required");
        }
        OrderRow row = mapper.lockBuyerOrder(buyerId, orderId);
        if (row == null) {
            throw new TradeException(Error.NOT_FOUND);
        }
        TradeOrderStatus current = TradeOrderStatus.valueOf(row.status());
        if (current == TradeOrderStatus.CLOSED || current == TradeOrderStatus.REFUNDING
                || current == TradeOrderStatus.REFUNDED) {
            return read(buyerId, orderId);
        }
        if (current == TradeOrderStatus.PAID) {
            if (!TradeSourceEvents.MALL.equals(row.source())) {
                throw new TradeException(Error.ORDER_STATE_INVALID);
            }
            throw new TradeException(Error.REFUND_REQUIRED);
        }
        if (current != TradeOrderStatus.PENDING_PAYMENT) {
            throw new TradeException(Error.ORDER_STATE_INVALID);
        }
        if (row.version() != version) {
            throw new TradeException(Error.VERSION_CONFLICT);
        }
        // 发起过支付的单不能直接关：买家可能正在付。交给支付服务先查单、在渠道关单再关这里。
        if (mapper.countOpenPayments(orderId) > 0) {
            throw new TradeException(Error.PAYMENT_IN_PROGRESS);
        }
        Instant now = clock.instant();
        mapper.closeUnpaid(orderId, version, now);
        mapper.insertTransition(
                orderId, TradeOrderStatus.PENDING_PAYMENT.name(), TradeOrderStatus.CLOSED.name(), "BUYER_CANCEL", buyerId, now);
        events.closed(orderId);
        return read(buyerId, orderId);
    }

    /** 关闭已过付款截止时间的待付款订单，返回关了几张。定时任务调用；多实例同时跑时用 SKIP LOCKED 错开。 */
    @Transactional
    public int closeExpired() {
        Instant now = clock.instant();
        int closed = 0;
        for (ExpiredRow row : mapper.lockExpired(now, EXPIRE_BATCH)) {
            if (mapper.closeUnpaid(row.id(), row.version(), now) > 0) {
                mapper.insertTransition(
                        row.id(), TradeOrderStatus.PENDING_PAYMENT.name(), TradeOrderStatus.CLOSED.name(), "TIMEOUT", null, now);
                events.closed(row.id());
                closed++;
            }
        }
        return closed;
    }

    // ---- 收货（E05） ----

    /** 买家确认收货：已发货 → 已完成。已完成的再确认一次没有变化。 */
    @Transactional
    public TradeOrderView confirmReceipt(UUID buyerId, UUID orderId, int version) {
        requireBuyer(buyerId);
        if (orderId == null) {
            throw new IllegalArgumentException("order id is required");
        }
        OrderRow row = mapper.lockBuyerOrder(buyerId, orderId);
        if (row == null) {
            throw new TradeException(Error.NOT_FOUND);
        }
        if (TradeOrderStatus.COMPLETED.name().equals(row.status())) {
            return read(buyerId, orderId);
        }
        if (!TradeOrderStatus.SHIPPED.name().equals(row.status())) {
            throw new TradeException(Error.ORDER_STATE_INVALID);
        }
        if (row.version() != version) {
            throw new TradeException(Error.VERSION_CONFLICT);
        }
        Instant now = clock.instant();
        mapper.markCompleted(orderId, TradeOrderStatus.SHIPPED.name(), version, now);
        mapper.insertTransition(orderId, TradeOrderStatus.SHIPPED.name(), TradeOrderStatus.COMPLETED.name(), "RECEIPT", buyerId, now);
        return read(buyerId, orderId);
    }

    /** 发货满 10 天还没确认收货的自动确认，返回确认了几张。定时任务调用。 */
    @Transactional
    public int autoConfirmReceipts() {
        Instant now = clock.instant();
        int confirmed = 0;
        for (ExpiredRow row : mapper.lockReceiptDue(now.minus(AUTO_RECEIPT_AFTER), EXPIRE_BATCH)) {
            if (mapper.markCompleted(row.id(), TradeOrderStatus.SHIPPED.name(), row.version(), now) > 0) {
                mapper.insertTransition(
                        row.id(), TradeOrderStatus.SHIPPED.name(), TradeOrderStatus.COMPLETED.name(), "AUTO_RECEIPT", null, now);
                confirmed++;
            }
        }
        return confirmed;
    }

    // ---- 商家（E05）：受权接口，没有页面 ----

    /** 本机构卖出的订单，新的在前。status 为空时全部。 */
    @Transactional(readOnly = true)
    public TradeOrderPageView listForMerchant(UUID merchantId, String status, int page, int size) {
        requireMerchant(merchantId);
        if (page < 1 || page > MAX_PAGE || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
        String filter = null;
        if (status != null && !status.isBlank()) {
            try {
                filter = TradeOrderStatus.valueOf(status.trim()).name();
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("status is invalid");
            }
        }
        long total = mapper.countMerchantOrders(merchantId, filter);
        List<OrderRow> rows = mapper.findMerchantOrders(merchantId, filter, size, (page - 1) * size);
        return new TradeOrderPageView(page, size, total, (long) page * size < total, assemble(rows, false));
    }

    @Transactional(readOnly = true)
    public TradeOrderView readForMerchant(UUID merchantId, UUID orderId) {
        requireMerchant(merchantId);
        OrderRow row = orderId == null ? null : mapper.findMerchantOrder(merchantId, orderId);
        if (row == null) {
            throw new TradeException(Error.NOT_FOUND);
        }
        return assemble(List.of(row), false).getFirst();
    }

    /** 发货：已付款的快递单填物流公司和单号 → 已发货。同一张单用同样的单号再发一次没有变化。 */
    @Transactional
    public TradeOrderView ship(UUID merchantId, UUID operatorId, UUID orderId, int version, String carrierName, String trackingNo) {
        requireMerchant(merchantId);
        String carrier = text(carrierName, MAX_CARRIER_LENGTH, "carrier name");
        String tracking = text(trackingNo, MAX_TRACKING_LENGTH, "tracking number");
        OrderRow row = orderId == null ? null : mapper.lockMerchantOrder(merchantId, orderId);
        if (row == null) {
            throw new TradeException(Error.NOT_FOUND);
        }
        if (TradeOrderStatus.SHIPPED.name().equals(row.status())) {
            TradeOrderView shipped = readForMerchant(merchantId, orderId);
            if (carrier.equals(shipped.delivery().carrierName()) && tracking.equals(shipped.delivery().trackingNo())) {
                return shipped;
            }
            throw new TradeException(Error.ORDER_STATE_INVALID);
        }
        if (!TradeOrderStatus.PAID.name().equals(row.status()) || !"EXPRESS".equals(row.deliveryMode())) {
            throw new TradeException(Error.ORDER_STATE_INVALID);
        }
        if (row.version() != version) {
            throw new TradeException(Error.VERSION_CONFLICT);
        }
        Instant now = clock.instant();
        mapper.markShipped(orderId, version, now);
        mapper.saveTracking(orderId, carrier, tracking, now);
        mapper.insertTransition(orderId, TradeOrderStatus.PAID.name(), TradeOrderStatus.SHIPPED.name(), "SHIPMENT", operatorId, now);
        buyerEvents.shipped(orderId, now);
        return readForMerchant(merchantId, orderId);
    }

    /**
     * 自提核销：买家出示 6 位取货码，商家核对后 → 已完成。输错记一次，连续输错 10 次后锁定。
     * 输错时要把次数留下来，所以 TradeException 不回滚。
     */
    @Transactional(noRollbackFor = TradeException.class)
    public TradeOrderView completePickup(UUID merchantId, UUID operatorId, UUID orderId, int version, String pickupCode) {
        requireMerchant(merchantId);
        String code = pickupCode == null ? "" : pickupCode.trim();
        if (!code.matches("^[0-9]{6}$")) {
            throw new IllegalArgumentException("pickup code must be 6 digits");
        }
        OrderRow row = orderId == null ? null : mapper.lockMerchantOrder(merchantId, orderId);
        if (row == null) {
            throw new TradeException(Error.NOT_FOUND);
        }
        if (!TradeOrderStatus.PAID.name().equals(row.status()) || !"PICKUP".equals(row.deliveryMode())) {
            throw new TradeException(Error.ORDER_STATE_INVALID);
        }
        if (row.version() != version) {
            throw new TradeException(Error.VERSION_CONFLICT);
        }
        Instant now = clock.instant();
        PickupRow pickup = mapper.lockPickup(orderId);
        if (pickup == null || pickup.pickupCode() == null || pickup.pickupFailures() >= MAX_PICKUP_FAILURES) {
            throw new TradeException(Error.PICKUP_CODE_INVALID);
        }
        if (!MessageDigest.isEqual(pickup.pickupCode().trim().getBytes(StandardCharsets.US_ASCII),
                code.getBytes(StandardCharsets.US_ASCII))) {
            mapper.recordPickupFailure(orderId, now);
            throw new TradeException(Error.PICKUP_CODE_INVALID);
        }
        mapper.markCompleted(orderId, TradeOrderStatus.PAID.name(), version, now);
        mapper.insertTransition(orderId, TradeOrderStatus.PAID.name(), TradeOrderStatus.COMPLETED.name(), "PICKUP", operatorId, now);
        return readForMerchant(merchantId, orderId);
    }

    // ---- 商家收款账户 ----

    /** 平台登记商家的微信子商户号。二级商户进件一期在微信服务商后台人工完成，这里只记结果。 */
    @Transactional
    public MerchantAccountView saveMerchantAccount(UUID organizationId, String subMchid, Boolean active) {
        if (organizationId == null || active == null) {
            throw new IllegalArgumentException("merchant account is invalid");
        }
        String code = subMchid == null ? "" : subMchid.trim();
        if (!code.matches("^[0-9]{8,32}$")) {
            throw new IllegalArgumentException("sub_mchid is invalid");
        }
        if (mapper.countOrganization(organizationId) == 0) {
            throw new TradeException(Error.NOT_FOUND);
        }
        MerchantAccountRow saved = mapper.saveMerchantAccount(organizationId, code, active, clock.instant());
        return new MerchantAccountView(saved.organizationId(), saved.subMchid(), saved.active());
    }

    // ---- 转换 ----

    private List<TradeOrderView> assemble(List<OrderRow> rows, boolean forBuyer) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(OrderRow::id).toList();
        Map<UUID, List<LineRow>> lines = mapper.findLines(ids).stream()
                .collect(Collectors.groupingBy(LineRow::orderId));
        Map<UUID, DeliveryRow> deliveries = mapper.findDeliveries(ids).stream()
                .collect(Collectors.toMap(DeliveryRow::orderId, Function.identity()));
        Map<UUID, List<RefundRow>> refunds = mapper.findRefunds(ids).stream()
                .collect(Collectors.groupingBy(RefundRow::orderId));
        // 关闭原因只有已关闭的单要：没有已关闭的就不查。
        List<UUID> closedIds = rows.stream()
                .filter(row -> TradeOrderStatus.CLOSED.name().equals(row.status()))
                .map(OrderRow::id)
                .toList();
        Map<UUID, String> closeCauses = closedIds.isEmpty()
                ? Map.of()
                : mapper.findCloseCauses(closedIds).stream()
                        .collect(Collectors.toMap(CloseCauseRow::orderId, CloseCauseRow::cause, (first, second) -> first));
        return rows.stream()
                .map(row -> toView(
                        row,
                        lines.getOrDefault(row.id(), List.of()),
                        deliveries.get(row.id()),
                        refunds.getOrDefault(row.id(), List.of()),
                        closeCauses.get(row.id()),
                        forBuyer))
                .toList();
    }

    private static TradeOrderView toView(
            OrderRow row,
            List<LineRow> lines,
            DeliveryRow delivery,
            List<RefundRow> refunds,
            String closeCause,
            boolean forBuyer
    ) {
        TradeOrderStatus status = TradeOrderStatus.valueOf(row.status());
        boolean mall = TradeSourceEvents.MALL.equals(row.source());
        return new TradeOrderView(
                row.id(),
                row.orderNo(),
                row.source(),
                row.sourceRef(),
                status,
                new TradeMerchantView(row.merchantId(), row.merchantName(), row.merchantKind()),
                lines.stream()
                        .map(line -> new TradeOrderLineView(
                                line.id(),
                                line.productId(),
                                line.offerId(),
                                line.productName(),
                                line.skuName(),
                                secureMedia(line.coverUrl(), line.productName()),
                                line.unitAmountMinor(),
                                line.quantity(),
                                line.subtotalMinor()))
                        .toList(),
                toDelivery(row, delivery, forBuyer),
                new TradeAmountsView(
                        row.goodsMinor(),
                        row.shippingMinor(),
                        row.payableMinor(),
                        row.paidMinor(),
                        row.refundedMinor(),
                        row.currency() == null ? "CNY" : row.currency().trim()),
                row.buyerNote(),
                row.createdAt(),
                status == TradeOrderStatus.PENDING_PAYMENT ? row.payBefore() : null,
                row.paidAt(),
                row.completedAt(),
                row.closedAt(),
                closeReason(status, closeCause),
                refunds.stream()
                        .map(refund -> new TradeRefundView(
                                refund.id(),
                                refund.amountMinor(),
                                refund.reason(),
                                refund.status(),
                                refund.createdAt(),
                                refund.succeededAt()))
                        .toList(),
                // 待付款直接关；已付款未发货（未自提）的商城订单取消即全额退款。业务来源的已付款订单由业务单退改。
                status == TradeOrderStatus.PENDING_PAYMENT || (status == TradeOrderStatus.PAID && mall),
                row.version());
    }

    /** 已关闭的单是买家取消还是超时关闭；别的状态、或原因不是这两种（不该有）时为 null。 */
    private static String closeReason(TradeOrderStatus status, String cause) {
        if (status != TradeOrderStatus.CLOSED || cause == null) {
            return null;
        }
        String trimmed = cause.trim();
        return CLOSE_REASONS.contains(trimmed) ? trimmed : null;
    }

    private static TradeDeliveryView toDelivery(OrderRow row, DeliveryRow delivery, boolean forBuyer) {
        if (NO_DELIVERY.equals(row.deliveryMode())) {
            // 业务来源的订单（E09）不发货，没有配送行。
            return null;
        }
        if (delivery == null) {
            return new TradeDeliveryView(row.deliveryMode(), null, null, null, null, null, null, null, null, row.shippedAt());
        }
        String addressLine = null;
        if (delivery.cityName() != null) {
            String region = Objects.equals(delivery.provinceName(), delivery.cityName())
                    ? delivery.cityName()
                    : Objects.toString(delivery.provinceName(), "") + delivery.cityName();
            addressLine = region + " " + delivery.addressDetail();
        }
        // 商家只在买家付款之后拿到完整手机号和收货地址（发货要用）；待付款、已关闭的单只看到收货人和打码手机号。
        boolean merchantMayShip = !forBuyer && PAID_STATUSES.contains(row.status());
        return new TradeDeliveryView(
                delivery.mode(),
                delivery.recipientName(),
                maskPhone(delivery.phone()),
                merchantMayShip && delivery.phone() != null ? delivery.phone().trim() : null,
                forBuyer || merchantMayShip ? addressLine : null,
                delivery.pickupPoint(),
                // 取货码只在“已付款、待自提”时给买家：取过货、退款了就不该再拿它去提货。
                forBuyer && TradeOrderStatus.PAID.name().equals(row.status()) && delivery.pickupCode() != null
                        ? delivery.pickupCode().trim()
                        : null,
                delivery.carrierName(),
                delivery.trackingNo(),
                row.shippedAt());
    }

    private static String maskPhone(String phone) {
        if (phone == null) {
            return null;
        }
        String value = phone.trim();
        return value.matches("^\\d{11}$") ? value.substring(0, 3) + "****" + value.substring(7) : "****";
    }

    /** 只放行带主机名的 HTTPS 地址（AGENTS.md §11）。 */
    private static TradeMediaView secureMedia(String url, String alt) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    ? new TradeMediaView(uri.toString(), alt)
                    : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    // ---- 校验与工具 ----

    private static SubmitCommand validate(UUID idempotencyKey, SubmitCommand command) {
        if (idempotencyKey == null || command == null) {
            throw new IllegalArgumentException("order is invalid");
        }
        if (command.cityCode() == null || !command.cityCode().trim().matches(CITY_CODE_PATTERN)) {
            throw new IllegalArgumentException("city code is invalid");
        }
        List<UUID> cartItemIds = command.cartItemIds() == null ? List.of() : command.cartItemIds();
        if (cartItemIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("cart items are invalid");
        }
        boolean fromCart = !cartItemIds.isEmpty();
        boolean fromQuote = command.inquiryQuoteId() != null;
        int sources = (fromCart ? 1 : 0) + (command.offerId() != null ? 1 : 0) + (fromQuote ? 1 : 0);
        if (sources != 1) {
            throw new IllegalArgumentException("order needs cart items, one offer or one inquiry quote");
        }
        if (command.offerId() != null && (command.quantity() == null || command.quantity() < 1 || command.quantity() > 99)) {
            throw new IllegalArgumentException("quantity is invalid");
        }
        if (!"EXPRESS".equals(command.deliveryMode()) && !"PICKUP".equals(command.deliveryMode())) {
            throw new IllegalArgumentException("delivery mode is invalid");
        }
        if (command.expectedPayableMinor() == null || command.expectedPayableMinor() < 1) {
            throw new IllegalArgumentException("expected payable is invalid");
        }
        if (!Boolean.TRUE.equals(command.agreementAccepted())) {
            throw new IllegalArgumentException("agreement is required");
        }
        String note = command.buyerNote() == null ? null : command.buyerNote().trim();
        if (note != null && (note.length() > MAX_NOTE_LENGTH || note.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("note is invalid");
        }
        return new SubmitCommand(
                command.cityCode().trim(),
                cartItemIds.stream().distinct().toList(),
                command.offerId(),
                // 按询价报价下单时数量是询价单的，请求里的数量不用。
                command.offerId() != null ? command.quantity() : null,
                command.deliveryMode(),
                "EXPRESS".equals(command.deliveryMode()) ? command.addressId() : null,
                note == null || note.isEmpty() ? null : note,
                command.expectedPayableMinor(),
                true,
                command.inquiryQuoteId());
    }

    /**
     * 每一行的单价和小计、整单的商品金额都要大于 0。0 元的单微信下不了单，也不该出现（商城的标价是正数），
     * 所以按请求不合法拒绝，什么都不写。
     */
    private static void requirePricedLines(CheckoutQuote quote) {
        if (quote.lines() == null || quote.lines().isEmpty() || quote.goodsMinor() <= 0) {
            throw new IllegalArgumentException("order has no priced goods");
        }
        for (QuotedLine line : quote.lines()) {
            if (line.unitAmountMinor() <= 0 || line.subtotalMinor() <= 0) {
                throw new IllegalArgumentException("order line is not priced");
            }
        }
    }

    private static String hash(SubmitCommand command) {
        String text = String.join("|",
                command.cityCode(),
                command.cartItemIds().stream().map(UUID::toString).sorted().collect(Collectors.joining(",")),
                Objects.toString(command.offerId(), ""),
                Objects.toString(command.quantity(), ""),
                command.deliveryMode(),
                Objects.toString(command.addressId(), ""),
                Objects.toString(command.buyerNote(), ""),
                Long.toString(command.expectedPayableMinor()))
                // 询价报价（F4）只在有时才加进摘要：以前的订单的摘要不变，重放照常认得出。
                + (command.inquiryQuoteId() == null ? "" : "|quote:" + command.inquiryQuoteId());
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * 前缀 + 北京时间日期 + 8 位随机数字；撞号就换一个（同一天一亿个号，几乎不会撞）。
     * 商城订单前缀 MO，收费报名的订单 AO（E09，见 TradeSourceOrders）。
     */
    String newOrderNo(String prefix, Instant now) {
        String day = ORDER_DAY.format(now.atZone(ORDER_ZONE));
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = prefix + day + orderNoSuffix.get();
            // 买家锁只挡住同一个买家；不同买家同时抽到同一个号时，靠这把号码锁让后来的那个看到先插入的单。
            mapper.lockOrderNo(candidate);
            if (mapper.countOrderNo(candidate) == 0) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not pick an unused order number");
    }

    private static void requireMerchant(UUID merchantId) {
        if (merchantId == null) {
            throw new IllegalArgumentException("merchant is required");
        }
    }

    /** 去掉首尾空白后 1 到 max 个字符，不能有控制字符。 */
    private static String text(String value, int max, String what) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty() || trimmed.length() > max || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(what + " is invalid");
        }
        return trimmed;
    }

    private static void requireBuyer(UUID buyerId) {
        if (buyerId == null) {
            throw new IllegalArgumentException("buyer is required");
        }
    }

    /** 提交订单的内容（契约 SubmitOrderRequest）。inquiryQuoteId 是第三种来源：选定的询价报价（F4）。 */
    public record SubmitCommand(
            String cityCode,
            List<UUID> cartItemIds,
            UUID offerId,
            Integer quantity,
            String deliveryMode,
            UUID addressId,
            String buyerNote,
            Long expectedPayableMinor,
            Boolean agreementAccepted,
            UUID inquiryQuoteId
    ) {
        public SubmitCommand(String cityCode, List<UUID> cartItemIds, UUID offerId, Integer quantity,
                             String deliveryMode, UUID addressId, String buyerNote, Long expectedPayableMinor,
                             Boolean agreementAccepted) {
            this(cityCode, cartItemIds, offerId, quantity, deliveryMode, addressId, buyerNote, expectedPayableMinor,
                    agreementAccepted, null);
        }
    }
}
