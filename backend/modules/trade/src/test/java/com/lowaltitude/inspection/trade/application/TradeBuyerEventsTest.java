package com.lowaltitude.inspection.trade.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.trade.application.TradeBuyerNotifier.OrderFacts;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper.LineRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper.OrderRow;
import org.junit.jupiter.api.Test;

/** R6-5：发货、付款到账、退款到账转告买家时带出的事实，以及“只有自提的商城订单才说可以自提了”。 */
class TradeBuyerEventsTest {

    private static final Instant AT = Instant.parse("2026-11-20T02:00:00Z");
    private static final UUID BUYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORDER = UUID.fromString("d1000000-0000-4000-8000-000000000001");
    private static final UUID ENROLLMENT = UUID.fromString("e1000000-0000-4000-8000-000000000001");
    private static final UUID REFUND = UUID.fromString("f1000000-0000-4000-8000-000000000001");

    private final RecordingBuyerNotifier notifier = new RecordingBuyerNotifier();
    private final List<String> reads = new ArrayList<>();

    @Test
    void theFactsAreTheBuyerTheMerchantTheFirstProductAndThePieces() {
        TradeBuyerEvents events = new TradeBuyerEvents(mapper(order("MALL", null, "EXPRESS"),
                List.of(line("编程机器人探索套装", 1), line("备用电池", 2))), notifier);

        events.shipped(ORDER, AT);

        assertThat(notifier.facts).containsExactly(
                new OrderFacts(ORDER, BUYER, "MALL", null, "示例机器人商家", "编程机器人探索套装", 3));
        assertThat(notifier.told).containsExactly("shipped " + ORDER + " " + AT);
    }

    @Test
    void onlyAPaidMallPickupOrderIsReadyForPickup() {
        new TradeBuyerEvents(mapper(order("MALL", null, "PICKUP"), List.of(line("示例无人机", 1))), notifier)
                .paid(ORDER, AT);
        new TradeBuyerEvents(mapper(order("MALL", null, "EXPRESS"), List.of(line("示例无人机", 1))), notifier)
                .paid(ORDER, AT);
        new TradeBuyerEvents(mapper(order("ACTIVITY", ENROLLMENT, "NONE"), List.of(line("编程挑战赛", 2))), notifier)
                .paid(ORDER, AT);

        assertThat(notifier.told).containsExactly("readyForPickup " + ORDER + " " + AT);
    }

    @Test
    void aRefundCarriesTheSourceSoTheReceiverCanTellMallFromSignUps() {
        new TradeBuyerEvents(mapper(order("ACTIVITY", ENROLLMENT, "NONE"), List.of(line("编程挑战赛", 2))), notifier)
                .refunded(ORDER, REFUND, 9_900, AT);

        assertThat(notifier.told).containsExactly("refunded " + ORDER + " " + REFUND + " 9900 " + AT);
        assertThat(notifier.facts.getFirst().source()).isEqualTo("ACTIVITY");
        assertThat(notifier.facts.getFirst().sourceRef()).isEqualTo(ENROLLMENT);
    }

    // 没接站内信时连订单都不读；读不到订单、或通知出错时只记日志，不让发货、入账、退款失败。
    @Test
    void nothingIsReadWithoutANotifierAndFailuresDoNotEscape() {
        TradeBuyerEvents.none().shipped(ORDER, AT);
        TradeBuyerEvents.none().paid(ORDER, AT);
        TradeBuyerEvents.none().refunded(ORDER, REFUND, 1, AT);
        assertThat(reads).isEmpty();

        new TradeBuyerEvents(mapper(null, List.of()), notifier).shipped(ORDER, AT);
        assertThat(notifier.told).isEmpty();

        TradeBuyerNotifier failing = new TradeBuyerNotifier() {
            @Override
            public void shipped(OrderFacts order, Instant at) {
                throw new IllegalStateException("notification store is down");
            }

            @Override
            public void readyForPickup(OrderFacts order, Instant at) {
            }

            @Override
            public void refunded(OrderFacts order, UUID refundId, long amountMinor, Instant at) {
            }
        };
        assertThatCode(() -> new TradeBuyerEvents(mapper(order("MALL", null, "EXPRESS"), List.of()), failing)
                .shipped(ORDER, AT)).doesNotThrowAnyException();
    }

    private OrderRow order(String source, UUID sourceRef, String deliveryMode) {
        return new OrderRow(ORDER, "MO2026112000000001", BUYER, UUID.randomUUID(), "LOCAL_STORE", "示例机器人商家",
                source, sourceRef, "PAID", deliveryMode, 10_000, 0, 10_000, 10_000, 0, "CNY", null,
                AT, AT, null, null, null, AT, 1);
    }

    private static LineRow line(String productName, int quantity) {
        return new LineRow(UUID.randomUUID(), ORDER, UUID.randomUUID(), UUID.randomUUID(), productName, "标准版", null,
                10_000, quantity, 10_000L * quantity);
    }

    /** 只回答 findOrder 和 findLines 的订单 mapper；别的语句一调用就失败。 */
    private TradeOrderMapper mapper(OrderRow order, List<LineRow> lines) {
        return (TradeOrderMapper) Proxy.newProxyInstance(
                TradeOrderMapper.class.getClassLoader(),
                new Class<?>[]{TradeOrderMapper.class},
                (proxy, method, args) -> {
                    reads.add(method.getName());
                    return switch (method.getName()) {
                        case "findOrder" -> order;
                        case "findLines" -> lines;
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }
}
