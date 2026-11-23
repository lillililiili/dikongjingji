package com.lowaltitude.inspection.trade.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import com.lowaltitude.inspection.trade.application.TradeCheckoutPort.AddressSnapshot;
import com.lowaltitude.inspection.trade.application.TradeCheckoutPort.CheckoutQuote;
import com.lowaltitude.inspection.trade.application.TradeCheckoutPort.CheckoutRequest;
import com.lowaltitude.inspection.trade.application.TradeCheckoutPort.QuotedLine;
import com.lowaltitude.inspection.trade.application.TradeException.Error;
import com.lowaltitude.inspection.trade.application.TradeOrderService.SubmitCommand;
import com.lowaltitude.inspection.trade.application.TradeViews.MerchantAccountView;
import com.lowaltitude.inspection.trade.application.TradeViews.SubmitResult;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeDeliveryView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeMediaView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeOrderLineView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeOrderPageView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeOrderView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeRefundView;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 订单的规则测试。mapper 换成内存实现（按 TradeOrderMapper 的 SQL 条件和 V001 的约束写），报价换成假的商城，
 * 时钟可拨动，订单号的随机部分由测试给出。SQL 文本本身由 TradeOrderMapperContractTest 检查。
 */
class TradeOrderServiceTest {

    /** 北京时间 2026-11-19 00:30：订单号的日期按北京时间，不按 UTC。 */
    private static final Instant NOW = Instant.parse("2026-11-18T16:30:00Z");
    private static final UUID BUYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_BUYER = UUID.fromString("11111111-1111-1111-1111-222222222222");
    private static final UUID MERCHANT = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_MERCHANT = UUID.fromString("c1000000-0000-4000-8000-000000000002");
    private static final UUID OPERATOR = UUID.fromString("c1000000-0000-4000-8000-0000000000a1");
    private static final UUID KEY = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final UUID CART_A = UUID.fromString("c5000000-0000-4000-8000-000000000001");
    private static final UUID CART_B = UUID.fromString("c5000000-0000-4000-8000-000000000002");
    private static final UUID OFFER_A = UUID.fromString("c4000000-0000-4000-8000-000000000001");
    private static final UUID OFFER_B = UUID.fromString("c4000000-0000-4000-8000-000000000002");
    private static final UUID PRODUCT_A = UUID.fromString("c2000000-0000-4000-8000-000000000001");
    private static final UUID PRODUCT_B = UUID.fromString("c2000000-0000-4000-8000-000000000002");
    private static final UUID ADDRESS_ID = UUID.fromString("d1000000-0000-4000-8000-000000000001");
    private static final String SUB_MCHID = "1900000109";
    private static final String PICKUP_POINT = "东营区示例路 1 号门店";
    private static final AddressSnapshot ADDRESS =
            new AddressSnapshot("王女士", "13800138000", "山东省", "东营市", "东营区示例路 18 号");
    /** 两行商品 1000 元 + 2 × 200 元，快递运费 10 元。 */
    private static final long GOODS = 140_000;
    private static final long SHIPPING = 1_000;

    private final InMemoryMapper mapper = new InMemoryMapper();
    private final FakeCheckout checkout = new FakeCheckout();
    private final MutableClock clock = new MutableClock(NOW);
    private final Deque<String> suffixes = new ArrayDeque<>();
    private final TradeOrderService service = new TradeOrderService(mapper, checkout, clock, this::nextSuffix);

    {
        mapper.organizations.add(MERCHANT);
        mapper.accounts.put(MERCHANT, new TradeOrderMapper.MerchantAccountRow(MERCHANT, SUB_MCHID, true));
    }

    // ---- 提交 ----

