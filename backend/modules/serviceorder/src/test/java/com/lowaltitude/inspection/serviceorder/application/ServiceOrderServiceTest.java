package com.lowaltitude.inspection.serviceorder.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.lowaltitude.inspection.serviceorder.application.ServiceOrderCodes.ServiceOrderCustomerKind;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderCodes.ServiceOrderDeliveryStatus;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderCodes.ServiceOrderParty;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderCodes.ServiceOrderRefundKind;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderCodes.ServiceOrderStatus;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderException.Error;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.OpeningLine;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.PlanDraft;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderOpening;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderPaymentStartView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderView;
import com.lowaltitude.inspection.serviceorder.infrastructure.persistence.ServiceOrderMapper;
import org.junit.jupiter.api.Test;

/** S4-3 通用服务订单的规则（§28）：内存 mapper + 假交易、假文件存储。真实库上由冒烟 service-order.cjs 验证。 */
class ServiceOrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-23T02:00:00Z");
    private static final UUID CUSTOMER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("11111111-1111-1111-1111-222222222222");
    private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-aaaaaaaaaaaa");
    private static final UUID PROVIDER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_PROVIDER = UUID.fromString("22222222-2222-2222-2222-333333333333");
    private static final UUID OPERATOR = UUID.fromString("22222222-2222-2222-2222-aaaaaaaaaaaa");
    private static final byte[] PDF = "%PDF-1.7 report".getBytes(StandardCharsets.US_ASCII);

    private final InMemoryMapper mapper = new InMemoryMapper();
    private final FakePayments payments = new FakePayments();
    private final Map<String, byte[]> stored = new HashMap<>();
    private final ServiceOrderService service = new ServiceOrderService(mapper, payments, new ServiceOrderPorts.People() {
        @Override
        public Optional<String> displayName(UUID accountId) {
            return Optional.of(CUSTOMER.equals(accountId) ? "王女士" : "账号");
        }

        @Override
        public Set<UUID> qualificationReviewed(Collection<UUID> organizationIds) {
            return Set.of(PROVIDER);
        }
    }, new ServiceOrderPorts.Files() {
        @Override
        public void put(String key, byte[] content) {
            stored.put(key, content);
        }

        @Override
        public byte[] read(String key) {
            return stored.get(key);
        }

        @Override
        public void delete(String key) {
            stored.remove(key);
        }
    }, new ServiceOrderService.Transactions() {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    }, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void openingSnapshotsTheChosenQuoteOnceAndDefaultsASingleLine() {
        UUID orderId = open(List.of());

        ServiceOrderView order = service.readMine(CUSTOMER, orderId);
        assertThat(order.status()).isEqualTo(ServiceOrderStatus.AWAITING_PLAN);
        assertThat(order.orderNo()).matches("SV20261123\\d{6}");
        assertThat(order.customer().kind()).isEqualTo(ServiceOrderCustomerKind.ORGANIZATION);
        assertThat(order.customer().contactName()).isEqualTo("王女士");
        assertThat(order.provider().qualificationReviewed()).isTrue();
        assertThat(order.lines()).singleElement().satisfies(line -> {
            assertThat(line.name()).isEqualTo("服务费用");
            assertThat(line.amountMinor()).isEqualTo(300_000L);
        });
        assertThat(order.quote().team()).isEqualTo("东营本地");
        assertThat(order.actions().cancel()).isTrue();
        assertThat(order.actions().confirmPlan()).isFalse();
        // 同一份需求再建：同一张单。
        assertThat(open(List.of())).isEqualTo(orderId);
        demand = UUID.randomUUID();
        assertThatThrownBy(() -> service.open(opening(List.of(new OpeningLine("航拍", null, null, null, 1L)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertRejected(Error.NOT_FOUND, () -> service.readMine(OTHER, orderId));
        assertRejected(Error.NOT_FOUND, () -> service.readForProvider(OTHER_PROVIDER, orderId));
        assertThat(service.listMine(CUSTOMER, null, 1, 20).items()).singleElement()
                .satisfies(row -> assertThat(row.title()).isEqualTo("航拍需求"));
    }

    @Test
    void theFullFlowFromPlanToAcceptanceWithOneRework() {
        UUID orderId = open(List.of(new OpeningLine("航拍", 1, "次", 300_000L, 300_000L)));

        ServiceOrderView v1 = service.issuePlan(PROVIDER, OPERATOR, orderId, 0,
                new PlanDraft("11/26 上门，约半天", List.of("交付成片 1 条", "照片 50 张"), null));
        assertThat(v1.status()).isEqualTo(ServiceOrderStatus.PLAN_PENDING);
        assertThat(v1.plan().version()).isEqualTo(1);
        assertThat(v1.plan().amountMinor()).isEqualTo(300_000L);
        assertThat(v1.actions().issuePlan()).isTrue();
        // 同样的内容不再出新版本；改了就是 v2。
        assertThat(service.issuePlan(PROVIDER, OPERATOR, orderId, v1.version(),
                new PlanDraft("11/26 上门，约半天", List.of("交付成片 1 条", "照片 50 张"), null)).plan().version())
                .isEqualTo(1);
        ServiceOrderView v2 = service.issuePlan(PROVIDER, OPERATOR, orderId, v1.version(),
                new PlanDraft("11/27 上门，约半天", List.of("交付成片 1 条"), "含后期调色"));
        assertThat(v2.plan().version()).isEqualTo(2);

        ServiceOrderView seen = service.readMine(CUSTOMER, orderId);
        assertRejected(Error.VERSION_CONFLICT, () -> service.confirmPlan(CUSTOMER, orderId, 0, 2, true));
        assertRejected(Error.PLAN_CHANGED, () -> service.confirmPlan(CUSTOMER, orderId, seen.version(), 1, true));
        assertRejected(Error.TERMS_NOT_AGREED, () -> service.confirmPlan(CUSTOMER, orderId, seen.version(), 2, false));
        ServiceOrderView confirmed = service.confirmPlan(CUSTOMER, orderId, seen.version(), 2, true);
        assertThat(confirmed.status()).isEqualTo(ServiceOrderStatus.AWAITING_PAYMENT);
        assertThat(confirmed.plan().confirmedBy()).isEqualTo("王女士");
        assertThat(confirmed.actions().pay()).isTrue();
        assertThat(service.confirmPlan(CUSTOMER, orderId, 0, 2, true).status())
                .isEqualTo(ServiceOrderStatus.AWAITING_PAYMENT);
        assertRejected(Error.STATE_INVALID, () -> service.issuePlan(PROVIDER, OPERATOR, orderId, confirmed.version(),
                new PlanDraft("改", List.of("改"), null)));

        ServiceOrderPaymentStartView payment = service.startPayment(CUSTOMER, orderId);
        assertThat(payment.amountMinor()).isEqualTo(300_000L);
        assertThat(payments.opened).singleElement().satisfies(open -> {
            assertThat(open.optionName()).isEqualTo("方案 v2");
            assertThat(open.itemName()).isEqualTo("航拍需求 · 鹰眼智能巡检");
            assertThat(open.payBefore()).isEqualTo(NOW.plusSeconds(15 * 60));
        });
        assertThat(service.startPayment(CUSTOMER, orderId).tradeOrderId()).isEqualTo(payment.tradeOrderId());
        UUID attempt = payments.opened.getFirst().attemptId();
        assertThat(service.onPaymentConfirmed(payment.tradeOrderId(), attempt)).isTrue();
        assertThat(service.onPaymentConfirmed(payment.tradeOrderId(), attempt)).isTrue();
        ServiceOrderView paid = service.readMine(CUSTOMER, orderId);
        assertThat(paid.status()).isEqualTo(ServiceOrderStatus.AWAITING_START);
        assertThat(paid.payment().paidTradeOrderId()).isEqualTo(payment.tradeOrderId());

        ServiceOrderView started = service.start(PROVIDER, OPERATOR, orderId, paid.version());
        assertThat(started.status()).isEqualTo(ServiceOrderStatus.IN_SERVICE);
        assertThat(service.readMine(CUSTOMER, orderId).actions().cancel()).isFalse();
        assertRejected(Error.STATE_INVALID, () -> service.cancelByCustomer(CUSTOMER, orderId, started.version(), null));

        var file = service.uploadFile(PROVIDER, OPERATOR, orderId, "../报告.pdf", PDF);
        assertThat(file.name()).isEqualTo("报告.pdf");
        assertThat(file.contentType()).isEqualTo("application/pdf");
        assertRejected(Error.FILE_INVALID, () -> service.uploadFile(PROVIDER, OPERATOR, orderId, "a.exe",
                "MZ".getBytes(StandardCharsets.US_ASCII)));
        ServiceOrderView staged = service.readForProvider(PROVIDER, orderId);
        assertThat(staged.stagedFiles()).hasSize(1);
        assertThat(service.readMine(CUSTOMER, orderId).stagedFiles()).isEmpty();
        assertRejected(Error.NOT_FOUND, () -> service.createDownload(CUSTOMER, orderId, file.fileId()));

        ServiceOrderView delivered = service.deliver(PROVIDER, OPERATOR, orderId, staged.version(), "成片已交付",
                List.of(file.fileId()));
        assertThat(delivered.status()).isEqualTo(ServiceOrderStatus.DELIVERED);
        assertThat(delivered.deliveries()).singleElement().satisfies(delivery -> {
            assertThat(delivery.round()).isEqualTo(1);
            assertThat(delivery.files()).hasSize(1);
        });
        assertRejected(Error.STATE_INVALID, () -> service.removeFile(PROVIDER, orderId, file.fileId()));

        var intent = service.createDownload(CUSTOMER, orderId, file.fileId());
        assertThat(intent.url()).startsWith("/api/v1/service-order-files/");
        assertThat(intent.expiresAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusMinutes(10));
        UUID token = UUID.fromString(intent.url().substring("/api/v1/service-order-files/".length()));
        assertThat(service.download(token).content()).isEqualTo(PDF);
        assertRejected(Error.NOT_FOUND, () -> service.download(UUID.randomUUID()));

        ServiceOrderView toReview = service.readMine(CUSTOMER, orderId);
        assertRejected(Error.DELIVERY_CHANGED, () -> service.accept(CUSTOMER, orderId, toReview.version(), 2));
        ServiceOrderView returned = service.rework(CUSTOMER, orderId, toReview.version(), 1, "画面有遮挡");
        assertThat(returned.status()).isEqualTo(ServiceOrderStatus.REWORK);
        assertThat(returned.deliveries().getFirst().status()).isEqualTo(ServiceOrderDeliveryStatus.RETURNED);
        assertThat(returned.deliveries().getFirst().reviewNote()).isEqualTo("画面有遮挡");

        ServiceOrderView again = service.deliver(PROVIDER, OPERATOR, orderId, returned.version(), "重新剪辑", List.of());
        ServiceOrderView accepted = service.accept(CUSTOMER, orderId, again.version(), 2);
        assertThat(accepted.status()).isEqualTo(ServiceOrderStatus.COMPLETED);
        assertThat(accepted.completedAt()).isNotNull();
        assertThat(accepted.deliveries()).extracting(delivery -> delivery.status())
                .containsExactly(ServiceOrderDeliveryStatus.RETURNED, ServiceOrderDeliveryStatus.ACCEPTED);
        assertThat(service.accept(CUSTOMER, orderId, 0, 2).status()).isEqualTo(ServiceOrderStatus.COMPLETED);
        assertThat(accepted.events()).extracting(event -> event.type().name()).containsExactly("CREATED",
                "PLAN_ISSUED", "PLAN_REVISED", "PLAN_CONFIRMED", "PAID", "STARTED", "DELIVERED", "RETURNED",
                "DELIVERED", "ACCEPTED");
    }

    @Test
    void cancellingBeforeStartClosesAPendingPaymentOrRefundsAPaidOne() {
        UUID unpaid = confirmedOrder();
        ServiceOrderPaymentStartView pending = service.startPayment(CUSTOMER, unpaid);
        ServiceOrderView cancelled = service.cancelByCustomer(CUSTOMER, unpaid,
                service.readMine(CUSTOMER, unpaid).version(), null);
        assertThat(payments.cancelled).containsExactly(pending.tradeOrderId());
        assertThat(cancelled.status()).isEqualTo(ServiceOrderStatus.CANCELLED);
        assertThat(cancelled.closedBy()).isEqualTo(ServiceOrderParty.CUSTOMER);
        assertThat(cancelled.closeReason()).isEqualTo("客户取消");
        assertThat(cancelled.payment().refunds()).isEmpty();
        // 关了之后才到账：不收，交易模块整笔退回。
        assertThat(service.onPaymentConfirmed(pending.tradeOrderId(), payments.opened.getFirst().attemptId())).isFalse();

        UUID paidOrder = paidOrder();
        ServiceOrderView byProvider = service.cancelByProvider(PROVIDER, OPERATOR, paidOrder,
                service.readForProvider(PROVIDER, paidOrder).version(), "设备故障，无法按期服务");
        assertThat(byProvider.status()).isEqualTo(ServiceOrderStatus.CANCELLED);
        assertThat(byProvider.payment().refunds()).singleElement().satisfies(refund -> {
            assertThat(refund.kind()).isEqualTo(ServiceOrderRefundKind.CANCEL);
            assertThat(refund.amountMinor()).isEqualTo(300_000L);
            assertThat(refund.status()).isEqualTo("PROCESSING");
        });
        assertThat(payments.locked).contains(byProvider.payment().paidTradeOrderId());
        assertRejected(Error.VERSION_CONFLICT, () -> service.cancelByCustomer(CUSTOMER, confirmedOrderFor(UUID.randomUUID()), 99, null));
        assertThatThrownBy(() -> service.cancelByProvider(PROVIDER, OPERATOR, paidOrder, 0, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aClosedAttemptCanBeRetriedAndAProviderWithoutAccountCannotCollect() {
        UUID orderId = confirmedOrder();
        ServiceOrderPaymentStartView first = service.startPayment(CUSTOMER, orderId);
        service.onPaymentClosed(first.tradeOrderId(), payments.opened.getFirst().attemptId());
        assertThat(service.readMine(CUSTOMER, orderId).payment().pendingTradeOrderId()).isNull();
        ServiceOrderPaymentStartView second = service.startPayment(CUSTOMER, orderId);
        assertThat(second.tradeOrderId()).isNotEqualTo(first.tradeOrderId());
        // 第一张关了之后才到账、服务订单还待付款：照常收下；第二张再付就退回。
        assertThat(service.onPaymentConfirmed(first.tradeOrderId(), payments.opened.getFirst().attemptId())).isTrue();
        assertThat(service.onPaymentConfirmed(second.tradeOrderId(), payments.opened.get(1).attemptId())).isFalse();
        assertThat(service.readMine(CUSTOMER, orderId).status()).isEqualTo(ServiceOrderStatus.AWAITING_START);

        payments.collectable = false;
        UUID other = confirmedOrderFor(UUID.randomUUID());
        assertThat(service.readMine(CUSTOMER, other).actions().pay()).isFalse();
        assertRejected(Error.PAYMENT_UNAVAILABLE, () -> service.startPayment(CUSTOMER, other));
    }

    @Test
    void afterStartOnlyANegotiatedTerminationEndsTheOrder() {
        UUID orderId = paidOrder();
        ServiceOrderView started = service.start(PROVIDER, OPERATOR, orderId,
                service.readForProvider(PROVIDER, orderId).version());
        assertRejected(Error.STATE_INVALID, () -> service.cancelByProvider(PROVIDER, OPERATOR, orderId,
                started.version(), "不做了"));
        assertRejected(Error.REFUND_NOT_ALLOWED, () -> service.terminate(PROVIDER, OPERATOR, orderId,
                started.version(), 300_001L, "协商一致"));
        ServiceOrderView terminated = service.terminate(PROVIDER, OPERATOR, orderId, started.version(), 100_000L,
                "协商一致，退一部分");
        assertThat(terminated.status()).isEqualTo(ServiceOrderStatus.TERMINATED);
        assertThat(terminated.payment().refundedMinor()).isEqualTo(100_000L);
        assertThat(terminated.payment().refunds()).singleElement()
                .satisfies(refund -> assertThat(refund.kind()).isEqualTo(ServiceOrderRefundKind.TERMINATE));
        assertThat(service.terminate(PROVIDER, OPERATOR, orderId, 0, 0L, "重复").status())
                .isEqualTo(ServiceOrderStatus.TERMINATED);

        UUID zero = paidOrder();
        ServiceOrderView zeroStarted = service.start(PROVIDER, OPERATOR, zero,
                service.readForProvider(PROVIDER, zero).version());
        assertThat(service.terminate(PROVIDER, OPERATOR, zero, zeroStarted.version(), 0L, "不退款").payment().refunds())
                .isEmpty();
    }

    @Test
    void fileTypesAreSniffedAndLimited() {
        assertThat(ServiceOrderService.sniff(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}, "a.jpg"))
                .isEqualTo("image/jpeg");
        assertThat(ServiceOrderService.sniff(new byte[] {0x50, 0x4B, 0x03, 0x04, 0}, "表.xlsx"))
                .endsWith("spreadsheetml.sheet");
        assertThat(ServiceOrderService.sniff(new byte[] {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'},
                "a.mp4")).isEqualTo("video/mp4");
        assertRejected(Error.FILE_INVALID, () -> ServiceOrderService.sniff(new byte[0], "a.pdf"));
        assertRejected(Error.FILE_INVALID, () -> ServiceOrderService.sniff("hello".getBytes(StandardCharsets.UTF_8),
                "a.txt"));
        assertThat(ServiceOrderService.fileName("C:\\fakepath\\成片.zip")).isEqualTo("成片.zip");
        assertThat(ServiceOrderService.fileName(" ")).isEqualTo("交付文件");
    }

    // ---- 辅助 ----

    private UUID demand = UUID.randomUUID();

    private UUID open(List<OpeningLine> lines) {
        return service.open(opening(lines));
    }

    private ServiceOrderOpening opening(List<OpeningLine> lines) {
        return new ServiceOrderOpening(demand, UUID.randomUUID(), 1, CUSTOMER, ORG, "东营示例物流公司", PROVIDER,
                "鹰眼智能巡检", "AERIAL", "航拍需求", "370500", "东营市", "黄河口景区", "景区宣传航拍",
                LocalDate.parse("2026-11-30"), 300_000L, "无人机航拍", "半天", 1, "成片 1 条", "东营本地", null, 72,
                30, null, lines);
    }

    private UUID confirmedOrder() {
        return confirmedOrderFor(UUID.randomUUID());
    }

    private UUID confirmedOrderFor(UUID demandId) {
        demand = demandId;
        UUID orderId = open(List.of());
        ServiceOrderView plan = service.issuePlan(PROVIDER, OPERATOR, orderId, 0,
                new PlanDraft("上门半天", List.of("成片 1 条"), null));
        return service.confirmPlan(CUSTOMER, orderId, plan.version(), 1, true).orderId();
    }

    private UUID paidOrder() {
        UUID orderId = confirmedOrder();
        ServiceOrderPaymentStartView payment = service.startPayment(CUSTOMER, orderId);
        service.onPaymentConfirmed(payment.tradeOrderId(), payments.opened.getLast().attemptId());
        return orderId;
    }

    private static void assertRejected(Error error, Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ServiceOrderException.class,
                exception -> assertThat(exception.error()).isEqualTo(error));
    }

    // ---- 假交易 ----

    private final class FakePayments implements ServiceOrderPorts.Payments {

        final List<ServiceOrderPorts.OpenPayment> opened = new ArrayList<>();
        final List<UUID> cancelled = new ArrayList<>();
        final List<UUID> locked = new ArrayList<>();
        final Map<UUID, Long> refunds = new HashMap<>();
        boolean collectable = true;

        @Override
        public boolean merchantCanCollect(UUID organizationId) {
            return collectable;
        }

        @Override
        public ServiceOrderPorts.OpenedPayment open(ServiceOrderPorts.OpenPayment request) {
            opened.add(request);
            return new ServiceOrderPorts.OpenedPayment(UUID.randomUUID(), request.payBefore());
        }

        @Override
        public void lockOrder(UUID tradeOrderId) {
            locked.add(tradeOrderId);
        }

        @Override
        public boolean cancelUnpaid(UUID buyerId, UUID tradeOrderId) {
            cancelled.add(tradeOrderId);
            opened.stream().filter(open -> mapper.payments.values().stream()
                            .anyMatch(row -> row.id().equals(open.attemptId()) && row.tradeOrderId().equals(tradeOrderId)))
                    .findFirst()
                    .ifPresent(open -> service.onPaymentClosed(tradeOrderId, open.attemptId()));
            return false;
        }

        @Override
        public UUID requestRefund(UUID tradeOrderId, long amountMinor, String reason, UUID idempotencyKey,
                                  UUID actorId) {
            UUID id = UUID.randomUUID();
            refunds.put(id, amountMinor);
            return id;
        }

        @Override
        public Map<UUID, String> refundStates(Collection<UUID> refundIds) {
            Map<UUID, String> states = new HashMap<>();
            refundIds.forEach(id -> states.put(id, "PROCESSING"));
            return states;
        }
    }

    // ---- 内存 mapper ----

    private static final class InMemoryMapper implements ServiceOrderMapper {

        final Map<UUID, OrderRow> orders = new HashMap<>();
        final List<LineRow> lines = new ArrayList<>();
        final List<PlanRow> plans = new ArrayList<>();
        final Map<UUID, PaymentRow> payments = new HashMap<>();
        final List<RefundRow> refunds = new ArrayList<>();
        final Map<UUID, UUID> refundOrders = new HashMap<>();
        final List<DeliveryRow> deliveries = new ArrayList<>();
        final Map<UUID, FileRow> files = new HashMap<>();
        final Map<UUID, Object[]> downloads = new HashMap<>();
        final List<Object[]> events = new ArrayList<>();
        int tick;

        private OffsetDateTime stamp(OffsetDateTime at) {
            return at.plusNanos(++tick);
        }

        @Override
        public Integer orderNoTaken(String orderNo) {
            return orders.values().stream().anyMatch(row -> row.orderNo().equals(orderNo)) ? 1 : null;
        }

        @Override
        public UUID findIdByDemand(UUID demandId) {
            return orders.values().stream().filter(row -> row.demandId().equals(demandId)).map(OrderRow::id)
                    .findFirst().orElse(null);
        }

        @Override
        public int insertOrder(UUID id, String orderNo, UUID demandId, UUID quoteId, int quoteVersion, UUID accountId,
                               String customerKind, UUID customerOrganizationId, String customerOrganizationName,
                               String contactName, UUID providerId, String providerName, String type, String title,
                               String cityCode, String cityName, String address, String scope, LocalDate dueDate,
                               long amountMinor, String method, String schedule, int durationDays,
                               String deliverables, String team, String nightWork, Integer reportHours,
                               Integer archiveDays, LocalDate firstVisitDate, OffsetDateTime at) {
            orders.put(id, new OrderRow(id, orderNo, demandId, quoteId, quoteVersion, accountId, customerKind,
                    customerOrganizationName, contactName, providerId, providerName, type, title, cityCode, cityName,
                    address, scope, dueDate, amountMinor, method, schedule, durationDays, deliverables, team,
                    nightWork, reportHours, archiveDays, firstVisitDate, "AWAITING_PLAN", null, null, null, null, null,
                    null, null, null, at, at, 0));
            return 1;
        }

        private OrderRow withLatest(OrderRow row) {
            OffsetDateTime latest = deliveries.stream().filter(d -> d.orderId().equals(row.id()))
                    .map(DeliveryRow::deliveredAt).max(Comparator.naturalOrder()).orElse(null);
            return copy(row, row.status(), row.planVersion(), row.paidAt(), row.startedAt(), row.completedAt(),
                    row.closedAt(), row.closedBy(), row.closeReason(), latest, row.updatedAt(), row.version());
        }

        private static OrderRow copy(OrderRow r, String status, Integer planVersion, OffsetDateTime paidAt,
                                     OffsetDateTime startedAt, OffsetDateTime completedAt, OffsetDateTime closedAt,
                                     String closedBy, String closeReason, OffsetDateTime latest,
                                     OffsetDateTime updatedAt, int version) {
            return new OrderRow(r.id(), r.orderNo(), r.demandId(), r.quoteId(), r.quoteVersion(), r.accountId(),
                    r.customerKind(), r.customerOrganizationName(), r.contactName(), r.providerId(), r.providerName(),
                    r.type(), r.title(), r.cityCode(), r.cityName(), r.address(), r.scopeText(), r.dueDate(),
                    r.amountMinor(), r.quoteMethod(), r.quoteSchedule(), r.quoteDurationDays(),
                    r.quoteDeliverables(), r.quoteTeam(), r.quoteNightWork(), r.quoteReportHours(),
                    r.quoteArchiveDays(), r.quoteFirstVisitDate(), status, planVersion, paidAt, startedAt,
                    completedAt, closedAt, closedBy, closeReason, latest, r.createdAt(), updatedAt, version);
        }

        private OrderRow ofAccount(UUID accountId, UUID orderId) {
            OrderRow row = orders.get(orderId);
            return row == null || !row.accountId().equals(accountId) ? null : withLatest(row);
        }

        private OrderRow ofProvider(UUID providerId, UUID orderId) {
            OrderRow row = orders.get(orderId);
            return row == null || !row.providerId().equals(providerId) ? null : withLatest(row);
        }

        @Override
        public OrderRow findOrder(UUID accountId, UUID orderId) {
            return ofAccount(accountId, orderId);
        }

        @Override
        public OrderRow lockOrder(UUID accountId, UUID orderId) {
            return ofAccount(accountId, orderId);
        }

        @Override
        public OrderRow findProviderOrder(UUID providerId, UUID orderId) {
            return ofProvider(providerId, orderId);
        }

        @Override
        public OrderRow lockProviderOrder(UUID providerId, UUID orderId) {
            return ofProvider(providerId, orderId);
        }

        @Override
        public OrderRow lockOrderById(UUID orderId) {
            OrderRow row = orders.get(orderId);
            return row == null ? null : withLatest(row);
        }

        @Override
        public long countOrders(UUID accountId, String status) {
            return findOrderPage(accountId, status, Integer.MAX_VALUE, 0).size();
        }

        @Override
        public List<OrderRow> findOrderPage(UUID accountId, String status, int limit, int offset) {
            return orders.values().stream().filter(row -> row.accountId().equals(accountId)
                            && (status == null || row.status().equals(status)))
                    .map(this::withLatest).skip(offset).limit(limit).toList();
        }

        @Override
        public long countProviderOrders(UUID providerId, String status) {
            return findProviderOrderPage(providerId, status, Integer.MAX_VALUE, 0).size();
        }

        @Override
        public List<OrderRow> findProviderOrderPage(UUID providerId, String status, int limit, int offset) {
            return orders.values().stream().filter(row -> row.providerId().equals(providerId)
                            && (status == null || row.status().equals(status)))
                    .map(this::withLatest).skip(offset).limit(limit).toList();
        }

        @Override
        public int setPlan(UUID orderId, int planVersion, OffsetDateTime at) {
            OrderRow row = orders.get(orderId);
            if (!Set.of("AWAITING_PLAN", "PLAN_PENDING").contains(row.status())) {
                return 0;
            }
            orders.put(orderId, copy(row, "PLAN_PENDING", planVersion, row.paidAt(), row.startedAt(),
                    row.completedAt(), row.closedAt(), row.closedBy(), row.closeReason(), null, at, row.version() + 1));
            return 1;
        }

        @Override
        public int moveOrder(UUID orderId, String fromStatus, String toStatus, OffsetDateTime at) {
            OrderRow row = orders.get(orderId);
            if (!row.status().equals(fromStatus)) {
                return 0;
            }
            orders.put(orderId, copy(row, toStatus, row.planVersion(),
                    toStatus.equals("AWAITING_START") ? at : row.paidAt(),
                    toStatus.equals("IN_SERVICE") ? at : row.startedAt(),
                    toStatus.equals("COMPLETED") ? at : row.completedAt(), row.closedAt(), row.closedBy(),
                    row.closeReason(), null, at, row.version() + 1));
            return 1;
        }

        @Override
        public int closeOrder(UUID orderId, String fromStatus, String toStatus, String closedBy, String reason,
                              OffsetDateTime at) {
            OrderRow row = orders.get(orderId);
            if (!row.status().equals(fromStatus)) {
                return 0;
            }
            orders.put(orderId, copy(row, toStatus, row.planVersion(), row.paidAt(), row.startedAt(),
                    row.completedAt(), at, closedBy, reason, null, at, row.version() + 1));
            return 1;
        }

        @Override
        public int insertLine(UUID orderId, int sortOrder, String name, Integer quantity, String unit,
                              Long unitAmountMinor, long amountMinor) {
            lines.add(new LineRow(orderId, sortOrder, name, quantity, unit, unitAmountMinor, amountMinor));
            return 1;
        }

        @Override
        public List<LineRow> findLines(UUID orderId) {
            return lines.stream().filter(line -> line.orderId().equals(orderId)).toList();
        }

        @Override
        public int insertPlan(UUID orderId, int planVersion, long amountMinor, String schedule, String terms,
                              String note, UUID issuedBy, OffsetDateTime at) {
            plans.add(new PlanRow(orderId, planVersion, amountMinor, schedule, terms, note, at, null, null));
            return 1;
        }

        @Override
        public PlanRow findPlan(UUID orderId, int planVersion) {
            return plans.stream().filter(plan -> plan.orderId().equals(orderId) && plan.planVersion() == planVersion)
                    .findFirst().orElse(null);
        }

        @Override
        public int confirmPlan(UUID orderId, int planVersion, UUID confirmedBy, String confirmedByName,
                               OffsetDateTime at) {
            PlanRow plan = findPlan(orderId, planVersion);
            if (plan == null || plan.confirmedAt() != null) {
                return 0;
            }
            plans.set(plans.indexOf(plan), new PlanRow(orderId, planVersion, plan.amountMinor(), plan.scheduleText(),
                    plan.termsText(), plan.note(), plan.issuedAt(), confirmedByName, at));
            return 1;
        }

        @Override
        public int insertPayment(UUID id, UUID orderId, UUID tradeOrderId, long amountMinor, OffsetDateTime payBefore,
                                 OffsetDateTime at) {
            payments.put(id, new PaymentRow(id, orderId, tradeOrderId, amountMinor, payBefore, "PENDING", false, null,
                    null, stamp(at)));
            return 1;
        }

        @Override
        public List<PaymentRow> findPayments(UUID orderId) {
            return payments.values().stream().filter(row -> row.orderId().equals(orderId))
                    .sorted(Comparator.comparing(PaymentRow::createdAt)).toList();
        }

        @Override
        public PaymentRow findPayment(UUID attemptId, UUID tradeOrderId) {
            PaymentRow row = payments.get(attemptId);
            return row == null || !row.tradeOrderId().equals(tradeOrderId) ? null : row;
        }

        @Override
        public int markPaymentPaid(UUID attemptId, OffsetDateTime at) {
            PaymentRow row = payments.get(attemptId);
            if (row.refused() || row.status().equals("PAID")) {
                return 0;
            }
            payments.put(attemptId, new PaymentRow(row.id(), row.orderId(), row.tradeOrderId(), row.amountMinor(),
                    row.payBefore(), "PAID", false, at, null, row.createdAt()));
            return 1;
        }

        @Override
        public int closePayment(UUID attemptId, boolean refused, OffsetDateTime at) {
            PaymentRow row = payments.get(attemptId);
            if (!(row.status().equals("PENDING") || (row.status().equals("CLOSED") && refused))) {
                return 0;
            }
            payments.put(attemptId, new PaymentRow(row.id(), row.orderId(), row.tradeOrderId(), row.amountMinor(),
                    row.payBefore(), "CLOSED", row.refused() || refused, null, at, row.createdAt()));
            return 1;
        }

        @Override
        public int insertRefund(UUID orderId, UUID tradeRefundId, String kind, long amountMinor, String reason,
                                UUID requestedBy, OffsetDateTime at) {
            refunds.add(new RefundRow(tradeRefundId, kind, amountMinor, reason, at));
            refundOrders.put(tradeRefundId, orderId);
            return 1;
        }

        @Override
        public List<RefundRow> findRefunds(UUID orderId) {
            return refunds.stream().filter(refund -> orderId.equals(refundOrders.get(refund.tradeRefundId()))).toList();
        }

        @Override
        public int insertDelivery(UUID id, UUID orderId, int round, String note, UUID deliveredBy, OffsetDateTime at) {
            deliveries.add(new DeliveryRow(id, orderId, round, note, "SUBMITTED", stamp(at), null, null, null));
            return 1;
        }

        @Override
        public List<DeliveryRow> findDeliveries(UUID orderId) {
            return deliveries.stream().filter(row -> row.orderId().equals(orderId))
                    .sorted(Comparator.comparingInt(DeliveryRow::round)).toList();
        }

        @Override
        public int reviewDelivery(UUID deliveryId, String status, UUID reviewedBy, String reviewedByName,
                                  String reviewNote, OffsetDateTime at) {
            for (int index = 0; index < deliveries.size(); index++) {
                DeliveryRow row = deliveries.get(index);
                if (row.id().equals(deliveryId) && row.status().equals("SUBMITTED")) {
                    deliveries.set(index, new DeliveryRow(row.id(), row.orderId(), row.round(), row.note(), status,
                            row.deliveredAt(), reviewedByName, at, reviewNote));
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public int insertFile(UUID id, UUID orderId, String name, String contentType, long sizeBytes, String sha256,
                              String storageKey, UUID uploadedBy, OffsetDateTime at) {
            files.put(id, new FileRow(id, orderId, null, name, contentType, sizeBytes, storageKey, stamp(at)));
            return 1;
        }

        @Override
        public List<FileRow> findFiles(UUID orderId) {
            return files.values().stream().filter(row -> row.orderId().equals(orderId))
                    .sorted(Comparator.comparing(FileRow::uploadedAt)).toList();
        }

        @Override
        public FileRow findFile(UUID orderId, UUID fileId) {
            FileRow row = files.get(fileId);
            return row == null || !row.orderId().equals(orderId) ? null : row;
        }

        @Override
        public int countStagedFiles(UUID orderId) {
            return (int) files.values().stream().filter(row -> row.orderId().equals(orderId)
                    && row.deliveryId() == null).count();
        }

        @Override
        public int attachFiles(UUID orderId, UUID deliveryId, List<UUID> fileIds) {
            int changed = 0;
            for (UUID fileId : fileIds) {
                FileRow row = files.get(fileId);
                if (row != null && row.orderId().equals(orderId) && row.deliveryId() == null) {
                    files.put(fileId, new FileRow(row.id(), orderId, deliveryId, row.name(), row.contentType(),
                            row.sizeBytes(), row.storageKey(), row.uploadedAt()));
                    changed++;
                }
            }
            return changed;
        }

        @Override
        public int deleteStagedFile(UUID orderId, UUID fileId) {
            FileRow row = files.get(fileId);
            if (row == null || !row.orderId().equals(orderId) || row.deliveryId() != null) {
                return 0;
            }
            files.remove(fileId);
            return 1;
        }

        @Override
        public int insertDownload(UUID token, UUID fileId, UUID accountId, OffsetDateTime at,
                                  OffsetDateTime expiresAt) {
            downloads.put(token, new Object[] {fileId, expiresAt});
            return 1;
        }

        @Override
        public FileRow findDownload(UUID token, OffsetDateTime now) {
            Object[] download = downloads.get(token);
            if (download == null || !((OffsetDateTime) download[1]).isAfter(now)) {
                return null;
            }
            FileRow row = files.get((UUID) download[0]);
            return row == null || row.deliveryId() == null ? null : row;
        }

        @Override
        public int deleteExpiredDownloads(OffsetDateTime before) {
            return 0;
        }

        @Override
        public int insertEvent(UUID orderId, String type, UUID actorId, Integer planVersion, Integer round,
                               OffsetDateTime at) {
            events.add(new Object[] {orderId, new EventRow(type, planVersion, round, stamp(at))});
            return 1;
        }

        @Override
        public List<EventRow> findEvents(UUID orderId) {
            return events.stream().filter(event -> event[0].equals(orderId)).map(event -> (EventRow) event[1])
                    .toList();
        }
    }
}