    @Test
    void anOrderFromTheCartSnapshotsTheQuoteAndRemovesTheCartLines() {
        suffixes.add("00000042");

        SubmitResult result = service.submit(BUYER, KEY, fromCart(GOODS + SHIPPING));
        TradeOrderView order = result.order();

        assertThat(result.created()).isTrue();
        assertThat(order.orderNo()).isEqualTo("MO2026111900000042");
        assertThat(order.source()).isEqualTo("MALL");
        assertThat(order.status()).isEqualTo(TradeOrderStatus.PENDING_PAYMENT);
        assertThat(order.merchant()).isEqualTo(new TradeViews.TradeMerchantView(MERCHANT, "示例无人机专卖店", "LOCAL_STORE"));
        assertThat(order.amounts()).isEqualTo(new TradeViews.TradeAmountsView(GOODS, SHIPPING, GOODS + SHIPPING, 0, 0, "CNY"));
        assertThat(order.buyerNote()).isEqualTo("请周末送");
        assertThat(order.createdAt()).isEqualTo(NOW);
        assertThat(order.payBefore()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        assertThat(order.paidAt()).isNull();
        assertThat(order.closedAt()).isNull();
        assertThat(order.refunds()).isEmpty();
        assertThat(order.cancellable()).isTrue();
        assertThat(order.version()).isZero();
        // 行按报价的顺序；非 HTTPS 的封面图不给出去。
        assertThat(order.lines())
                .extracting(TradeOrderLineView::offerId, TradeOrderLineView::productName, TradeOrderLineView::coverImage,
                        TradeOrderLineView::unitAmountMinor, TradeOrderLineView::quantity, TradeOrderLineView::subtotalMinor)
                .containsExactly(
                        tuple(OFFER_A, "示例无人机", new TradeMediaView("https://img.example.com/a.png", "示例无人机"),
                                100_000L, 1, 100_000L),
                        tuple(OFFER_B, "备用电池", null, 20_000L, 2, 40_000L));
        assertThat(mapper.lines).extracting(line -> line.sortOrder).containsExactly(0, 10);
        // 收货地址做成快照，手机号打码后返回。
        assertThat(order.delivery()).isEqualTo(new TradeDeliveryView(
                "EXPRESS", "王女士", "138****8000", null, "山东省东营市 东营区示例路 18 号", null, null, null, null, null));
        InMemoryMapper.Order stored = mapper.only();
        assertThat(stored.buyerId).isEqualTo(BUYER);
        assertThat(stored.subMchid).isEqualTo(SUB_MCHID);
        assertThat(stored.cityCode).isEqualTo("370500");
        assertThat(stored.idempotencyKey).isEqualTo(KEY);
        assertThat(stored.requestHash).matches("^[0-9a-f]{64}$");
        assertThat(mapper.deliveries.get(stored.id).phone).isEqualTo("13800138000");
        assertThat(mapper.deliveries.get(stored.id).pickupPoint).isNull();
        assertThat(mapper.transitions).containsExactly(
                new Transition(stored.id, null, "PENDING_PAYMENT", "SUBMIT", BUYER, NOW));
        // 先排队再查幂等键；报价按规范化后的内容要；下单的购物车行删掉。
        assertThat(mapper.calls.subList(0, 2)).containsExactly("lockBuyer:" + BUYER, "findByKey");
        assertThat(checkout.requests).containsExactly(
                new CheckoutRequest("370500", List.of(CART_A, CART_B), null, null, "EXPRESS", ADDRESS_ID));
        assertThat(checkout.removed).containsExactly(List.of(CART_A, CART_B));
    }

    @Test
    void buyingNowAsksForTheOfferAndLeavesTheCartAlone() {
        SubmitCommand buyNow = new SubmitCommand(
                "370500", null, OFFER_A, 2, "EXPRESS", null, null, GOODS + SHIPPING, true);

        TradeOrderView order = service.submit(BUYER, KEY, buyNow).order();

        assertThat(order.buyerNote()).isNull();
        assertThat(checkout.requests).containsExactly(
                new CheckoutRequest("370500", List.of(), OFFER_A, 2, "EXPRESS", null));
        assertThat(checkout.removed).isEmpty();
    }

    @Test
    void aPickupOrderKeepsThePickupPointAndNoAddress() {
        SubmitCommand pickup = new SubmitCommand(
                "370500", List.of(CART_A), null, null, "PICKUP", ADDRESS_ID, "  ", GOODS, true);

        TradeOrderView order = service.submit(BUYER, KEY, pickup).order();

        // 自提时不用地址：不传给商城，也不存。
        assertThat(checkout.requests.getFirst().addressId()).isNull();
        assertThat(order.delivery()).isEqualTo(new TradeDeliveryView(
                "PICKUP", null, null, null, null, PICKUP_POINT, null, null, null, null));
        assertThat(order.amounts().shippingMinor()).isZero();
        assertThat(order.buyerNote()).as("空白备注存成 null").isNull();
        InMemoryMapper.Delivery delivery = mapper.deliveries.get(order.orderId());
        assertThat(delivery.recipientName).isNull();
        assertThat(delivery.phone).isNull();
        assertThat(delivery.cityName).isNull();
        assertThat(delivery.addressDetail).isNull();
    }

    // ---- 幂等 ----

    @Test
    void theSameKeyWithTheSameContentReturnsTheFirstOrderWithoutQuotingAgain() {
        TradeOrderView first = service.submit(BUYER, KEY, fromCart(GOODS + SHIPPING)).order();
        // 内容规范化后相同：城市前后空格、行的顺序和重复、备注前后空格都不算不同。
        SubmitCommand sameContent = new SubmitCommand(
                " 370500 ", List.of(CART_B, CART_A, CART_B), null, null, "EXPRESS", ADDRESS_ID, " 请周末送 ",
                GOODS + SHIPPING, true);

        SubmitResult replay = service.submit(BUYER, KEY, sameContent);

        assertThat(replay.created()).isFalse();
        assertThat(replay.order()).isEqualTo(first);
        assertThat(mapper.orders).hasSize(1);
        assertThat(checkout.requests).hasSize(1);
        assertThat(checkout.removed).hasSize(1);
        assertThat(mapper.transitions).hasSize(1);
    }

    @Test
    void aReplayAfterTheOrderClosedReturnsTheClosedOrder() {
        TradeOrderView first = service.submit(BUYER, KEY, fromCart(GOODS + SHIPPING)).order();
        service.cancel(BUYER, first.orderId(), 0);

        SubmitResult replay = service.submit(BUYER, KEY, fromCart(GOODS + SHIPPING));

        assertThat(replay.created()).isFalse();
        assertThat(replay.order().orderId()).isEqualTo(first.orderId());
        assertThat(replay.order().status()).isEqualTo(TradeOrderStatus.CLOSED);
    }

    @Test
    void theSameKeyWithDifferentContentIsRefusedAndNothingIsWritten() {
        service.submit(BUYER, KEY, fromCart(GOODS + SHIPPING));

        for (SubmitCommand changed : List.of(
                fromCart(GOODS + SHIPPING + 1),
                new SubmitCommand("370500", List.of(CART_A), null, null, "EXPRESS", ADDRESS_ID, "请周末送",
                        GOODS + SHIPPING, true),
                new SubmitCommand("370500", List.of(CART_A, CART_B), null, null, "PICKUP", null, "请周末送",
                        GOODS + SHIPPING, true),
                new SubmitCommand("370500", List.of(CART_A, CART_B), null, null, "EXPRESS", null, "请周末送",
                        GOODS + SHIPPING, true),
                new SubmitCommand("370100", List.of(CART_A, CART_B), null, null, "EXPRESS", ADDRESS_ID, "请周末送",
                        GOODS + SHIPPING, true),
                new SubmitCommand("370500", List.of(CART_A, CART_B), null, null, "EXPRESS", ADDRESS_ID, "别的备注",
                        GOODS + SHIPPING, true))) {
            assertRefused(() -> service.submit(BUYER, KEY, changed), Error.IDEMPOTENCY_CONFLICT);
        }
        assertThat(mapper.orders).hasSize(1);
        assertThat(checkout.requests).hasSize(1);
    }

    @Test
    void anIdempotencyKeyBelongsToOneBuyer() {
        TradeOrderView mine = service.submit(BUYER, KEY, fromCart(GOODS + SHIPPING)).order();

        SubmitResult theirs = service.submit(OTHER_BUYER, KEY, fromCart(GOODS + SHIPPING));

        assertThat(theirs.created()).isTrue();
        assertThat(theirs.order().orderId()).isNotEqualTo(mine.orderId());
        assertThat(mapper.orders).hasSize(2);
    }

    // ---- 拒绝的顺序 ----

    @Test
    void aMerchantWithoutAnActiveAccountIsRefusedBeforeAnythingElse() {
        // 同时配送方式不可用、缺地址、金额也变了：仍然先报商家收不了款。
        checkout.respond = request -> quoteOf(null, null, GOODS);

        mapper.accounts.remove(MERCHANT);
        assertRefused(() -> service.submit(BUYER, KEY, fromCart(1)), Error.MERCHANT_PAYMENT_UNAVAILABLE);

        mapper.accounts.put(MERCHANT, new TradeOrderMapper.MerchantAccountRow(MERCHANT, SUB_MCHID, false));
        assertRefused(() -> service.submit(BUYER, KEY, fromCart(1)), Error.MERCHANT_PAYMENT_UNAVAILABLE);

        assertNothingWritten();
    }

    @Test
    void aDeliveryModeTheOrderCannotUseIsRefusedBeforeTheAddressAndTheAmount() {
        // 要快递，商城说只能自提（或者一种都没有）。
        checkout.respond = request -> quoteOf("PICKUP", null, GOODS);
        assertRefused(() -> service.submit(BUYER, KEY, fromCart(1)), Error.DELIVERY_UNAVAILABLE);

        checkout.respond = request -> quoteOf(null, null, GOODS);
        assertRefused(() -> service.submit(BUYER, KEY, fromCart(1)), Error.DELIVERY_UNAVAILABLE);

        assertNothingWritten();
    }

    @Test
    void anExpressOrderWithoutAnAddressIsRefusedBeforeTheAmount() {
        checkout.respond = request -> quoteOf("EXPRESS", null, GOODS + SHIPPING);

        assertRefused(() -> service.submit(BUYER, KEY, fromCart(1)), Error.ADDRESS_REQUIRED);
        assertRefused(() -> service.submit(BUYER, KEY, fromCart(GOODS + SHIPPING)), Error.ADDRESS_REQUIRED);

        assertNothingWritten();
    }

    @Test
    void aChangedAmountIsRefusedAndTheSameKeyWorksWithTheNewAmount() {
        assertRefused(() -> service.submit(BUYER, KEY, fromCart(GOODS)), Error.AMOUNT_CHANGED);
        assertNothingWritten();

        // 页面重新预览后用新金额再提交：第一次没有建单，同一个键可以用。
        SubmitResult retried = service.submit(BUYER, KEY, fromCart(GOODS + SHIPPING));

        assertThat(retried.created()).isTrue();
        assertThat(mapper.orders).hasSize(1);
    }

    @Test
    void aQuoteFailureFromTheMallLeavesNothingBehind() {
        checkout.respond = request -> {
            throw new IllegalStateException("item unavailable");
        };

        assertThatThrownBy(() -> service.submit(BUYER, KEY, fromCart(GOODS + SHIPPING)))
                .isInstanceOf(IllegalStateException.class);
        assertNothingWritten();
    }

    // 0 元（或没有商品）的报价：按请求不合法拒绝（→ 400），什么都不写，购物车也不动。
    @Test
    void aQuoteWithAnUnpricedLineIsRefusedAndNothingIsWritten() {
        QuotedLine priced = new QuotedLine(PRODUCT_A, OFFER_A, "示例无人机", "标准版", null, 100_000, 1, 100_000);
        List<CheckoutQuote> unpriced = List.of(
                quoteWith(List.of(priced, new QuotedLine(PRODUCT_B, OFFER_B, "赠品", "标准版", null, 0, 1, 0)), 100_000),
                quoteWith(List.of(new QuotedLine(PRODUCT_B, OFFER_B, "备用电池", "5000mAh", null, 20_000, 1, 0)), 20_000),
                quoteWith(List.of(new QuotedLine(PRODUCT_B, OFFER_B, "备用电池", "5000mAh", null, -1, 1, -1)), 0),
                quoteWith(List.of(priced), 0),
                quoteWith(List.of(), 100_000));

        for (CheckoutQuote quote : unpriced) {
            checkout.respond = request -> quote;
            assertThatThrownBy(() -> service.submit(BUYER, KEY, fromCart(quote.payableMinor())))
                    .as(quote.lines().toString())
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertNothingWritten();
        // 报价之后、写任何东西之前就停下。
        assertThat(mapper.calls).containsOnly("lockBuyer:" + BUYER, "findByKey");
    }

    // ---- 校验 ----

    @Test
    void invalidRequestsAreRefusedBeforeTheMallIsAsked() {
        List<SubmitCommand> invalid = new ArrayList<>(Arrays.asList(
                null,
                command(null, List.of(CART_A), null, null, "EXPRESS", 1L, true, null),
                command("dy01", List.of(CART_A), null, null, "EXPRESS", 1L, true, null),
                command("370", List.of(CART_A), null, null, "EXPRESS", 1L, true, null),
                command("A".repeat(17), List.of(CART_A), null, null, "EXPRESS", 1L, true, null),
                // 购物车行和立即购买二选一。
                command("370500", List.of(CART_A), OFFER_A, 1, "EXPRESS", 1L, true, null),
                command("370500", List.of(), null, null, "EXPRESS", 1L, true, null),
                command("370500", null, null, null, "EXPRESS", 1L, true, null),
                command("370500", Arrays.asList(CART_A, null), null, null, "EXPRESS", 1L, true, null),
                command("370500", null, OFFER_A, null, "EXPRESS", 1L, true, null),
                command("370500", null, OFFER_A, 0, "EXPRESS", 1L, true, null),
                command("370500", null, OFFER_A, 100, "EXPRESS", 1L, true, null),
                command("370500", List.of(CART_A), null, null, null, 1L, true, null),
                command("370500", List.of(CART_A), null, null, "express", 1L, true, null),
                command("370500", List.of(CART_A), null, null, "SAME_DAY", 1L, true, null),
                command("370500", List.of(CART_A), null, null, "EXPRESS", null, true, null),
                command("370500", List.of(CART_A), null, null, "EXPRESS", -1L, true, null),
                command("370500", List.of(CART_A), null, null, "EXPRESS", 0L, true, null),
                command("370500", List.of(CART_A), null, null, "EXPRESS", 1L, null, null),
                command("370500", List.of(CART_A), null, null, "EXPRESS", 1L, false, null),
                command("370500", List.of(CART_A), null, null, "EXPRESS", 1L, true, "注".repeat(51)),
                command("370500", List.of(CART_A), null, null, "EXPRESS", 1L, true, "请\n周末送")));

        for (SubmitCommand command : invalid) {
            assertThatThrownBy(() -> service.submit(BUYER, KEY, command))
                    .as(String.valueOf(command))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.submit(BUYER, null, fromCart(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.submit(null, KEY, fromCart(1)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(checkout.requests).isEmpty();
        assertThat(mapper.calls).isEmpty();
        assertNothingWritten();
    }

    @Test
    void theBoundaryValuesAreAccepted() {
        long payable = GOODS + SHIPPING;

        TradeOrderView longNote = service.submit(BUYER, UUID.randomUUID(), command(
                "370500", List.of(CART_A), null, null, "EXPRESS", payable, true, " " + "注".repeat(50) + " ")).order();
        service.submit(BUYER, UUID.randomUUID(), command("ABCD", null, OFFER_A, 1, "EXPRESS", payable, true, null));
        service.submit(BUYER, UUID.randomUUID(),
                command("A".repeat(16), null, OFFER_A, 99, "EXPRESS", payable, true, null));

        assertThat(longNote.buyerNote()).isEqualTo("注".repeat(50));
        assertThat(mapper.orders).hasSize(3);
    }

    // ---- 按询价报价下单（F4） ----

    private static final UUID INQUIRY_QUOTE = UUID.fromString("e1000000-0000-4000-8000-000000000001");

    @Test
    void anInquiryQuoteOrderAsksTheMallWithTheQuoteAndTellsItTheNewOrder() {
        // 数量是询价单的：请求里带了也不往商城传。
        TradeOrderView order = service.submit(BUYER, KEY, fromQuote(7)).order();

        CheckoutRequest asked = new CheckoutRequest("370500", List.of(), null, null, "EXPRESS", ADDRESS_ID, INQUIRY_QUOTE);
        assertThat(checkout.requests).containsExactly(asked);
        assertThat(checkout.placed).containsExactly(new Placed(BUYER, asked, order.orderId()));
        assertThat(checkout.removed).isEmpty();
        assertThat(order.source()).isEqualTo("MALL");
        assertThat(order.status()).isEqualTo(TradeOrderStatus.PENDING_PAYMENT);
        // 同一个键重放不再通知商城；换一份报价是另一份内容。
        assertThat(service.submit(BUYER, KEY, fromQuote(null)).created()).isFalse();
        assertThat(checkout.placed).hasSize(1);
        SubmitCommand otherQuote = new SubmitCommand("370500", null, null, null, "EXPRESS", ADDRESS_ID, null,
                GOODS + SHIPPING, true, UUID.randomUUID());
        assertRefused(() -> service.submit(BUYER, KEY, otherQuote), Error.IDEMPOTENCY_CONFLICT);
        // 不带报价的请求，内容摘要和以前一样（老的幂等键照旧能重放）。
        UUID cartKey = UUID.randomUUID();
        service.submit(BUYER, cartKey, fromCart(GOODS + SHIPPING));
        assertThat(service.submit(BUYER, cartKey, new SubmitCommand("370500", List.of(CART_A, CART_B), null, null,
                "EXPRESS", ADDRESS_ID, "请周末送", GOODS + SHIPPING, true, null)).created()).isFalse();
    }

    @Test
    void aQuoteWithAnOrderThatIsStillOpenCannotBeOrderedAgain() {
        UUID first = service.submit(BUYER, KEY, fromQuote(null)).order().orderId();
        checkout.respond = request -> withPrevious(quoteOf("EXPRESS", ADDRESS, GOODS + SHIPPING), first);
        int written = mapper.orders.size();

        for (String status : List.of("PENDING_PAYMENT", "PAID", "SHIPPED", "COMPLETED", "REFUNDING", "REFUNDED",
                "REFUND_FAILED")) {
            mapper.orders.getFirst().status = status;
            assertRefused(() -> service.submit(BUYER, UUID.randomUUID(), fromQuote(null)), Error.QUOTE_ALREADY_ORDERED);
        }
        assertThat(mapper.orders).hasSize(written);
        assertThat(checkout.placed).hasSize(1);

        // 上一张没付款关闭了：可以再下一张。
        mapper.orders.getFirst().status = "CLOSED";
        UUID second = service.submit(BUYER, UUID.randomUUID(), fromQuote(null)).order().orderId();
        assertThat(second).isNotEqualTo(first);
        assertThat(checkout.placed).extracting(Placed::orderId).containsExactly(first, second);
    }

    @Test
    void anInquiryQuoteIsTheOnlySourceOfItsOrder() {
        for (SubmitCommand mixed : List.of(
                new SubmitCommand("370500", List.of(CART_A), null, null, "EXPRESS", ADDRESS_ID, null, 1L, true,
                        INQUIRY_QUOTE),
                new SubmitCommand("370500", null, OFFER_A, 1, "EXPRESS", ADDRESS_ID, null, 1L, true, INQUIRY_QUOTE))) {
            assertThatThrownBy(() -> service.submit(BUYER, KEY, mixed)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(checkout.requests).isEmpty();
        assertNothingWritten();
    }

    private static SubmitCommand fromQuote(Integer quantity) {
        return new SubmitCommand("370500", null, null, quantity, "EXPRESS", ADDRESS_ID, null, GOODS + SHIPPING, true,
                INQUIRY_QUOTE);
    }

    private static CheckoutQuote withPrevious(CheckoutQuote quote, UUID previousOrderId) {
        return new CheckoutQuote(quote.merchantId(), quote.merchantName(), quote.merchantKind(), quote.lines(),
                quote.deliveryMode(), quote.pickupPoint(), quote.address(), quote.goodsMinor(), quote.shippingMinor(),
                quote.payableMinor(), previousOrderId);
    }

    private record Placed(UUID accountId, CheckoutRequest request, UUID orderId) {
    }

    // ---- 订单号 ----

    @Test
    void anOrderNumberThatIsAlreadyUsedIsPickedAgain() {
        mapper.takenOrderNos.add("MO2026111900000001");
        mapper.takenOrderNos.add("MO2026111900000002");
        suffixes.addAll(List.of("00000001", "00000002", "00000003"));

        TradeOrderView order = service.submit(BUYER, KEY, fromCart(GOODS + SHIPPING)).order();

        assertThat(order.orderNo()).isEqualTo("MO2026111900000003");
        assertThat(mapper.orderNoChecks).containsExactly(
                "MO2026111900000001", "MO2026111900000002", "MO2026111900000003");
        // 每个候选号码都先加号码锁再查，跨买家同时抽到同一个号时后来的会看到先插入的单。
        assertThat(mapper.orderNoLocks).containsExactlyElementsOf(mapper.orderNoChecks);
    }

    @Test
    void fiveCollisionsInARowGiveUpWithoutWritingAnything() {
        for (int i = 1; i <= 5; i++) {
            mapper.takenOrderNos.add("MO202611190000000" + i);
            suffixes.add("0000000" + i);
        }
        suffixes.add("00000006");

        assertThatThrownBy(() -> service.submit(BUYER, KEY, fromCart(GOODS + SHIPPING)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(mapper.orderNoChecks).hasSize(5);
        assertNothingWritten();
    }

    @Test
    void theOrderDateIsTheBeijingDate() {
        clock.now = Instant.parse("2026-11-18T15:59:59Z");
        assertThat(service.submit(BUYER, UUID.randomUUID(), fromCart(GOODS + SHIPPING)).order().orderNo())
                .startsWith("MO20261118");

        clock.now = Instant.parse("2026-11-18T16:00:00Z");
        assertThat(service.submit(BUYER, UUID.randomUUID(), fromCart(GOODS + SHIPPING)).order().orderNo())
                .startsWith("MO20261119")
                .hasSize(18);
    }

    // ---- 读 ----

    @Test
    void anotherBuyersOrderAndAMissingOrderAreNotFound() {
        TradeOrderView mine = service.submit(BUYER, KEY, fromCart(GOODS + SHIPPING)).order();

        assertRefused(() -> service.read(OTHER_BUYER, mine.orderId()), Error.NOT_FOUND);
        assertRefused(() -> service.read(BUYER, UUID.randomUUID()), Error.NOT_FOUND);
        assertThatThrownBy(() -> service.read(BUYER, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.read(null, mine.orderId())).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.read(BUYER, mine.orderId())).isEqualTo(mine);
    }

    @ParameterizedTest
    @EnumSource(value = TradeOrderStatus.class, names = "PENDING_PAYMENT", mode = EnumSource.Mode.EXCLUDE)
    void onlyAPendingOrderHasADeadline(TradeOrderStatus status) {
        UUID orderId = mapper.seed(BUYER, status, NOW);

        TradeOrderView order = service.read(BUYER, orderId);

        assertThat(order.status()).isEqualTo(status);
        assertThat(order.payBefore()).isNull();
        assertThat(mapper.find(orderId).payBefore).as("截止时间还在库里，只是不给出去").isNotNull();
    }

    // 待付款直接关；已付款未发货（未自提）的取消即全额退款（E05）。别的状态都不能取消。
    @ParameterizedTest
    @EnumSource(TradeOrderStatus.class)
    void onlyAPendingOrAPaidOrderCanBeCancelled(TradeOrderStatus status) {
        UUID orderId = mapper.seed(BUYER, status, NOW);

        boolean expected = status == TradeOrderStatus.PENDING_PAYMENT || status == TradeOrderStatus.PAID;
        assertThat(service.read(BUYER, orderId).cancellable()).isEqualTo(expected);
        assertThat(service.readForMerchant(MERCHANT, orderId).cancellable()).isEqualTo(expected);
    }

    @Test
    void thePickupCodeShippingAndRefundsAreShownToTheBuyer() {
        UUID orderId = mapper.seed(BUYER, TradeOrderStatus.PAID, NOW);
        InMemoryMapper.Delivery delivery = mapper.deliveries.get(orderId);
        delivery.mode = "PICKUP";
        delivery.recipientName = null;
        delivery.phone = null;
        delivery.provinceName = null;
        delivery.cityName = null;
        delivery.addressDetail = null;
        delivery.pickupPoint = PICKUP_POINT;
        delivery.pickupCode = "123456";
        UUID refundId = UUID.randomUUID();
        mapper.refunds.add(new TradeOrderMapper.RefundRow(
                refundId, orderId, 5_000, "少发一件", "SUCCEEDED", NOW.plusSeconds(60), NOW.plusSeconds(120)));
        mapper.find(orderId).currency = "CNY";

        TradeOrderView order = service.read(BUYER, orderId);

        assertThat(order.delivery().pickupCode()).isEqualTo("123456");
        assertThat(order.delivery().pickupPoint()).isEqualTo(PICKUP_POINT);
        assertThat(order.delivery().addressLine()).isNull();
        assertThat(order.delivery().phoneMasked()).isNull();
        assertThat(order.refunds()).containsExactly(new TradeRefundView(
                refundId, 5_000, "少发一件", "SUCCEEDED", NOW.plusSeconds(60), NOW.plusSeconds(120)));
        assertThat(order.paidAt()).isEqualTo(NOW);
    }

    @Test
    void phoneNumbersAreAlwaysMaskedAndTheRegionIsNotRepeated() {
        UUID orderId = mapper.seed(BUYER, TradeOrderStatus.SHIPPED, NOW);
        InMemoryMapper.Delivery delivery = mapper.deliveries.get(orderId);

        delivery.phone = " 13912345678 ";
        assertThat(service.read(BUYER, orderId).delivery().phoneMasked()).isEqualTo("139****5678");
        // 不是 11 位数字的号码一位都不露。
        delivery.phone = "+8613912345678";
        assertThat(service.read(BUYER, orderId).delivery().phoneMasked()).isEqualTo("****");
        delivery.phone = "1391234567";
        assertThat(service.read(BUYER, orderId).delivery().phoneMasked()).isEqualTo("****");

        // 直辖市的省和市同名，只写一次；省份缺了也不出现 "null"。
        delivery.provinceName = "北京市";
        delivery.cityName = "北京市";
        delivery.addressDetail = "朝阳区示例路 1 号";
        assertThat(service.read(BUYER, orderId).delivery().addressLine()).isEqualTo("北京市 朝阳区示例路 1 号");
        delivery.provinceName = null;
        assertThat(service.read(BUYER, orderId).delivery().addressLine()).isEqualTo("北京市 朝阳区示例路 1 号");

        delivery.carrierName = "顺丰速运";
        delivery.trackingNo = "SF1234567890";
        TradeDeliveryView shipped = service.read(BUYER, orderId).delivery();
        assertThat(shipped.carrierName()).isEqualTo("顺丰速运");
        assertThat(shipped.trackingNo()).isEqualTo("SF1234567890");
        assertThat(shipped.shippedAt()).isEqualTo(NOW);
    }

    @Test
    void onlyHttpsCoversWithAHostAreShown() {
        checkout.respond = request -> new CheckoutQuote(
                MERCHANT, "示例无人机专卖店", "LOCAL_STORE",
                List.of(
                        line(OFFER_A, " https://img.example.com/a.png ", 1),
                        line(OFFER_A, "http://img.example.com/a.png", 1),
                        line(OFFER_A, "/static/a.png", 1),
                        line(OFFER_A, "https:///no-host.png", 1),
                        line(OFFER_A, "https://bad host/a.png", 1),
                        line(OFFER_A, "   ", 1),
                        line(OFFER_A, null, 1)),
                "EXPRESS", null, ADDRESS, 700_000, 0, 700_000);

        TradeOrderView order = service.submit(BUYER, KEY, fromCart(700_000)).order();

        assertThat(order.lines()).extracting(TradeOrderLineView::coverImage).containsExactly(
                new TradeMediaView("https://img.example.com/a.png", "示例无人机"), null, null, null, null, null, null);
    }

    @Test
    void anOrderWithoutADeliveryRowStillHasItsMode() {
        UUID orderId = mapper.seed(BUYER, TradeOrderStatus.COMPLETED, NOW);
        mapper.deliveries.remove(orderId);
        mapper.find(orderId).currency = null;

        TradeOrderView order = service.read(BUYER, orderId);

        assertThat(order.delivery()).isEqualTo(new TradeDeliveryView(
                "EXPRESS", null, null, null, null, null, null, null, null, NOW));
        assertThat(order.amounts().currency()).isEqualTo("CNY");
    }

    // ---- 我的订单 ----

    @Test
    void eachTabListsItsStatusesNewestFirstAndOnlyTheBuyersOwn() {
        Map<TradeOrderStatus, UUID> seeded = new LinkedHashMap<>();
        int minute = 0;
        for (TradeOrderStatus status : TradeOrderStatus.values()) {
            seeded.put(status, mapper.seed(BUYER, status, NOW.plusSeconds(60L * minute++)));
            mapper.seed(OTHER_BUYER, status, NOW.plusSeconds(60L * minute++));
        }

        assertThat(ids(service.list(BUYER, null, 1, 50))).containsExactlyElementsOf(
                seeded.values().stream().toList().reversed());
        assertThat(ids(service.list(BUYER, "ALL", 1, 50))).hasSize(8);
        assertThat(ids(service.list(BUYER, " ", 1, 50))).hasSize(8);
        assertThat(ids(service.list(BUYER, "TO_PAY", 1, 50)))
                .containsExactly(seeded.get(TradeOrderStatus.PENDING_PAYMENT));
        assertThat(ids(service.list(BUYER, " TO_SHIP ", 1, 50))).containsExactly(seeded.get(TradeOrderStatus.PAID));
        assertThat(ids(service.list(BUYER, "TO_RECEIVE", 1, 50))).containsExactly(seeded.get(TradeOrderStatus.SHIPPED));
        assertThat(ids(service.list(BUYER, "DONE", 1, 50))).containsExactly(seeded.get(TradeOrderStatus.COMPLETED));
        assertThat(ids(service.list(BUYER, "REFUND", 1, 50))).containsExactly(
                seeded.get(TradeOrderStatus.REFUND_FAILED),
                seeded.get(TradeOrderStatus.REFUNDED),
                seeded.get(TradeOrderStatus.REFUNDING));
        // 已关闭的只在“全部”里。
        assertThat(TradeOrderStatus.ofTab("ALL")).isEmpty();
        assertThat(List.of("TO_PAY", "TO_SHIP", "TO_RECEIVE", "DONE", "REFUND"))
                .allSatisfy(tab -> assertThat(TradeOrderStatus.ofTab(tab)).doesNotContain("CLOSED"));
        assertThat(service.list(UUID.randomUUID(), null, 1, 20).items()).isEmpty();
    }

    @Test
    void pagesCountTheTotalAndSayWhetherMoreFollow() {
        for (int i = 0; i < 5; i++) {
            mapper.seed(BUYER, TradeOrderStatus.COMPLETED, NOW.plusSeconds(i));
        }

        TradeOrderPageView first = service.list(BUYER, "DONE", 1, 2);
        TradeOrderPageView last = service.list(BUYER, "DONE", 3, 2);
        TradeOrderPageView beyond = service.list(BUYER, "DONE", 4, 2);

        assertThat(first.page()).isEqualTo(1);
        assertThat(first.size()).isEqualTo(2);
        assertThat(first.total()).isEqualTo(5);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.items()).hasSize(2);
        assertThat(last.items()).hasSize(1);
        assertThat(last.hasMore()).isFalse();
        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.hasMore()).isFalse();
        assertThat(service.list(BUYER, "DONE", 1, 5).hasMore()).isFalse();
    }

    @Test
    void anUnknownTabOrAnInvalidPageIsRefused() {
        assertThatThrownBy(() -> service.list(BUYER, "CLOSED", 1, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(BUYER, "to_pay", 1, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(BUYER, null, 0, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(BUYER, null, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(BUYER, null, 1, 51)).isInstanceOf(IllegalArgumentException.class);
        // 页码有上限：超大页码在算偏移量时会溢出，要当作参数错误而不是服务器错误。
        assertThatThrownBy(() -> service.list(BUYER, null, 10_001, 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(BUYER, null, 50_000_000, 50)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.list(BUYER, null, 10_000, 50).items()).isEmpty();
        assertThatThrownBy(() -> service.list(null, null, 1, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.list(BUYER, null, 1, 50).items()).isEmpty();
    }

    // ---- 取消 ----

    @Test
    void cancellingAPendingOrderClosesItAndRecordsWhoDidIt() {
        TradeOrderView order = service.submit(BUYER, KEY, fromCart(GOODS + SHIPPING)).order();
        clock.now = NOW.plusSeconds(300);

        TradeOrderView cancelled = service.cancel(BUYER, order.orderId(), 0);

        assertThat(cancelled.status()).isEqualTo(TradeOrderStatus.CLOSED);
        assertThat(cancelled.closedAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(cancelled.version()).isEqualTo(1);
        assertThat(cancelled.cancellable()).isFalse();
        assertThat(cancelled.payBefore()).isNull();
        assertThat(mapper.transitions).last().isEqualTo(new Transition(
                order.orderId(), "PENDING_PAYMENT", "CLOSED", "BUYER_CANCEL", BUYER, NOW.plusSeconds(300)));
        assertThat(mapper.calls).contains("lockBuyerOrder");
    }

    @Test
    void cancellingAClosedOrderAgainChangesNothing() {
        TradeOrderView order = service.submit(BUYER, KEY, fromCart(GOODS + SHIPPING)).order();
        TradeOrderView cancelled = service.cancel(BUYER, order.orderId(), 0);
        int transitions = mapper.transitions.size();

        // 版本对不上也原样返回：重复点击、两台设备各点一次都不报错。
        assertThat(service.cancel(BUYER, order.orderId(), 0)).isEqualTo(cancelled);
        assertThat(service.cancel(BUYER, order.orderId(), 7)).isEqualTo(cancelled);
        assertThat(mapper.transitions).hasSize(transitions);
    }

    @Test
    void aStaleVersionIsAConflictAndTheOrderStaysPending() {
        TradeOrderView order = service.submit(BUYER, KEY, fromCart(GOODS + SHIPPING)).order();

        assertRefused(() -> service.cancel(BUYER, order.orderId(), 1), Error.VERSION_CONFLICT);

        assertThat(service.read(BUYER, order.orderId()).status()).isEqualTo(TradeOrderStatus.PENDING_PAYMENT);
        assertThat(mapper.transitions).hasSize(1);
    }

    // 已发货、已完成、退款异常的单不能取消（售后不在一期）。
    @ParameterizedTest
    @EnumSource(value = TradeOrderStatus.class, names = {"SHIPPED", "COMPLETED", "REFUND_FAILED"})
    void anOrderPastShippingCannotBeCancelled(TradeOrderStatus status) {
        UUID orderId = mapper.seed(BUYER, status, NOW);

        assertRefused(() -> service.cancel(BUYER, orderId, 1), Error.ORDER_STATE_INVALID);
        // 状态不对先于版本不对。
        assertRefused(() -> service.cancel(BUYER, orderId, 9), Error.ORDER_STATE_INVALID);
        assertThat(mapper.find(orderId).status).isEqualTo(status.name());
        assertThat(mapper.transitions).isEmpty();
    }

    // 已付款的单取消要退款：抛 REFUND_REQUIRED 交给退款服务，这里什么都不写、版本也不看（退款服务自己核对）。
    @Test
    void aPaidOrderIsHandedToTheRefundServiceWithoutWritingAnything() {
        UUID orderId = mapper.seed(BUYER, TradeOrderStatus.PAID, NOW);
        mapper.find(orderId).version = 1;

        assertRefused(() -> service.cancel(BUYER, orderId, 1), Error.REFUND_REQUIRED);
        assertRefused(() -> service.cancel(BUYER, orderId, 9), Error.REFUND_REQUIRED);

        assertThat(mapper.find(orderId).status).isEqualTo("PAID");
        assertThat(mapper.find(orderId).version).isEqualTo(1);
        assertThat(mapper.transitions).isEmpty();
        assertThat(mapper.openPaymentChecks).isEmpty();
    }

    // 已在退款中、已退款的单再取消一次没有变化（重复点击、退款服务接手后页面又点了一次）。
    @ParameterizedTest
    @EnumSource(value = TradeOrderStatus.class, names = {"REFUNDING", "REFUNDED"})
    void cancellingAnOrderThatIsBeingOrWasRefundedReturnsItUnchanged(TradeOrderStatus status) {
        UUID orderId = mapper.seed(BUYER, status, NOW);
        TradeOrderView before = service.read(BUYER, orderId);

        assertThat(service.cancel(BUYER, orderId, 0)).isEqualTo(before);
        assertThat(service.cancel(BUYER, orderId, 9)).isEqualTo(before);

        assertThat(mapper.find(orderId).status).isEqualTo(status.name());
        assertThat(mapper.transitions).isEmpty();
    }

    // 发起过支付的单不能在这里直接关：买家可能正在付，交给支付服务先查单。拒绝时什么都不写。
    @Test
    void anOrderWithAnOpenPaymentIsHandedToThePaymentServiceWithoutWritingAnything() {
        TradeOrderView order = service.submit(BUYER, KEY, fromCart(GOODS + SHIPPING)).order();
        mapper.openPayments.add(order.orderId());
        int calls = mapper.calls.size();

        assertRefused(() -> service.cancel(BUYER, order.orderId(), 0), Error.PAYMENT_IN_PROGRESS);

        assertThat(mapper.find(order.orderId()).status).isEqualTo("PENDING_PAYMENT");
        assertThat(mapper.find(order.orderId()).version).isZero();
        assertThat(mapper.find(order.orderId()).closedAt).isNull();
        assertThat(mapper.transitions).hasSize(1);
        assertThat(mapper.openPaymentChecks).containsExactly(order.orderId());
        assertThat(mapper.calls.subList(calls, mapper.calls.size())).containsExactly("lockBuyerOrder");

        // 版本不对先于“有进行中支付单”：页面上的单已经旧了，先让买家刷新。
        assertRefused(() -> service.cancel(BUYER, order.orderId(), 3), Error.VERSION_CONFLICT);
        assertThat(mapper.openPaymentChecks).hasSize(1);

        // 支付单关掉以后照常取消。
        mapper.openPayments.remove(order.orderId());
        assertThat(service.cancel(BUYER, order.orderId(), 0).status()).isEqualTo(TradeOrderStatus.CLOSED);
    }

    @Test
    void aClosedOrderIsReturnedEvenIfAPaymentLooksOpen() {
        TradeOrderView order = service.submit(BUYER, KEY, fromCart(GOODS + SHIPPING)).order();
        TradeOrderView cancelled = service.cancel(BUYER, order.orderId(), 0);
        mapper.openPayments.add(order.orderId());

        assertThat(service.cancel(BUYER, order.orderId(), 0)).isEqualTo(cancelled);
        assertThat(mapper.openPaymentChecks).hasSize(1);
    }

    @Test
    void anotherBuyersOrderCannotBeCancelled() {
        TradeOrderView theirs = service.submit(OTHER_BUYER, KEY, fromCart(GOODS + SHIPPING)).order();

        assertRefused(() -> service.cancel(BUYER, theirs.orderId(), 0), Error.NOT_FOUND);
        assertRefused(() -> service.cancel(BUYER, UUID.randomUUID(), 0), Error.NOT_FOUND);
        assertThatThrownBy(() -> service.cancel(BUYER, null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.cancel(null, theirs.orderId(), 0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(mapper.find(theirs.orderId()).status).isEqualTo("PENDING_PAYMENT");
        assertThat(mapper.find(theirs.orderId()).version).isZero();
    }

    // ---- 超时关闭 ----

    @Test
    void ordersPastTheirDeadlineAreClosedAndTheRestAreLeftAlone() {
        TradeOrderView early = service.submit(BUYER, UUID.randomUUID(), fromCart(GOODS + SHIPPING)).order();
        clock.now = NOW.plusSeconds(60);
        TradeOrderView late = service.submit(OTHER_BUYER, UUID.randomUUID(), fromCart(GOODS + SHIPPING)).order();
        UUID paid = mapper.seed(BUYER, TradeOrderStatus.PAID, NOW.minusSeconds(3600));
        mapper.find(paid).payBefore = NOW.minusSeconds(1800);

        // 截止时间那一刻就关；晚一分钟下的单还没到。
        clock.now = NOW.plus(Duration.ofMinutes(30));
        assertThat(service.closeExpired()).isEqualTo(1);

        assertThat(mapper.find(early.orderId()).status).isEqualTo("CLOSED");
        assertThat(mapper.find(early.orderId()).closedAt).isEqualTo(clock.now);
        assertThat(mapper.find(early.orderId()).version).isEqualTo(1);
        assertThat(mapper.find(late.orderId()).status).isEqualTo("PENDING_PAYMENT");
        assertThat(mapper.find(paid).status).isEqualTo("PAID");
        assertThat(mapper.transitions).last().isEqualTo(new Transition(
                early.orderId(), "PENDING_PAYMENT", "CLOSED", "TIMEOUT", null, clock.now));
        assertThat(mapper.expiredQueries).containsExactly(new ExpiredQuery(clock.now, 100));

        clock.now = NOW.plus(Duration.ofMinutes(31));
        assertThat(service.closeExpired()).isEqualTo(1);
        assertThat(service.closeExpired()).isZero();
        assertThat(service.read(OTHER_BUYER, late.orderId()).status()).isEqualTo(TradeOrderStatus.CLOSED);
    }

    @Test
    void oneRunClosesAtMostOneBatchOldestFirst() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            UUID id = mapper.seed(BUYER, TradeOrderStatus.PENDING_PAYMENT, NOW);
            // 倒着排截止时间：最后建的最早到期。
            mapper.find(id).payBefore = NOW.minusSeconds(1000 - i);
            ids.add(id);
        }

        assertThat(service.closeExpired()).isEqualTo(100);
        assertThat(mapper.find(ids.getLast()).status).isEqualTo("PENDING_PAYMENT");
        assertThat(service.closeExpired()).isEqualTo(1);
        assertThat(mapper.find(ids.getLast()).status).isEqualTo("CLOSED");
    }

    // 有进行中支付单的过期订单由支付服务先查单再关，这里跳过（lockExpired 的 NOT EXISTS）。
    @Test
    void expiredOrdersWithAnOpenPaymentAreLeftToThePaymentService() {
        TradeOrderView withPayment = service.submit(BUYER, UUID.randomUUID(), fromCart(GOODS + SHIPPING)).order();
        TradeOrderView withoutPayment = service.submit(OTHER_BUYER, UUID.randomUUID(), fromCart(GOODS + SHIPPING)).order();
        mapper.openPayments.add(withPayment.orderId());
        clock.now = NOW.plus(Duration.ofHours(1));

        assertThat(service.closeExpired()).isEqualTo(1);

        assertThat(mapper.find(withPayment.orderId()).status).isEqualTo("PENDING_PAYMENT");
        assertThat(mapper.find(withPayment.orderId()).version).isZero();
        assertThat(mapper.find(withoutPayment.orderId()).status).isEqualTo("CLOSED");
        assertThat(mapper.transitions).filteredOn(transition -> "TIMEOUT".equals(transition.cause()))
                .extracting(Transition::orderId)
                .containsExactly(withoutPayment.orderId());
        assertThat(service.closeExpired()).isZero();
    }

    @Test
    void anOrderThatChangedMeanwhileIsNotCountedOrRecorded() {
        TradeOrderView order = service.submit(BUYER, KEY, fromCart(GOODS + SHIPPING)).order();
        mapper.raceLost.add(order.orderId());
        clock.now = NOW.plus(Duration.ofHours(1));

        assertThat(service.closeExpired()).isZero();
        assertThat(mapper.transitions).hasSize(1);
    }

    // ---- 确认收货（E05） ----

    @Test
    void theBuyerConfirmsReceiptOfAShippedOrder() {
        UUID orderId = mapper.seed(BUYER, TradeOrderStatus.SHIPPED, NOW.minus(Duration.ofDays(2)));
        mapper.find(orderId).version = 2;
        clock.now = NOW.plusSeconds(60);

        TradeOrderView received = service.confirmReceipt(BUYER, orderId, 2);

        assertThat(received.status()).isEqualTo(TradeOrderStatus.COMPLETED);
        assertThat(received.completedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(received.version()).isEqualTo(3);
        assertThat(received.cancellable()).isFalse();
        assertThat(mapper.transitions).containsExactly(new Transition(
                orderId, "SHIPPED", "COMPLETED", "RECEIPT", BUYER, NOW.plusSeconds(60)));
        assertThat(mapper.calls).contains("lockBuyerOrder");

        // 已完成的再确认一次（版本对不对都一样）没有变化。
        assertThat(service.confirmReceipt(BUYER, orderId, 3)).isEqualTo(received);
        assertThat(service.confirmReceipt(BUYER, orderId, 0)).isEqualTo(received);
        assertThat(mapper.transitions).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(value = TradeOrderStatus.class, names = {"SHIPPED", "COMPLETED"}, mode = EnumSource.Mode.EXCLUDE)
    void onlyAShippedOrderCanBeReceived(TradeOrderStatus status) {
        UUID orderId = mapper.seed(BUYER, status, NOW);

        assertRefused(() -> service.confirmReceipt(BUYER, orderId, 0), Error.ORDER_STATE_INVALID);
        // 状态不对先于版本不对。
        assertRefused(() -> service.confirmReceipt(BUYER, orderId, 9), Error.ORDER_STATE_INVALID);
        assertThat(mapper.find(orderId).status).isEqualTo(status.name());
        assertThat(mapper.transitions).isEmpty();
    }

    @Test
    void receivingNeedsTheBuyersOwnOrderAtItsCurrentVersion() {
        UUID orderId = mapper.seed(BUYER, TradeOrderStatus.SHIPPED, NOW);

        assertRefused(() -> service.confirmReceipt(BUYER, orderId, 1), Error.VERSION_CONFLICT);
        assertRefused(() -> service.confirmReceipt(OTHER_BUYER, orderId, 0), Error.NOT_FOUND);
        assertRefused(() -> service.confirmReceipt(BUYER, UUID.randomUUID(), 0), Error.NOT_FOUND);
        assertThatThrownBy(() -> service.confirmReceipt(null, orderId, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.confirmReceipt(BUYER, null, 0)).isInstanceOf(IllegalArgumentException.class);

        assertThat(mapper.find(orderId).status).isEqualTo("SHIPPED");
        assertThat(mapper.transitions).isEmpty();
    }

    // 发货满 10 天还没确认收货的自动确认：那一刻就确认，差一秒不确认。
    @Test
    void ordersShippedTenDaysAgoAreReceivedAutomatically() {
        UUID due = mapper.seed(BUYER, TradeOrderStatus.SHIPPED, NOW);
        mapper.find(due).shippedAt = NOW.minus(Duration.ofDays(10));
        UUID older = mapper.seed(OTHER_BUYER, TradeOrderStatus.SHIPPED, NOW);
        mapper.find(older).shippedAt = NOW.minus(Duration.ofDays(30));
        mapper.find(older).version = 4;
        UUID notYet = mapper.seed(BUYER, TradeOrderStatus.SHIPPED, NOW);
        mapper.find(notYet).shippedAt = NOW.minus(Duration.ofDays(10)).plusSeconds(1);
        UUID paid = mapper.seed(BUYER, TradeOrderStatus.PAID, NOW.minus(Duration.ofDays(20)));

        assertThat(service.autoConfirmReceipts()).isEqualTo(2);

        assertThat(mapper.receiptQueries).containsExactly(new ExpiredQuery(NOW.minus(Duration.ofDays(10)), 100));
        assertThat(mapper.find(due).status).isEqualTo("COMPLETED");
        assertThat(mapper.find(due).completedAt).isEqualTo(NOW);
        assertThat(mapper.find(due).version).isEqualTo(1);
        assertThat(mapper.find(older).status).isEqualTo("COMPLETED");
        assertThat(mapper.find(older).version).isEqualTo(5);
        assertThat(mapper.find(notYet).status).isEqualTo("SHIPPED");
        assertThat(mapper.find(paid).status).isEqualTo("PAID");
        // 最早发货的先确认；没有操作人。
        assertThat(mapper.transitions).containsExactly(
                new Transition(older, "SHIPPED", "COMPLETED", "AUTO_RECEIPT", null, NOW),
                new Transition(due, "SHIPPED", "COMPLETED", "AUTO_RECEIPT", null, NOW));
        assertThat(service.autoConfirmReceipts()).isZero();
    }

    @Test
    void anAutomaticReceiptThatLostARaceIsNotCountedOrRecorded() {
        UUID orderId = mapper.seed(BUYER, TradeOrderStatus.SHIPPED, NOW.minus(Duration.ofDays(11)));
        mapper.raceLost.add(orderId);

        assertThat(service.autoConfirmReceipts()).isZero();
        assertThat(mapper.transitions).isEmpty();
        assertThat(TradeOrderService.AUTO_RECEIPT_AFTER).isEqualTo(Duration.ofDays(10));
    }

    // ---- 商家（E05） ----

    @Test
    void aMerchantListsOnlyItsOwnOrdersNewestFirstAndCanFilterByStatus() {
        UUID paid = mapper.seed(BUYER, TradeOrderStatus.PAID, NOW);
        UUID shipped = mapper.seed(OTHER_BUYER, TradeOrderStatus.SHIPPED, NOW.plusSeconds(60));
        UUID pending = mapper.seed(BUYER, TradeOrderStatus.PENDING_PAYMENT, NOW.plusSeconds(120));
        UUID theirs = mapper.seed(BUYER, TradeOrderStatus.PAID, NOW.plusSeconds(180));
        mapper.find(theirs).merchantId = OTHER_MERCHANT;

        assertThat(ids(service.listForMerchant(MERCHANT, null, 1, 50))).containsExactly(pending, shipped, paid);
        assertThat(ids(service.listForMerchant(MERCHANT, " ", 1, 50))).containsExactly(pending, shipped, paid);
        assertThat(ids(service.listForMerchant(MERCHANT, " PAID ", 1, 50))).containsExactly(paid);
        assertThat(ids(service.listForMerchant(MERCHANT, "CLOSED", 1, 50))).isEmpty();
        assertThat(ids(service.listForMerchant(OTHER_MERCHANT, null, 1, 50))).containsExactly(theirs);
        assertThat(mapper.merchantQueries).contains(List.of(MERCHANT, "PAID"));

        TradeOrderPageView page = service.listForMerchant(MERCHANT, null, 1, 2);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.hasMore()).isTrue();
        assertThat(service.listForMerchant(MERCHANT, null, 2, 2).hasMore()).isFalse();
    }

    // 0.5.0：工作台的“待发货”“待自提”都是 PAID，靠配送方式分开。
    @Test
    void aMerchantCanSplitPaidOrdersByDeliveryMode() {
        UUID express = mapper.seed(BUYER, TradeOrderStatus.PAID, NOW);
        UUID pickup = mapper.seed(BUYER, TradeOrderStatus.PAID, NOW.plusSeconds(60));
        mapper.find(pickup).deliveryMode = "PICKUP";
        UUID shippedExpress = mapper.seed(BUYER, TradeOrderStatus.SHIPPED, NOW.plusSeconds(120));

        assertThat(ids(service.listForMerchant(MERCHANT, "PAID", "EXPRESS", 1, 50))).containsExactly(express);
        assertThat(ids(service.listForMerchant(MERCHANT, "PAID", " PICKUP ", 1, 50))).containsExactly(pickup);
        assertThat(ids(service.listForMerchant(MERCHANT, null, "EXPRESS", 1, 50)))
                .containsExactly(shippedExpress, express);
        assertThat(ids(service.listForMerchant(MERCHANT, "PAID", "", 1, 50))).containsExactly(pickup, express);
        for (String invalid : List.of("express", "SELF", "EXPRESS,PICKUP")) {
            assertThatThrownBy(() -> service.listForMerchant(MERCHANT, null, invalid, 1, 20))
                    .as(invalid)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aMerchantReadsOnlyItsOwnPaymentAccount() {
        assertThat(service.readMerchantAccount(MERCHANT)).isEqualTo(new MerchantAccountView(MERCHANT, SUB_MCHID, true));
        mapper.accounts.put(MERCHANT, new TradeOrderMapper.MerchantAccountRow(MERCHANT, SUB_MCHID, false));
        assertThat(service.readMerchantAccount(MERCHANT).active()).isFalse();

        assertRefused(() -> service.readMerchantAccount(OTHER_MERCHANT), Error.NOT_FOUND);
        assertThatThrownBy(() -> service.readMerchantAccount(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anInvalidStatusOrPageOrAMissingMerchantIsRefused() {
        for (Runnable call : List.<Runnable>of(
                () -> service.listForMerchant(MERCHANT, "paid", 1, 20),
                () -> service.listForMerchant(MERCHANT, "TO_PAY", 1, 20),
                () -> service.listForMerchant(MERCHANT, null, 0, 20),
                () -> service.listForMerchant(MERCHANT, null, 10_001, 20),
                () -> service.listForMerchant(MERCHANT, null, 1, 0),
                () -> service.listForMerchant(MERCHANT, null, 1, 51),
                () -> service.listForMerchant(null, null, 1, 20),
                () -> service.readForMerchant(null, UUID.randomUUID()),
                () -> service.ship(null, OPERATOR, UUID.randomUUID(), 0, "顺丰速运", "SF1"),
                () -> service.completePickup(null, OPERATOR, UUID.randomUUID(), 0, "123456"))) {
            assertThatThrownBy(call::run).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(mapper.merchantQueries).isEmpty();
    }

    // 商家要完整手机号发货；取货码只给买家（商家看到了就能自己核销）。
    @Test
    void theMerchantSeesTheFullPhoneAndTheBuyerSeesThePickupCode() {
        UUID express = mapper.seed(BUYER, TradeOrderStatus.PAID, NOW);
        mapper.deliveries.get(express).phone = " 13912345678 ";
        UUID pickup = pickupOrder(TradeOrderStatus.PAID, "123456");

        TradeDeliveryView forMerchant = service.readForMerchant(MERCHANT, express).delivery();
        TradeDeliveryView forBuyer = service.read(BUYER, express).delivery();

        assertThat(forMerchant.recipientPhone()).isEqualTo("13912345678");
        assertThat(forMerchant.phoneMasked()).isEqualTo("139****5678");
        assertThat(forBuyer.recipientPhone()).isNull();
        assertThat(forBuyer.phoneMasked()).isEqualTo("139****5678");
        assertThat(service.listForMerchant(MERCHANT, null, 1, 50).items())
                .filteredOn(order -> order.orderId().equals(express))
                .extracting(order -> order.delivery().recipientPhone())
                .containsExactly("13912345678");

        assertThat(service.read(BUYER, pickup).delivery().pickupCode()).isEqualTo("123456");
        assertThat(service.readForMerchant(MERCHANT, pickup).delivery().pickupCode()).isNull();
        // 只有“已付款、待自提”时给买家；取过货、退款中、已退款、退款异常时不再给。
        for (TradeOrderStatus after : new TradeOrderStatus[] {TradeOrderStatus.COMPLETED, TradeOrderStatus.REFUNDING,
                TradeOrderStatus.REFUNDED, TradeOrderStatus.REFUND_FAILED}) {
            UUID done = pickupOrder(after, "654321");
            assertThat(service.read(BUYER, done).delivery().pickupCode()).as(after.name()).isNull();
        }
        assertThat(service.readForMerchant(MERCHANT, pickup).delivery().recipientPhone()).isNull();
        assertThat(service.listForMerchant(MERCHANT, null, 1, 50).items())
                .extracting(order -> order.delivery().pickupCode())
                .containsOnlyNulls();
    }

    // 商家只在买家付款之后拿到完整手机号和收货地址；待付款、已关闭时只有收货人和打码手机号。买家自己总能看到地址。
    @ParameterizedTest
    @EnumSource(TradeOrderStatus.class)
    void theMerchantGetsThePhoneAndAddressOnlyOnceTheOrderIsPaid(TradeOrderStatus status) {
        UUID orderId = mapper.seed(BUYER, status, NOW);
        boolean paid = status != TradeOrderStatus.PENDING_PAYMENT && status != TradeOrderStatus.CLOSED;

        TradeDeliveryView forMerchant = service.readForMerchant(MERCHANT, orderId).delivery();
        TradeDeliveryView listed = service.listForMerchant(MERCHANT, null, 1, 50).items().getFirst().delivery();
        TradeDeliveryView forBuyer = service.read(BUYER, orderId).delivery();

        for (TradeDeliveryView view : List.of(forMerchant, listed)) {
            assertThat(view.recipientName()).isEqualTo("王女士");
            assertThat(view.phoneMasked()).isEqualTo("138****8000");
            assertThat(view.recipientPhone()).isEqualTo(paid ? "13800138000" : null);
            assertThat(view.addressLine()).isEqualTo(paid ? "山东省东营市 东营区示例路 18 号" : null);
        }
        assertThat(forBuyer.recipientPhone()).isNull();
        assertThat(forBuyer.addressLine()).isEqualTo("山东省东营市 东营区示例路 18 号");
        assertThat(forBuyer.phoneMasked()).isEqualTo("138****8000");
    }

    // ---- 关闭原因 ----

    @Test
    void aCancelledOrderSaysTheBuyerCancelledIt() {
        TradeOrderView order = service.submit(BUYER, KEY, fromCart(GOODS + SHIPPING)).order();
        assertThat(order.closeReason()).isNull();

        TradeOrderView cancelled = service.cancel(BUYER, order.orderId(), 0);

        assertThat(cancelled.closeReason()).isEqualTo("BUYER_CANCEL");
        assertThat(service.read(BUYER, order.orderId()).closeReason()).isEqualTo("BUYER_CANCEL");
        assertThat(service.readForMerchant(MERCHANT, order.orderId()).closeReason()).isEqualTo("BUYER_CANCEL");
        assertThat(service.list(BUYER, "ALL", 1, 20).items()).extracting(TradeOrderView::closeReason)
                .containsExactly("BUYER_CANCEL");
    }

    @Test
    void anOrderClosedByTheDeadlineSaysItTimedOut() {
        TradeOrderView order = service.submit(BUYER, KEY, fromCart(GOODS + SHIPPING)).order();
        clock.now = NOW.plus(Duration.ofMinutes(30));
        service.closeExpired();

        assertThat(service.read(BUYER, order.orderId()).closeReason()).isEqualTo("TIMEOUT");
    }

    // 只有已关闭的单有原因，也只为已关闭的单去查；没有已关闭的单时不查。
    @Test
    void onlyClosedOrdersHaveACloseReasonAndOnlyTheyAreLookedUp() {
        UUID cancelled = service.submit(BUYER, UUID.randomUUID(), fromCart(GOODS + SHIPPING)).order().orderId();
        service.cancel(BUYER, cancelled, 0);
        UUID pending = service.submit(BUYER, UUID.randomUUID(), fromCart(GOODS + SHIPPING)).order().orderId();
        // 关单后才到的付款把订单重新打开成已付款：迁移记录里有过 CLOSED，但现在不是已关闭。
        UUID reopened = mapper.seed(BUYER, TradeOrderStatus.PAID, NOW);
        mapper.transitions.add(new Transition(reopened, "PENDING_PAYMENT", "CLOSED", "TIMEOUT", null, NOW));
        mapper.transitions.add(new Transition(reopened, "CLOSED", "PAID", "PAYMENT_NOTIFIED", null, NOW));
        // 已关闭但找不到迁移记录（不该有）：原因为 null。
        UUID withoutRecord = mapper.seed(BUYER, TradeOrderStatus.CLOSED, NOW);
        mapper.closeCauseQueries.clear();

        Map<UUID, String> reasons = new HashMap<>();
        for (TradeOrderView order : service.list(BUYER, "ALL", 1, 20).items()) {
            reasons.put(order.orderId(), order.closeReason());
        }

        assertThat(reasons).containsEntry(cancelled, "BUYER_CANCEL").containsEntry(pending, null)
                .containsEntry(reopened, null).containsEntry(withoutRecord, null);
        assertThat(mapper.closeCauseQueries).hasSize(1);
        assertThat(mapper.closeCauseQueries.getFirst()).containsExactlyInAnyOrder(cancelled, withoutRecord);

        mapper.closeCauseQueries.clear();
        service.read(BUYER, pending);
        service.read(BUYER, reopened);
        assertThat(mapper.closeCauseQueries).isEmpty();
    }

    // 有几条进入 CLOSED 的迁移记录时，取最后一条的原因（和 SQL 的 DISTINCT ON … ORDER BY occurred_at DESC 一样）。
    @Test
    void theLastMoveIntoClosedGivesTheReason() {
        UUID orderId = mapper.seed(BUYER, TradeOrderStatus.CLOSED, NOW);
        mapper.transitions.add(new Transition(orderId, "PENDING_PAYMENT", "CLOSED", "TIMEOUT", null, NOW));
        mapper.transitions.add(new Transition(orderId, "CLOSED", "PAID", "PAYMENT_NOTIFIED", null, NOW));
        mapper.transitions.add(new Transition(orderId, "PAID", "CLOSED", "BUYER_CANCEL", BUYER, NOW));

        assertThat(service.read(BUYER, orderId).closeReason()).isEqualTo("BUYER_CANCEL");
    }

    @Test
    void anotherMerchantsOrderIsNotFound() {
        UUID orderId = mapper.seed(BUYER, TradeOrderStatus.PAID, NOW);
        UUID pickup = pickupOrder(TradeOrderStatus.PAID, "123456");

        assertRefused(() -> service.readForMerchant(OTHER_MERCHANT, orderId), Error.NOT_FOUND);
        assertRefused(() -> service.readForMerchant(MERCHANT, UUID.randomUUID()), Error.NOT_FOUND);
        assertRefused(() -> service.readForMerchant(MERCHANT, null), Error.NOT_FOUND);
        assertRefused(() -> service.ship(OTHER_MERCHANT, OPERATOR, orderId, 0, "顺丰速运", "SF1"), Error.NOT_FOUND);
        assertRefused(() -> service.ship(MERCHANT, OPERATOR, null, 0, "顺丰速运", "SF1"), Error.NOT_FOUND);
        assertRefused(() -> service.completePickup(OTHER_MERCHANT, OPERATOR, pickup, 0, "123456"), Error.NOT_FOUND);
        assertRefused(() -> service.completePickup(MERCHANT, OPERATOR, null, 0, "123456"), Error.NOT_FOUND);

        assertThat(mapper.find(orderId).status).isEqualTo("PAID");
        assertThat(mapper.find(pickup).status).isEqualTo("PAID");
        assertThat(mapper.deliveries.get(pickup).pickupFailures).isZero();
        assertThat(mapper.transitions).isEmpty();
    }

    // ---- 发货 ----

    @Test
    void shippingAPaidExpressOrderSavesTheTrackingAndRecordsTheOperator() {
        UUID orderId = mapper.seed(BUYER, TradeOrderStatus.PAID, NOW.minusSeconds(600));
        mapper.find(orderId).version = 1;

        TradeOrderView shipped = service.ship(MERCHANT, OPERATOR, orderId, 1, " 顺丰速运 ", " SF1234567890 ");

        assertThat(shipped.status()).isEqualTo(TradeOrderStatus.SHIPPED);
        assertThat(shipped.version()).isEqualTo(2);
        assertThat(shipped.cancellable()).isFalse();
        assertThat(shipped.delivery().carrierName()).isEqualTo("顺丰速运");
        assertThat(shipped.delivery().trackingNo()).isEqualTo("SF1234567890");
        assertThat(shipped.delivery().shippedAt()).isEqualTo(NOW);
        assertThat(shipped.delivery().recipientPhone()).isEqualTo(ADDRESS.phone());
        assertThat(mapper.find(orderId).shippedAt).isEqualTo(NOW);
        assertThat(mapper.transitions).containsExactly(
                new Transition(orderId, "PAID", "SHIPPED", "SHIPMENT", OPERATOR, NOW));
        assertThat(mapper.calls).contains("lockMerchantOrder");
        // 买家看到物流，看不到完整手机号。
        assertThat(service.read(BUYER, orderId).delivery().trackingNo()).isEqualTo("SF1234567890");
        assertThat(service.read(BUYER, orderId).delivery().recipientPhone()).isNull();
    }

    // 同一张单用同样的物流再发一次（重试）原样返回；换了物流不行（改单号不在一期）。
    @Test
    void shippingAgainWithTheSameTrackingChangesNothingAndAnotherTrackingIsRefused() {
        UUID orderId = mapper.seed(BUYER, TradeOrderStatus.PAID, NOW);
        TradeOrderView shipped = service.ship(MERCHANT, OPERATOR, orderId, 0, "顺丰速运", "SF1234567890");

        // 版本对不对都一样：重试时页面可能还拿着旧版本。
        assertThat(service.ship(MERCHANT, OPERATOR, orderId, 0, "顺丰速运 ", "SF1234567890")).isEqualTo(shipped);
        assertThat(service.ship(MERCHANT, OPERATOR, orderId, 1, "顺丰速运", "SF1234567890")).isEqualTo(shipped);
        assertRefused(() -> service.ship(MERCHANT, OPERATOR, orderId, 1, "顺丰速运", "SF0000000000"),
                Error.ORDER_STATE_INVALID);
        assertRefused(() -> service.ship(MERCHANT, OPERATOR, orderId, 1, "中通快递", "SF1234567890"),
                Error.ORDER_STATE_INVALID);

        assertThat(mapper.transitions).hasSize(1);
        assertThat(mapper.deliveries.get(orderId).trackingNo).isEqualTo("SF1234567890");
    }

    // R6-5：发货时告诉买家一次（交易物流“订单已发货”）；同样的物流重试不再说。
    @Test
    void shippingTellsTheBuyerOnce() {
        RecordingBuyerNotifier notifier = new RecordingBuyerNotifier();
        TradeOrderService telling = new TradeOrderService(mapper, checkout, clock, this::nextSuffix,
                TradeSourceEvents.none(), new TradeBuyerEvents(mapper, notifier));
        UUID orderId = mapper.seed(BUYER, TradeOrderStatus.PAID, NOW);

        telling.ship(MERCHANT, OPERATOR, orderId, 0, "顺丰速运", "SF1234567890");
        telling.ship(MERCHANT, OPERATOR, orderId, 1, "顺丰速运", "SF1234567890");

        assertThat(notifier.told).containsExactly("shipped " + orderId + " " + NOW);
        assertThat(notifier.facts.getFirst().buyerId()).isEqualTo(BUYER);
        assertThat(notifier.facts.getFirst().merchantName()).isEqualTo("示例无人机专卖店");
        assertThat(notifier.facts.getFirst().source()).isEqualTo("MALL");
    }

    @ParameterizedTest
    @EnumSource(value = TradeOrderStatus.class, names = {"PAID", "SHIPPED"}, mode = EnumSource.Mode.EXCLUDE)
    void onlyAPaidOrderCanBeShipped(TradeOrderStatus status) {
        UUID orderId = mapper.seed(BUYER, status, NOW);

        assertRefused(() -> service.ship(MERCHANT, OPERATOR, orderId, 0, "顺丰速运", "SF1"), Error.ORDER_STATE_INVALID);
        assertRefused(() -> service.ship(MERCHANT, OPERATOR, orderId, 9, "顺丰速运", "SF1"), Error.ORDER_STATE_INVALID);
        assertThat(mapper.find(orderId).status).isEqualTo(status.name());
        assertThat(mapper.transitions).isEmpty();
    }

    @Test
    void aPickupOrderIsNotShippedAndAStaleVersionIsAConflict() {
        UUID pickup = pickupOrder(TradeOrderStatus.PAID, "123456");
        UUID express = mapper.seed(BUYER, TradeOrderStatus.PAID, NOW);

        assertRefused(() -> service.ship(MERCHANT, OPERATOR, pickup, 0, "顺丰速运", "SF1"), Error.ORDER_STATE_INVALID);
        assertRefused(() -> service.ship(MERCHANT, OPERATOR, express, 1, "顺丰速运", "SF1"), Error.VERSION_CONFLICT);

        assertThat(mapper.find(pickup).status).isEqualTo("PAID");
        assertThat(mapper.find(express).status).isEqualTo("PAID");
        assertThat(mapper.deliveries.get(express).trackingNo).isNull();
        assertThat(mapper.transitions).isEmpty();
    }

    @Test
    void theCarrierAndTrackingNumberAreOneToFortyCharactersWithoutControlCharacters() {
        UUID orderId = mapper.seed(BUYER, TradeOrderStatus.PAID, NOW);

        for (String[] invalid : List.of(
                new String[] {null, "SF1"},
                new String[] {"  ", "SF1"},
                new String[] {"顺".repeat(41), "SF1"},
                new String[] {"顺丰\t速运", "SF1"},
                new String[] {"顺丰速运", null},
                new String[] {"顺丰速运", ""},
                new String[] {"顺丰速运", "S".repeat(41)},
                new String[] {"顺丰速运", "SF1\n"  + "2"})) {
            assertThatThrownBy(() -> service.ship(MERCHANT, OPERATOR, orderId, 0, invalid[0], invalid[1]))
                    .as(Arrays.toString(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(mapper.calls).doesNotContain("lockMerchantOrder");

        TradeOrderView shipped = service.ship(MERCHANT, OPERATOR, orderId, 0, "顺".repeat(40), "S".repeat(40));
        assertThat(shipped.delivery().carrierName()).hasSize(40);
        assertThat(shipped.delivery().trackingNo()).hasSize(40);
    }

    // ---- 自提核销 ----

    @Test
    void theRightPickupCodeCompletesThePickupOrder() {
        UUID orderId = pickupOrder(TradeOrderStatus.PAID, "012345");
        mapper.find(orderId).version = 1;
        clock.now = NOW.plusSeconds(3600);

        TradeOrderView completed = service.completePickup(MERCHANT, OPERATOR, orderId, 1, " 012345 ");

        assertThat(completed.status()).isEqualTo(TradeOrderStatus.COMPLETED);
        assertThat(completed.completedAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(completed.version()).isEqualTo(2);
        assertThat(completed.delivery().pickupCode()).isNull();
        assertThat(mapper.transitions).containsExactly(
                new Transition(orderId, "PAID", "COMPLETED", "PICKUP", OPERATOR, NOW.plusSeconds(3600)));
        assertThat(mapper.calls).containsSubsequence("lockMerchantOrder", "lockPickup");
    }

    // 输错记一次（这次记录不随异常回滚：服务上有 noRollbackFor），10 次后连对的也不收。
    @Test
    void aWrongCodeIsCountedAndTenWrongCodesLockThePickup() throws Exception {
        UUID orderId = pickupOrder(TradeOrderStatus.PAID, "123456");

        for (int attempt = 1; attempt <= 10; attempt++) {
            clock.now = NOW.plusSeconds(attempt);
            assertRefused(() -> service.completePickup(MERCHANT, OPERATOR, orderId, 0, "654321"),
                    Error.PICKUP_CODE_INVALID);
            assertThat(mapper.deliveries.get(orderId).pickupFailures).isEqualTo(attempt);
        }
        assertThat(mapper.pickupFailuresAt).hasSize(10).last().isEqualTo(NOW.plusSeconds(10));

        assertRefused(() -> service.completePickup(MERCHANT, OPERATOR, orderId, 0, "123456"), Error.PICKUP_CODE_INVALID);
        assertRefused(() -> service.completePickup(MERCHANT, OPERATOR, orderId, 0, "654321"), Error.PICKUP_CODE_INVALID);
        assertThat(mapper.deliveries.get(orderId).pickupFailures).isEqualTo(10);
        assertThat(mapper.pickupFailuresAt).hasSize(10);
        assertThat(mapper.find(orderId).status).isEqualTo("PAID");
        assertThat(mapper.transitions).isEmpty();

        assertThat(TradeOrderService.class.getMethod("completePickup", UUID.class, UUID.class, UUID.class, int.class,
                        String.class).getAnnotation(org.springframework.transaction.annotation.Transactional.class)
                .noRollbackFor()).containsExactly(TradeException.class);
    }

    @Test
    void nineWrongCodesStillLeaveTheRightOneWorking() {
        UUID orderId = pickupOrder(TradeOrderStatus.PAID, "123456");
        mapper.deliveries.get(orderId).pickupFailures = 9;

        assertThat(service.completePickup(MERCHANT, OPERATOR, orderId, 0, "123456").status())
                .isEqualTo(TradeOrderStatus.COMPLETED);
        assertThat(TradeOrderService.MAX_PICKUP_FAILURES).isEqualTo(10);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "12345", "1234567", "12345a", "１２３４５６", "123 456"})
    void aPickupCodeMustBeSixDigits(String code) {
        UUID orderId = pickupOrder(TradeOrderStatus.PAID, "123456");

        assertThatThrownBy(() -> service.completePickup(MERCHANT, OPERATOR, orderId, 0, code))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.completePickup(MERCHANT, OPERATOR, orderId, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        // 格式不对的不算输错一次。
        assertThat(mapper.deliveries.get(orderId).pickupFailures).isZero();
        assertThat(mapper.calls).doesNotContain("lockMerchantOrder");
    }

    @Test
    void onlyAPaidPickupOrderWithACodeCanBePickedUpAtItsCurrentVersion() {
        UUID express = mapper.seed(BUYER, TradeOrderStatus.PAID, NOW);
        UUID completed = pickupOrder(TradeOrderStatus.COMPLETED, "123456");
        UUID withoutCode = pickupOrder(TradeOrderStatus.PAID, null);
        UUID stale = pickupOrder(TradeOrderStatus.PAID, "123456");

        assertRefused(() -> service.completePickup(MERCHANT, OPERATOR, express, 0, "123456"), Error.ORDER_STATE_INVALID);
        assertRefused(() -> service.completePickup(MERCHANT, OPERATOR, completed, 0, "123456"),
                Error.ORDER_STATE_INVALID);
        // 付款入账时才生成取货码；没有码的单谁也核销不了。
        assertRefused(() -> service.completePickup(MERCHANT, OPERATOR, withoutCode, 0, "123456"),
                Error.PICKUP_CODE_INVALID);
        assertRefused(() -> service.completePickup(MERCHANT, OPERATOR, stale, 1, "654321"), Error.VERSION_CONFLICT);

        assertThat(mapper.deliveries.get(withoutCode).pickupFailures).isZero();
        assertThat(mapper.deliveries.get(stale).pickupFailures).as("版本不对不算输错").isZero();
        assertThat(mapper.transitions).isEmpty();
    }

    // ---- 商家收款账户 ----

    @Test
    void aMerchantAccountIsRegisteredAndCanBeSwitchedOff() {
        UUID organization = UUID.fromString("c1000000-0000-4000-8000-000000000002");
        mapper.organizations.add(organization);

        MerchantAccountView saved = service.saveMerchantAccount(organization, " 12345678 ", true);
        MerchantAccountView off = service.saveMerchantAccount(organization, "1".repeat(32), false);

        assertThat(saved).isEqualTo(new MerchantAccountView(organization, "12345678", true));
        assertThat(off).isEqualTo(new MerchantAccountView(organization, "1".repeat(32), false));
        assertThat(mapper.accounts.get(organization)).isEqualTo(
                new TradeOrderMapper.MerchantAccountRow(organization, "1".repeat(32), false));
        assertThat(mapper.accountSavedAt).containsExactly(NOW, NOW);

        // 停用后，这个商家的订单提交不了。
        service.saveMerchantAccount(MERCHANT, SUB_MCHID, false);
        assertRefused(() -> service.submit(BUYER, KEY, fromCart(GOODS + SHIPPING)), Error.MERCHANT_PAYMENT_UNAVAILABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "1234567", "123456789012345678901234567890123", "12345678a", "1234 5678",
            "-12345678"})
    void aSubMerchantNumberMustBeEightToThirtyTwoDigits(String subMchid) {
        assertThatThrownBy(() -> service.saveMerchantAccount(MERCHANT, subMchid, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(mapper.accountSavedAt).isEmpty();
    }

    @Test
    void aMerchantAccountNeedsAnOrganizationThatExistsAndAnActiveFlag() {
        assertThatThrownBy(() -> service.saveMerchantAccount(null, SUB_MCHID, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.saveMerchantAccount(MERCHANT, SUB_MCHID, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.saveMerchantAccount(MERCHANT, null, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertRefused(() -> service.saveMerchantAccount(UUID.randomUUID(), SUB_MCHID, true), Error.NOT_FOUND);
        // 格式不对先于机构不存在。
        assertThatThrownBy(() -> service.saveMerchantAccount(UUID.randomUUID(), "abc", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(mapper.accountSavedAt).isEmpty();
    }

    // ---- 辅助 ----

    private String nextSuffix() {
        return suffixes.isEmpty() ? String.format("%08d", mapper.orders.size() + 1) : suffixes.poll();
    }

    /** 本商家的一张自提单（不收运费），取货码为 null 表示还没生成。 */
    private UUID pickupOrder(TradeOrderStatus status, String pickupCode) {
        UUID orderId = mapper.seed(BUYER, status, NOW);
        InMemoryMapper.Order order = mapper.find(orderId);
        order.deliveryMode = "PICKUP";
        order.shippingMinor = 0;
        order.payableMinor = GOODS;
        order.paidMinor = order.paidMinor == 0 ? 0 : GOODS;
        order.shippedAt = null;
        InMemoryMapper.Delivery delivery = mapper.deliveries.get(orderId);
        delivery.mode = "PICKUP";
        delivery.recipientName = null;
        delivery.phone = null;
        delivery.provinceName = null;
        delivery.cityName = null;
        delivery.addressDetail = null;
        delivery.pickupPoint = PICKUP_POINT;
        delivery.pickupCode = pickupCode;
        return orderId;
    }

    private void assertNothingWritten() {
        assertThat(mapper.orders).isEmpty();
        assertThat(mapper.lines).isEmpty();
        assertThat(mapper.deliveries).isEmpty();
        assertThat(mapper.transitions).isEmpty();
        assertThat(checkout.removed).isEmpty();
    }

    private static void assertRefused(Runnable action, Error error) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(TradeException.class, refused -> assertThat(refused.error()).isEqualTo(error));
    }

    private static List<UUID> ids(TradeOrderPageView page) {
        return page.items().stream().map(TradeOrderView::orderId).toList();
    }

    private static SubmitCommand fromCart(long expectedPayable) {
        return new SubmitCommand(
                "370500", List.of(CART_A, CART_B), null, null, "EXPRESS", ADDRESS_ID, "请周末送", expectedPayable, true);
    }

    private static SubmitCommand command(
            String cityCode,
            List<UUID> cartItemIds,
            UUID offerId,
            Integer quantity,
            String deliveryMode,
            Long expectedPayable,
            Boolean agreement,
            String note
    ) {
        return new SubmitCommand(
                cityCode, cartItemIds, offerId, quantity, deliveryMode, null, note, expectedPayable, agreement);
    }

    private static QuotedLine line(UUID offerId, String coverUrl, int quantity) {
        return new QuotedLine(PRODUCT_A, offerId, "示例无人机", "标准版", coverUrl, 100_000, quantity, 100_000L * quantity);
    }

    /** 给定行和商品金额的快递报价（运费 10 元）。 */
    private static CheckoutQuote quoteWith(List<QuotedLine> lines, long goods) {
        return new CheckoutQuote(MERCHANT, "示例无人机专卖店", "LOCAL_STORE", lines, "EXPRESS", null, ADDRESS,
                goods, SHIPPING, goods + SHIPPING);
    }

    /** 商城的报价：两行商品；快递时带运费和地址，自提时带自提点。 */
    private static CheckoutQuote quoteOf(String mode, AddressSnapshot address, long payable) {
        long shipping = "EXPRESS".equals(mode) ? Math.min(SHIPPING, payable) : 0;
        return new CheckoutQuote(
                MERCHANT,
                "示例无人机专卖店",
                "LOCAL_STORE",
                List.of(
                        new QuotedLine(PRODUCT_A, OFFER_A, "示例无人机", "标准版", "https://img.example.com/a.png",
                                100_000, 1, 100_000),
                        new QuotedLine(PRODUCT_B, OFFER_B, "备用电池", "5000mAh", "http://img.example.com/b.png",
                                20_000, 2, 40_000)),
                mode,
                "PICKUP".equals(mode) ? PICKUP_POINT : null,
                address,
                payable - shipping,
                shipping,
                payable);
    }

    // ---- 假的商城与时钟 ----

    private static final class FakeCheckout implements TradeCheckoutPort {

        final List<CheckoutRequest> requests = new ArrayList<>();
        final List<List<UUID>> removed = new ArrayList<>();
        final List<Placed> placed = new ArrayList<>();
        Function<CheckoutRequest, CheckoutQuote> respond = request -> "PICKUP".equals(request.deliveryMode())
                ? quoteOf("PICKUP", null, GOODS)
                : quoteOf("EXPRESS", ADDRESS, GOODS + SHIPPING);

        @Override
        public CheckoutQuote quote(UUID accountId, CheckoutRequest request) {
            requests.add(request);
            return respond.apply(request);
        }

        @Override
        public void removeCartLines(UUID accountId, List<UUID> cartItemIds) {
            removed.add(cartItemIds);
        }

        @Override
        public void orderPlaced(UUID accountId, CheckoutRequest request, UUID orderId) {
            placed.add(new Placed(accountId, request, orderId));
        }
    }

    private static final class MutableClock extends Clock {

        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private record Transition(UUID orderId, String from, String to, String cause, UUID actor, Instant at) {
    }

    private record ExpiredQuery(Instant now, int limit) {
    }

    /** 按 TradeOrderMapper 的 SQL 写的内存实现；插入时检查 V001 的唯一约束和金额约束。 */
    private static final class InMemoryMapper implements TradeOrderMapper {
        // ---- 0.6.0 新加的读写：这些测试不关心，什么都不做 ----

        @Override
        public int saveParties(UUID orderId, String fulfilledFrom, String invoiceIssuer, String aftersaleParty) {
            return 1;
        }

        @Override
        public int saveParticipantNames(UUID orderId, String participantNames) {
            return 1;
        }

        @Override
        public int markLinesWithoutNoReasonReturn(UUID orderId, java.util.List<UUID> offerIds) {
            return offerIds.size();
        }

        @Override
        public java.util.List<com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper.OrderExtrasRow>
                findOrderExtras(java.util.List<UUID> orderIds) {
            return java.util.List.of();
        }


        final List<String> calls = new ArrayList<>();
        final Set<UUID> organizations = new HashSet<>();
        final Map<UUID, MerchantAccountRow> accounts = new HashMap<>();
        final List<Instant> accountSavedAt = new ArrayList<>();
        final List<Order> orders = new ArrayList<>();
        final List<Line> lines = new ArrayList<>();
        final Map<UUID, Delivery> deliveries = new LinkedHashMap<>();
        final List<RefundRow> refunds = new ArrayList<>();
        final List<Transition> transitions = new ArrayList<>();
        final Set<String> takenOrderNos = new HashSet<>();
        final List<String> orderNoChecks = new ArrayList<>();
        final List<String> orderNoLocks = new ArrayList<>();
        final List<ExpiredQuery> expiredQueries = new ArrayList<>();
        final Set<UUID> raceLost = new HashSet<>();
        /** 有进行中支付单（CREATED / PREPAID）的订单；支付单本身由 TradePaymentServiceTest 覆盖。 */
        final Set<UUID> openPayments = new HashSet<>();
        final List<UUID> openPaymentChecks = new ArrayList<>();
        final List<ExpiredQuery> receiptQueries = new ArrayList<>();
        final List<List<Object>> merchantQueries = new ArrayList<>();
        final List<Instant> pickupFailuresAt = new ArrayList<>();
        final List<List<UUID>> closeCauseQueries = new ArrayList<>();
        private long sequence;

        Order find(UUID id) {
            return orders.stream().filter(order -> order.id.equals(id)).findFirst().orElseThrow();
        }

        Order only() {
            assertThat(orders).hasSize(1);
            return orders.getFirst();
        }

        /** 直接放一张某个状态的单，模拟以后的付款、发货、退款步骤。 */
        UUID seed(UUID buyerId, TradeOrderStatus status, Instant createdAt) {
            Order order = new Order();
            order.id = UUID.randomUUID();
            order.sequence = ++sequence;
            order.orderNo = "MO20000101" + String.format("%08d", sequence);
            order.buyerId = buyerId;
            order.merchantId = MERCHANT;
            order.merchantKind = "LOCAL_STORE";
            order.merchantName = "示例无人机专卖店";
            order.subMchid = SUB_MCHID;
            order.cityCode = "370500";
            order.status = status.name();
            order.deliveryMode = "EXPRESS";
            order.goodsMinor = GOODS;
            order.shippingMinor = SHIPPING;
            order.payableMinor = GOODS + SHIPPING;
            order.payBefore = createdAt.plus(Duration.ofMinutes(30));
            order.createdAt = createdAt;
            order.idempotencyKey = UUID.randomUUID();
            order.requestHash = "0".repeat(64);
            if (status == TradeOrderStatus.CLOSED) {
                order.closedAt = createdAt;
            } else if (status != TradeOrderStatus.PENDING_PAYMENT) {
                order.paidAt = createdAt;
                order.paidMinor = order.payableMinor;
            }
            if (status == TradeOrderStatus.SHIPPED || status == TradeOrderStatus.COMPLETED) {
                order.shippedAt = createdAt;
            }
            orders.add(order);
            Delivery delivery = new Delivery();
            delivery.orderId = order.id;
            delivery.mode = "EXPRESS";
            delivery.recipientName = ADDRESS.recipientName();
            delivery.phone = ADDRESS.phone();
            delivery.provinceName = ADDRESS.provinceName();
            delivery.cityName = ADDRESS.cityName();
            delivery.addressDetail = ADDRESS.detail();
            deliveries.put(order.id, delivery);
            return order.id;
        }

        @Override
        public Integer lockBuyer(UUID buyerId) {
            calls.add("lockBuyer:" + buyerId);
            return 1;
        }

        @Override
        public KeyRow findByKey(UUID buyerId, UUID idempotencyKey) {
            calls.add("findByKey");
            return orders.stream()
                    .filter(order -> order.buyerId.equals(buyerId) && idempotencyKey.equals(order.idempotencyKey))
                    .findFirst()
                    .map(order -> new KeyRow(order.id, order.requestHash))
                    .orElse(null);
        }

        @Override
        public MerchantAccountRow findMerchantAccount(UUID organizationId) {
            return accounts.get(organizationId);
        }

        @Override
        public int countOrganization(UUID organizationId) {
            return organizations.contains(organizationId) ? 1 : 0;
        }

        @Override
        public MerchantAccountRow saveMerchantAccount(UUID organizationId, String subMchid, boolean active, Instant at) {
            if (!subMchid.matches("^[0-9]{8,32}$")) {
                throw new IllegalStateException("sub_mchid check violated");
            }
            accountSavedAt.add(at);
            MerchantAccountRow row = new MerchantAccountRow(organizationId, subMchid, active);
            accounts.put(organizationId, row);
            return row;
        }

        @Override
        public UUID insertOrder(
                String orderNo,
                UUID buyerId,
                UUID merchantId,
                String merchantKind,
                String merchantName,
                String subMchid,
                String cityCode,
                String deliveryMode,
                long goodsMinor,
                long shippingMinor,
                long payableMinor,
                String buyerNote,
                Instant payBefore,
                UUID idempotencyKey,
                String requestHash,
                Instant at
        ) {
            if (orders.stream().anyMatch(order -> order.orderNo.equals(orderNo)) || takenOrderNos.contains(orderNo)) {
                throw new IllegalStateException("duplicate order_no");
            }
            if (orders.stream().anyMatch(order -> order.buyerId.equals(buyerId)
                    && idempotencyKey.equals(order.idempotencyKey))) {
                throw new IllegalStateException("duplicate (buyer_id, idempotency_key)");
            }
            if (payableMinor != goodsMinor + shippingMinor || orderNo.length() > 24 || requestHash.length() != 64
                    || (buyerNote != null && buyerNote.length() > 50)) {
                throw new IllegalStateException("trade_order check violated");
            }
            Order order = new Order();
            order.id = UUID.randomUUID();
            order.sequence = ++sequence;
            order.orderNo = orderNo;
            order.buyerId = buyerId;
            order.merchantId = merchantId;
            order.merchantKind = merchantKind;
            order.merchantName = merchantName;
            order.subMchid = subMchid;
            order.cityCode = cityCode;
            order.status = "PENDING_PAYMENT";
            order.deliveryMode = deliveryMode;
            order.goodsMinor = goodsMinor;
            order.shippingMinor = shippingMinor;
            order.payableMinor = payableMinor;
            order.buyerNote = buyerNote;
            order.payBefore = payBefore;
            order.idempotencyKey = idempotencyKey;
            order.requestHash = requestHash;
            order.createdAt = at;
            orders.add(order);
            return order.id;
        }

        @Override
        public Integer lockOrderNo(String orderNo) {
            orderNoLocks.add(orderNo);
            return 1;
        }

        @Override
        public int countOrderNo(String orderNo) {
            orderNoChecks.add(orderNo);
            boolean taken = takenOrderNos.contains(orderNo)
                    || orders.stream().anyMatch(order -> order.orderNo.equals(orderNo));
            return taken ? 1 : 0;
        }

        @Override
        public int insertLine(
                UUID orderId,
                UUID productId,
                UUID offerId,
                String productName,
                String skuName,
                String coverUrl,
                long unitAmountMinor,
                int quantity,
                long subtotalMinor,
                int sortOrder,
                Instant at
        ) {
            Line line = new Line();
            line.row = new LineRow(UUID.randomUUID(), orderId, productId, offerId, productName, skuName, coverUrl,
                    unitAmountMinor, quantity, subtotalMinor);
            line.sortOrder = sortOrder;
            line.sequence = ++sequence;
            lines.add(line);
            return 1;
        }

        @Override
        public int insertDelivery(
                UUID orderId,
                String mode,
                String recipientName,
                String phone,
                String provinceName,
                String cityName,
                String addressDetail,
                String pickupPoint,
                Instant at
        ) {
            if ("EXPRESS".equals(mode) && (recipientName == null || phone == null || cityName == null
                    || addressDetail == null)) {
                throw new IllegalStateException("trade_order_delivery express check violated");
            }
            if ("PICKUP".equals(mode) && pickupPoint == null) {
                throw new IllegalStateException("trade_order_delivery pickup check violated");
            }
            Delivery delivery = new Delivery();
            delivery.orderId = orderId;
            delivery.mode = mode;
            delivery.recipientName = recipientName;
            delivery.phone = phone;
            delivery.provinceName = provinceName;
            delivery.cityName = cityName;
            delivery.addressDetail = addressDetail;
            delivery.pickupPoint = pickupPoint;
            deliveries.put(orderId, delivery);
            return 1;
        }

        @Override
        public int insertTransition(
                UUID orderId, String fromStatus, String toStatus, String cause, UUID actorId, Instant at) {
            transitions.add(new Transition(orderId, fromStatus, toStatus, cause, actorId, at));
            return 1;
        }

        @Override
        public int closeUnpaid(UUID orderId, int version, Instant at) {
            if (raceLost.contains(orderId)) {
                return 0;
            }
            for (Order order : orders) {
                if (order.id.equals(orderId) && "PENDING_PAYMENT".equals(order.status) && order.version == version) {
                    order.status = "CLOSED";
                    order.closedAt = at;
                    order.version++;
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public List<ExpiredRow> lockExpired(Instant now, int limit) {
            expiredQueries.add(new ExpiredQuery(now, limit));
            return orders.stream()
                    .filter(order -> "PENDING_PAYMENT".equals(order.status) && !order.payBefore.isAfter(now))
                    .filter(order -> !openPayments.contains(order.id))
                    .sorted(Comparator.comparing((Order order) -> order.payBefore).thenComparing(order -> order.id))
                    .limit(limit)
                    .map(order -> new ExpiredRow(order.id, order.version))
                    .toList();
        }

        @Override
        public int countOpenPayments(UUID orderId) {
            openPaymentChecks.add(orderId);
            return openPayments.contains(orderId) ? 1 : 0;
        }

        @Override
        public OrderRow findBuyerOrder(UUID buyerId, UUID orderId) {
            calls.add("findBuyerOrder");
            return buyerOrder(buyerId, orderId);
        }

        @Override
        public OrderRow lockBuyerOrder(UUID buyerId, UUID orderId) {
            calls.add("lockBuyerOrder");
            return buyerOrder(buyerId, orderId);
        }

        @Override
        public OrderRow findOrder(UUID orderId) {
            return orders.stream().filter(order -> order.id.equals(orderId)).findFirst().map(Order::toRow).orElse(null);
        }

        @Override
        public long countBuyerOrders(UUID buyerId, List<String> statuses) {
            return buyerOrders(buyerId, statuses).size();
        }

        @Override
        public List<OrderRow> findBuyerOrders(UUID buyerId, List<String> statuses, int limit, int offset) {
            return buyerOrders(buyerId, statuses).stream().skip(offset).limit(limit).map(Order::toRow).toList();
        }

        @Override
        public List<LineRow> findLines(List<UUID> orderIds) {
            return lines.stream()
                    .filter(line -> orderIds.contains(line.row.orderId()))
                    .sorted(Comparator.comparingInt((Line line) -> line.sortOrder).thenComparing(line -> line.sequence))
                    .map(line -> line.row)
                    .toList();
        }

        @Override
        public List<DeliveryRow> findDeliveries(List<UUID> orderIds) {
            return deliveries.values().stream()
                    .filter(delivery -> orderIds.contains(delivery.orderId))
                    .map(Delivery::toRow)
                    .toList();
        }

        @Override
        public List<RefundRow> findRefunds(List<UUID> orderIds) {
            return refunds.stream().filter(refund -> orderIds.contains(refund.orderId())).toList();
        }

        @Override
        public List<CloseCauseRow> findCloseCauses(List<UUID> orderIds) {
            closeCauseQueries.add(List.copyOf(orderIds));
            // 和 SQL 一样：每张单最后一次进入 CLOSED 的原因（迁移记录按写入顺序排）。
            Map<UUID, String> last = new LinkedHashMap<>();
            for (Transition transition : transitions) {
                if ("CLOSED".equals(transition.to()) && orderIds.contains(transition.orderId())) {
                    last.put(transition.orderId(), transition.cause());
                }
            }
            return last.entrySet().stream().map(entry -> new CloseCauseRow(entry.getKey(), entry.getValue())).toList();
        }

        // ---- 商家（E05） ----

        @Override
        public OrderRow findMerchantOrder(UUID merchantId, UUID orderId) {
            calls.add("findMerchantOrder");
            return merchantOrder(merchantId, orderId);
        }

        @Override
        public OrderRow lockMerchantOrder(UUID merchantId, UUID orderId) {
            calls.add("lockMerchantOrder");
            return merchantOrder(merchantId, orderId);
        }

        @Override
        public long countMerchantOrders(UUID merchantId, String status, String deliveryMode) {
            merchantQueries.add(Arrays.asList(merchantId, status));
            return merchantOrders(merchantId, status, deliveryMode).size();
        }

        @Override
        public List<OrderRow> findMerchantOrders(UUID merchantId, String status, String deliveryMode, int limit,
                                                   int offset) {
            return merchantOrders(merchantId, status, deliveryMode).stream().skip(offset).limit(limit).map(Order::toRow)
                    .toList();
        }

        @Override
        public int markShipped(UUID orderId, int version, Instant at) {
            for (Order order : orders) {
                if (order.id.equals(orderId) && "PAID".equals(order.status) && "EXPRESS".equals(order.deliveryMode)
                        && order.version == version) {
                    order.status = "SHIPPED";
                    order.shippedAt = at;
                    order.version++;
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public int saveTracking(UUID orderId, String carrierName, String trackingNo, Instant at) {
            if ((carrierName == null) != (trackingNo == null) || carrierName.length() > 40 || trackingNo.length() > 40) {
                throw new IllegalStateException("trade_order_delivery tracking check violated");
            }
            Delivery delivery = deliveries.get(orderId);
            if (delivery == null) {
                return 0;
            }
            delivery.carrierName = carrierName;
            delivery.trackingNo = trackingNo;
            return 1;
        }

        @Override
        public int markCompleted(UUID orderId, String fromStatus, int version, Instant at) {
            if (raceLost.contains(orderId)) {
                return 0;
            }
            for (Order order : orders) {
                if (order.id.equals(orderId) && order.status.equals(fromStatus) && order.version == version) {
                    order.status = "COMPLETED";
                    order.completedAt = at;
                    order.version++;
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public PickupRow lockPickup(UUID orderId) {
            calls.add("lockPickup");
            Delivery delivery = deliveries.get(orderId);
            // pickup_code 是 char(6)，和 findDeliveries 一样可能带空格。
            return delivery == null ? null : new PickupRow(
                    delivery.pickupCode == null ? null : delivery.pickupCode + " ", delivery.pickupFailures);
        }

        @Override
        public int recordPickupFailure(UUID orderId, Instant at) {
            Delivery delivery = deliveries.get(orderId);
            if (delivery == null || delivery.pickupFailures >= 10) {
                return 0;
            }
            delivery.pickupFailures++;
            pickupFailuresAt.add(at);
            return 1;
        }

        @Override
        public List<ExpiredRow> lockReceiptDue(Instant shippedBefore, int limit) {
            receiptQueries.add(new ExpiredQuery(shippedBefore, limit));
            return orders.stream()
                    .filter(order -> "SHIPPED".equals(order.status) && !order.shippedAt.isAfter(shippedBefore))
                    .sorted(Comparator.comparing((Order order) -> order.shippedAt).thenComparing(order -> order.id))
                    .limit(limit)
                    .map(order -> new ExpiredRow(order.id, order.version))
                    .toList();
        }

        private OrderRow merchantOrder(UUID merchantId, UUID orderId) {
            return orders.stream()
                    .filter(order -> order.id.equals(orderId) && order.merchantId.equals(merchantId))
                    .findFirst()
                    .map(Order::toRow)
                    .orElse(null);
        }

        private List<Order> merchantOrders(UUID merchantId, String status, String deliveryMode) {
            return orders.stream()
                    .filter(order -> order.merchantId.equals(merchantId))
                    .filter(order -> status == null || status.equals(order.status))
                    .filter(order -> deliveryMode == null || deliveryMode.equals(order.deliveryMode))
                    .sorted(Comparator.comparing((Order order) -> order.createdAt)
                            .thenComparing(order -> order.id)
                            .reversed())
                    .toList();
        }

        private OrderRow buyerOrder(UUID buyerId, UUID orderId) {
            return orders.stream()
                    .filter(order -> order.id.equals(orderId) && order.buyerId.equals(buyerId))
                    .findFirst()
                    .map(Order::toRow)
                    .orElse(null);
        }

        private List<Order> buyerOrders(UUID buyerId, List<String> statuses) {
            return orders.stream()
                    .filter(order -> order.buyerId.equals(buyerId))
                    .filter(order -> statuses == null || statuses.isEmpty() || statuses.contains(order.status))
                    .sorted(Comparator.comparing((Order order) -> order.createdAt)
                            .thenComparing(order -> order.id)
                            .reversed())
                    .toList();
        }

        static final class Order {
            UUID id;
            long sequence;
            String orderNo;
            UUID buyerId;
            UUID merchantId;
            String merchantKind;
            String merchantName;
            String subMchid;
            String cityCode;
            String status;
            String deliveryMode;
            long goodsMinor;
            long shippingMinor;
            long payableMinor;
            long paidMinor;
            long refundedMinor;
            String currency = "CNY";
            String buyerNote;
            Instant payBefore;
            Instant paidAt;
            Instant shippedAt;
            Instant completedAt;
            Instant closedAt;
            UUID idempotencyKey;
            String requestHash;
            int version;
            Instant createdAt;

            OrderRow toRow() {
                return new OrderRow(id, orderNo, buyerId, merchantId, merchantKind, merchantName, "MALL", null, status,
                        deliveryMode, goodsMinor, shippingMinor, payableMinor, paidMinor, refundedMinor, currency,
                        buyerNote, payBefore, paidAt, shippedAt, completedAt, closedAt, createdAt, version);
            }
        }

        static final class Line {
            LineRow row;
            int sortOrder;
            long sequence;
        }

        static final class Delivery {
            UUID orderId;
            String mode;
            String recipientName;
            String phone;
            String provinceName;
            String cityName;
            String addressDetail;
            String pickupPoint;
            String pickupCode;
            String carrierName;
            String trackingNo;
            int pickupFailures;

            DeliveryRow toRow() {
                // pickup_code 是 char(6)，库里读出来可能带空格。
                return new DeliveryRow(orderId, mode, recipientName, phone, provinceName, cityName, addressDetail,
                        pickupPoint, pickupCode == null ? null : pickupCode + " ", carrierName, trackingNo);
            }
        }
    }
}
