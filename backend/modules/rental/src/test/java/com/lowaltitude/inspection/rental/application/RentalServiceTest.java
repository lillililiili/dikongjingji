package com.lowaltitude.inspection.rental.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.function.Predicate;

import com.lowaltitude.inspection.rental.application.RentalException.Error;
import com.lowaltitude.inspection.rental.application.RentalPorts.Address;
import com.lowaltitude.inspection.rental.application.RentalPorts.Offer;
import com.lowaltitude.inspection.rental.application.RentalPorts.OpenPayment;
import com.lowaltitude.inspection.rental.application.RentalPorts.OpenedPayment;
import com.lowaltitude.inspection.rental.application.RentalPorts.RefundProgress;
import com.lowaltitude.inspection.rental.infrastructure.persistence.RentalMapper;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * 设备租赁的规则测试（F3，§24.1）。mapper 换成内存实现（按 SQL 的条件和 V001 的约束写），商城供给、收货地址、交易付款换成替身。
 * 时钟固定；换时间时用同一份数据另起一个服务。SQL 文本由 RentalMapperContractTest 检查，真实库上由冒烟脚本 rental.cjs 验证。
 */
class RentalServiceTest {

    /** 北京时间 2026-11-19 10:00。 */
    private static final Instant NOW = Instant.parse("2026-11-19T02:00:00Z");
    private static final OffsetDateTime NOW_UTC = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("11111111-1111-1111-1111-222222222222");
    private static final UUID CUSTOMER_ORG = UUID.fromString("11111111-1111-1111-1111-aaaaaaaaaaaa");
    private static final UUID MERCHANT = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID NO_ACCOUNT_MERCHANT = UUID.fromString("c1000000-0000-4000-8000-000000000006");
    private static final UUID OTHER_MERCHANT = UUID.fromString("c1000000-0000-4000-8000-000000000002");
    private static final UUID OPERATOR = UUID.fromString("c1000000-0000-4000-8000-0000000000a1");
    private static final UUID PRODUCT = UUID.fromString("c2000000-0000-4000-8000-000000000401");
    private static final UUID OFFER = UUID.fromString("7344eb2d-bf1d-aa17-7e27-fb2351e99dee");
    private static final UUID JINAN_OFFER = UUID.fromString("7344eb2d-bf1d-aa17-7e27-000000000002");
    private static final UUID UNPAID_OFFER = UUID.fromString("7344eb2d-bf1d-aa17-7e27-000000000006");
    private static final UUID BUY_OFFER = UUID.fromString("7344eb2d-bf1d-aa17-7e27-000000000101");
    private static final UUID ADDRESS = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final RentalLessee COMPANY = new RentalLessee(CUSTOMER_ORG, "东营港务物流", "王女士");
    private static final RentalLessee PERSON = new RentalLessee(null, null, "王女士");

    private final InMemoryMapper mapper = new InMemoryMapper();
    private final FakePayments payments = new FakePayments();
    private final RentalService service = at(NOW);

    // R6-4：出租方的资质是否审核过来自身份模块的端口（这里是 reviewedMerchants），每次读的时候问，不存快照。
    private final java.util.Set<UUID> reviewedMerchants = new java.util.HashSet<>(java.util.Set.of(MERCHANT));

    // ---- 询价单 ----

    @Test
    void theLessorIsMarkedReviewedOnlyWhileItsQualificationIsReviewed() {
        UUID inquiryId = submit(UUID.randomUUID(), draft()).inquiry().inquiryId();
        assertThat(service.readInquiry(ACCOUNT, inquiryId).merchant().qualificationReviewed()).isTrue();

        reviewedMerchants.clear();
        assertThat(service.readInquiry(ACCOUNT, inquiryId).merchant())
                .isEqualTo(new RentalMerchantView(MERCHANT, "示例机器人商家", false));
    }

    @Test
    void anInquirySnapshotsTheOfferTheAddressAndTheLessee() {
        RentalInquiryCreated created = submit(UUID.randomUUID(), draft());
        RentalInquiryView view = created.inquiry();

        assertThat(created.created()).isTrue();
        assertThat(view.status()).isEqualTo(RentalInquiryStatus.PENDING);
        assertThat(view.lessee()).isEqualTo(new RentalLesseeView(RentalLesseeKind.ORGANIZATION, "东营港务物流", "王女士"));
        assertThat(view.merchant()).isEqualTo(new RentalMerchantView(MERCHANT, "示例机器人商家", true));
        assertThat(view.item()).isEqualTo(new RentalItemView(OFFER, PRODUCT, "巡检机器狗", "标准版", null, 38000L, "DAY"));
        assertThat(view.startDate()).isEqualTo(LocalDate.parse("2026-11-22"));
        assertThat(view.endDate()).isEqualTo(LocalDate.parse("2026-11-26"));
        assertThat(view.days()).isEqualTo(5);
        assertThat(view.place()).isEqualTo(new RentalPlaceView(ADDRESS, "王女士", "13800001234", "山东省", "370500",
                "东营市", "东营港 示例港路 6 号"));
        assertThat(view.note()).isEqualTo("夜间巡逻\n需要讲解");
        assertThat(view.quoteDeadline()).isEqualTo(NOW_UTC.plusDays(7));
        assertThat(view.quote()).isNull();
        assertThat(view.confirmable()).isFalse();
        assertThat(view.closable()).isTrue();
        assertThat(mapper.events).extracting(InMemoryMapper.Event::type).containsExactly("SUBMITTED");
        // 个人身份。
        RentalInquiryView personal = service.submit(ACCOUNT, PERSON, UUID.randomUUID(), draft()).inquiry();
        assertThat(personal.lessee()).isEqualTo(new RentalLesseeView(RentalLesseeKind.PERSONAL, null, "王女士"));
    }

    @Test
    void onlyRentableOffersOfACollectingLessorInTheAddressCityCanBeAskedFor() {
        assertRejected(Error.OFFER_UNAVAILABLE, () -> submit(UUID.randomUUID(), draft(BUY_OFFER)));
        assertRejected(Error.OFFER_UNAVAILABLE, () -> submit(UUID.randomUUID(), draft(JINAN_OFFER)));
        assertRejected(Error.PAYMENT_UNAVAILABLE, () -> submit(UUID.randomUUID(), draft(UNPAID_OFFER)));
        assertRejected(Error.NOT_FOUND, () -> submit(UUID.randomUUID(), draft(UUID.randomUUID())));
        assertRejected(Error.ADDRESS_NOT_FOUND, () -> service.submit(OTHER, PERSON, UUID.randomUUID(), draft()));
        assertThat(mapper.inquiries).isEmpty();
    }

    @Test
    void theInquiryContentIsValidated() {
        assertInvalid(() -> submit(UUID.randomUUID(), with(draft(), "quantity", 0)));
        assertInvalid(() -> submit(UUID.randomUUID(), with(draft(), "quantity", 11)));
        assertInvalid(() -> submit(UUID.randomUUID(), with(draft(), "days", 91)));
        assertInvalid(() -> submit(UUID.randomUUID(), with(draft(), "startDate", "2026-11-19")));
        assertInvalid(() -> submit(UUID.randomUUID(), with(draft(), "startDate", "11/22")));
        assertInvalid(() -> submit(UUID.randomUUID(), with(draft(), "handoverMode", "DRONE_DROP")));
        assertInvalid(() -> submit(UUID.randomUUID(), with(draft(), "returnMode", null)));
        assertInvalid(() -> submit(UUID.randomUUID(), with(draft(), "note", "说".repeat(201))));
        assertInvalid(() -> submit(null, draft()));
        assertThat(mapper.inquiries).isEmpty();
        // 明天、1 天、10 台都可以。
        assertThat(submit(UUID.randomUUID(), new RentalInquiryDraft(OFFER, 10, "2026-11-20", 1, "PICKUP",
                "DROP_OFF", ADDRESS, null)).inquiry().endDate()).isEqualTo(LocalDate.parse("2026-11-20"));
    }

    @Test
    void theSameKeyReplaysAndAnotherRequestIsRefused() {
        UUID key = UUID.randomUUID();
        RentalInquiryView first = submit(key, draft()).inquiry();

        RentalInquiryCreated again = submit(key, draft());

        assertThat(again.created()).isFalse();
        assertThat(again.inquiry()).isEqualTo(first);
        assertRejected(Error.IDEMPOTENCY_CONFLICT, () -> submit(key, with(draft(), "days", 6)));
        assertThat(mapper.inquiries).hasSize(1);
    }

    @Test
    void inquiriesAreTheirOwnersAndTheLessorsOnly() {
        RentalInquiryView inquiry = submit(UUID.randomUUID(), draft()).inquiry();

        assertRejected(Error.NOT_FOUND, () -> service.readInquiry(OTHER, inquiry.inquiryId()));
        assertRejected(Error.NOT_FOUND, () -> service.merchantInquiry(OTHER_MERCHANT, inquiry.inquiryId()));
        RentalInquiryView seen = service.merchantInquiry(MERCHANT, inquiry.inquiryId());
        // 商家在付款之前看到的手机号打码；也不能确认、关闭。
        assertThat(seen.place().phone()).isEqualTo("138****1234");
        assertThat(seen.confirmable()).isFalse();
        assertThat(seen.closable()).isFalse();
        assertThat(service.listInquiries(ACCOUNT, "PENDING", 1, 20).total()).isEqualTo(1);
        assertThat(service.listInquiries(ACCOUNT, "QUOTED", 1, 20).items()).isEmpty();
        assertThat(service.merchantInquiries(MERCHANT, null, 1, 20).items()).hasSize(1);
        assertThat(service.merchantInquiries(OTHER_MERCHANT, null, 1, 20).items()).isEmpty();
        assertInvalid(() -> service.listInquiries(ACCOUNT, "LATER", 1, 20));
        assertInvalid(() -> service.listInquiries(ACCOUNT, null, 1, 51));
    }

    // ---- 报价 ----

    @Test
    void quotesAreVersionedAndAddUpTheFees() {
        UUID id = submit(UUID.randomUUID(), draft()).inquiry().inquiryId();

        RentalInquiryView quoted = quote(id, 38000);
        RentalQuoteView first = quoted.quote();

        assertThat(quoted.status()).isEqualTo(RentalInquiryStatus.QUOTED);
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.rentMinor()).isEqualTo(38000L * 5);
        assertThat(first.totalMinor()).isEqualTo(38000L * 5 + 5000);
        assertThat(first.fees()).containsExactly(
                new RentalFeeView("送达与取回", "东营市内", 0), new RentalFeeView("保险", null, 5000));
        assertThat(first.expired()).isFalse();
        // 一样的内容再报一次没有变化；改价是新的一版。
        assertThat(quote(id, 38000).quote().version()).isEqualTo(1);
        RentalInquiryView revised = service.quote(MERCHANT, OPERATOR, id, quoteDraft(36000, 4, 2, NOW_UTC.plusDays(3)));
        assertThat(revised.quote().version()).isEqualTo(2);
        assertThat(revised.quote().rentMinor()).isEqualTo(36000L * 4 * 2);
        assertThat(revised.days()).isEqualTo(4);
        assertThat(revised.quantity()).isEqualTo(2);
        assertThat(revised.endDate()).isEqualTo(LocalDate.parse("2026-11-25"));
        assertThat(mapper.events).extracting(InMemoryMapper.Event::type).containsExactly("SUBMITTED", "QUOTED",
                "QUOTE_REVISED");
        assertThat(service.readInquiry(ACCOUNT, id).confirmable()).isTrue();
    }

    @Test
    void quoteContentIsValidated() {
        UUID id = submit(UUID.randomUUID(), draft()).inquiry().inquiryId();

        assertInvalid(() -> service.quote(MERCHANT, OPERATOR, id, quoteDraft(0, 5, 1, NOW_UTC.plusDays(3))));
        assertInvalid(() -> service.quote(MERCHANT, OPERATOR, id, quoteDraft(38000, 91, 1, NOW_UTC.plusDays(3))));
        assertInvalid(() -> service.quote(MERCHANT, OPERATOR, id, quoteDraft(38000, 5, 11, NOW_UTC.plusDays(3))));
        assertInvalid(() -> service.quote(MERCHANT, OPERATOR, id, quoteDraft(38000, 5, 1, NOW_UTC)));
        assertInvalid(() -> service.quote(MERCHANT, OPERATOR, id,
                quoteDraft(38000, 5, 1, NOW_UTC.plusDays(7).plusSeconds(1))));
        assertInvalid(() -> service.quote(MERCHANT, OPERATOR, id, new RentalQuoteDraft(38000L, 5, 1,
                List.of(new RentalFeeDraft("租金", null, 1L)), true, NOW_UTC.plusDays(3), "条款")));
        assertInvalid(() -> service.quote(MERCHANT, OPERATOR, id, new RentalQuoteDraft(38000L, 5, 1,
                List.of(new RentalFeeDraft("保险", null, 1L), new RentalFeeDraft("保险", null, 2L)), true,
                NOW_UTC.plusDays(3), "条款")));
        assertInvalid(() -> service.quote(MERCHANT, OPERATOR, id, new RentalQuoteDraft(38000L, 5, 1,
                List.of(new RentalFeeDraft("保险", null, -1L)), true, NOW_UTC.plusDays(3), "条款")));
        assertInvalid(() -> service.quote(MERCHANT, OPERATOR, id, new RentalQuoteDraft(38000L, 5, 1,
                List.of(), true, NOW_UTC.plusDays(3), " ")));
        assertInvalid(() -> service.quote(MERCHANT, OPERATOR, id, new RentalQuoteDraft(38000L, 5, 1,
                List.of(), null, NOW_UTC.plusDays(3), "条款")));
        assertRejected(Error.NOT_FOUND, () -> service.quote(OTHER_MERCHANT, OPERATOR, id,
                quoteDraft(38000, 5, 1, NOW_UTC.plusDays(3))));
        assertThat(mapper.quotes).isEmpty();
    }

    @Test
    void declinedAndClosedInquiriesTakeNoMoreQuotes() {
        UUID declined = submit(UUID.randomUUID(), draft()).inquiry().inquiryId();
        UUID closed = submit(UUID.randomUUID(), draft()).inquiry().inquiryId();

        RentalInquiryView view = service.decline(MERCHANT, OPERATOR, declined, " 档期已满 ");
        assertThat(view.status()).isEqualTo(RentalInquiryStatus.DECLINED);
        assertThat(view.declineReason()).isEqualTo("档期已满");
        assertThat(service.decline(MERCHANT, OPERATOR, declined, "再拒绝一次")).isEqualTo(view);
        assertRejected(Error.STATE_INVALID, () -> quote(declined, 38000));
        assertInvalid(() -> service.decline(MERCHANT, OPERATOR, closed, " "));

        RentalInquiryView closing = service.closeInquiry(ACCOUNT, closed);
        assertThat(closing.status()).isEqualTo(RentalInquiryStatus.CLOSED);
        assertThat(closing.closeReason()).isEqualTo(RentalCloseReason.CUSTOMER);
        assertThat(closing.closable()).isFalse();
        assertThat(service.closeInquiry(ACCOUNT, closed)).isEqualTo(closing);
        assertRejected(Error.STATE_INVALID, () -> quote(closed, 38000));
        assertRejected(Error.STATE_INVALID, () -> service.decline(MERCHANT, OPERATOR, closed, "档期已满"));
        assertRejected(Error.STATE_INVALID, () -> service.closeInquiry(ACCOUNT, declined));
    }

    // ---- 确认合同 ----

    @Test
    void confirmingCreatesTheRentalOrderTheContractAndATradeOrder() {
        UUID id = submit(UUID.randomUUID(), draft()).inquiry().inquiryId();
        quote(id, 38000);
        RentalInquiryView quoted = service.readInquiry(ACCOUNT, id);

        RentalConfirmationView confirmation = service.confirm(ACCOUNT, id, quoted.version(), 1, true);

        assertThat(confirmation.payBefore()).isEqualTo(NOW_UTC.plusMinutes(30));
        OpenPayment opened = payments.opened.getFirst();
        assertThat(opened.rentalOrderId()).isEqualTo(confirmation.rentalOrderId());
        assertThat(opened.merchantId()).isEqualTo(MERCHANT);
        assertThat(opened.cityCode()).isEqualTo("370500");
        // 租金一行（日租金 × 天数，数量是台数）；为 0 的费用行不进交易订单。
        assertThat(opened.lines()).extracting(RentalPorts.PaymentLine::optionName, RentalPorts.PaymentLine::unitAmountMinor,
                        RentalPorts.PaymentLine::quantity)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("11月22日–11月26日 共 5 天", 38000L * 5, 1),
                        org.assertj.core.groups.Tuple.tuple("保险", 5000L, 1));
        RentalInquiryView confirmed = service.readInquiry(ACCOUNT, id);
        assertThat(confirmed.status()).isEqualTo(RentalInquiryStatus.CONFIRMED);
        assertThat(confirmed.order()).isEqualTo(new RentalOrderSummaryView(confirmation.rentalOrderId(),
                RentalOrderStatus.PENDING_PAYMENT, confirmation.tradeOrderId()));
        assertThat(confirmed.closable()).isFalse();

        RentalOrderView order = service.readOrder(ACCOUNT, confirmation.rentalOrderId());
        assertThat(order.status()).isEqualTo(RentalOrderStatus.PENDING_PAYMENT);
        assertThat(order.payment()).isEqualTo(new RentalPaymentView(confirmation.tradeOrderId(), 38000L * 5 + 5000,
                NOW_UTC.plusMinutes(30), null));
        RentalContractView contract = order.contract();
        assertThat(contract.contractNo()).isEqualTo("RC20261119000001");
        assertThat(contract.confirmedAt()).isEqualTo(NOW_UTC);
        assertThat(contract.quoteVersion()).isEqualTo(1);
        assertThat(contract.lessee().organizationName()).isEqualTo("东营港务物流");
        assertThat(contract.lessor()).isEqualTo(new RentalMerchantView(MERCHANT, "示例机器人商家", true));
        assertThat(contract.startDate()).isEqualTo(LocalDate.parse("2026-11-22"));
        assertThat(contract.endDate()).isEqualTo(LocalDate.parse("2026-11-26"));
        assertThat(contract.totalMinor()).isEqualTo(38000L * 5 + 5000);
        assertThat(contract.fees()).hasSize(2);
        assertThat(contract.handoverMode()).isEqualTo(RentalHandoverMode.DELIVERY);
        assertThat(contract.terms()).isEqualTo("押金不在线收取。\n超期按天计费。");
        // 同一版再确认一次：原样返回，不再建订单。
        assertThat(service.confirm(ACCOUNT, id, quoted.version(), 1, true)).isEqualTo(confirmation);
        assertThat(payments.opened).hasSize(1);
        assertRejected(Error.STATE_INVALID, () -> quote(id, 30000));
        assertRejected(Error.STATE_INVALID, () -> service.closeInquiry(ACCOUNT, id));
    }

    @Test
    void confirmingChecksAgreementVersionsValidityScheduleAndStartDate() {
        UUID id = submit(UUID.randomUUID(), draft()).inquiry().inquiryId();
        assertRejected(Error.STATE_INVALID, () -> service.confirm(ACCOUNT, id, 0, 1, true));
        quote(id, 38000);
        int version = service.readInquiry(ACCOUNT, id).version();

        assertRejected(Error.TERMS_NOT_AGREED, () -> service.confirm(ACCOUNT, id, version, 1, false));
        assertRejected(Error.TERMS_NOT_AGREED, () -> service.confirm(ACCOUNT, id, version, 1, null));
        assertRejected(Error.VERSION_CONFLICT, () -> service.confirm(ACCOUNT, id, version - 1, 1, true));
        assertInvalid(() -> service.confirm(ACCOUNT, id, version, null, true));
        service.quote(MERCHANT, OPERATOR, id, quoteDraft(36000, 5, 1, NOW_UTC.plusDays(3)));
        int revised = service.readInquiry(ACCOUNT, id).version();
        assertRejected(Error.QUOTE_CHANGED, () -> service.confirm(ACCOUNT, id, revised, 1, true));
        assertRejected(Error.QUOTE_EXPIRED, () -> at(NOW.plusSeconds(3 * 86400)).confirm(ACCOUNT, id, revised, 2, true));
        assertRejected(Error.NOT_FOUND, () -> service.confirm(OTHER, id, revised, 2, true));
        // 档期不可用。
        service.quote(MERCHANT, OPERATOR, id, new RentalQuoteDraft(36000L, 5, 1, List.of(), false,
                NOW_UTC.plusDays(3), "条款"));
        int unavailable = service.readInquiry(ACCOUNT, id).version();
        assertThat(service.readInquiry(ACCOUNT, id).confirmable()).isFalse();
        assertRejected(Error.SCHEDULE_UNAVAILABLE, () -> service.confirm(ACCOUNT, id, unavailable, 3, true));
        // 起租日已过（报价还在有效期内）。
        service.quote(MERCHANT, OPERATOR, id, quoteDraft(36000, 5, 1, NOW_UTC.plusDays(7)));
        int late = service.readInquiry(ACCOUNT, id).version();
        assertRejected(Error.START_DATE_PASSED,
                () -> at(Instant.parse("2026-11-22T16:30:00Z")).confirm(ACCOUNT, id, late, 4, true));
        // 收不了款时整步回滚。
        payments.collecting = false;
        assertRejected(Error.PAYMENT_UNAVAILABLE, () -> service.confirm(ACCOUNT, id, late, 4, true));
        assertThat(payments.opened).isEmpty();
    }

    // ---- 付款、交接、归还 ----

    @Test
    void paymentHandoverAndReturnRunTheOrderToItsEnd() {
        RentalConfirmationView c = contracted();
        assertRejected(Error.STATE_INVALID, () -> service.confirmHandover(ACCOUNT, c.rentalOrderId()));
        assertRejected(Error.STATE_INVALID, () -> record("handover", c.rentalOrderId()));

        assertThat(service.onPaymentConfirmed(c.tradeOrderId(), c.rentalOrderId())).isTrue();
        assertThat(service.onPaymentConfirmed(c.tradeOrderId(), c.rentalOrderId())).isTrue();
        RentalOrderView paid = service.readOrder(ACCOUNT, c.rentalOrderId());
        assertThat(paid.status()).isEqualTo(RentalOrderStatus.AWAITING_HANDOVER);
        assertThat(paid.payment().paidAt()).isEqualTo(NOW_UTC);
        // 付款之后商家看得到完整手机号。
        assertThat(service.merchantOrder(MERCHANT, c.rentalOrderId()).contract().place().phone()).isEqualTo("13800001234");

        RentalOrderView recorded = record("handover", c.rentalOrderId());
        assertThat(recorded.status()).isEqualTo(RentalOrderStatus.HANDOVER_RECORDED);
        assertThat(recorded.handoverRecord().note()).isEqualTo("编号 DOG-0118，配件齐全");
        assertThat(recorded.handoverRecord().confirmedAt()).isNull();
        // 客户确认之前可以再录。
        RentalOrderView again = service.recordHandover(MERCHANT, OPERATOR, c.rentalOrderId(),
                new RentalRecordDraft(NOW_UTC, "编号 DOG-0119"));
        assertThat(again.handoverRecord().note()).isEqualTo("编号 DOG-0119");
        assertThat(service.readOrder(ACCOUNT, c.rentalOrderId()).handoverConfirmable()).isTrue();

        RentalOrderView inUse = service.confirmHandover(ACCOUNT, c.rentalOrderId());
        assertThat(inUse.status()).isEqualTo(RentalOrderStatus.IN_USE);
        assertThat(inUse.rentStartedAt()).isEqualTo(NOW_UTC);
        assertThat(inUse.handoverRecord().confirmedAt()).isEqualTo(NOW_UTC);
        assertThat(service.confirmHandover(ACCOUNT, c.rentalOrderId())).isEqualTo(inUse);
        assertRejected(Error.STATE_INVALID, () -> record("handover", c.rentalOrderId()));
        assertRejected(Error.STATE_INVALID, () -> service.cancel(MERCHANT, OPERATOR, c.rentalOrderId(), "档期冲突"));
        assertRejected(Error.STATE_INVALID, () -> service.confirmReturn(ACCOUNT, c.rentalOrderId()));

        assertThat(record("return", c.rentalOrderId()).status()).isEqualTo(RentalOrderStatus.RETURN_RECORDED);
        assertThat(service.readOrder(ACCOUNT, c.rentalOrderId()).returnConfirmable()).isTrue();
        RentalOrderView ended = service.confirmReturn(ACCOUNT, c.rentalOrderId());
        assertThat(ended.status()).isEqualTo(RentalOrderStatus.ENDED);
        assertThat(ended.endedAt()).isEqualTo(NOW_UTC);
        assertThat(service.confirmReturn(ACCOUNT, c.rentalOrderId())).isEqualTo(ended);
        // 交接确认在归还之后重复点：原样返回。
        assertThat(service.confirmHandover(ACCOUNT, c.rentalOrderId()).status()).isEqualTo(RentalOrderStatus.ENDED);
        assertThat(mapper.events).extracting(InMemoryMapper.Event::type).containsSubsequence("CONFIRMED",
                "PAYMENT_CONFIRMED", "HANDOVER_RECORDED", "HANDOVER_CONFIRMED", "RETURN_RECORDED", "RETURN_CONFIRMED");
        assertThat(service.listOrders(ACCOUNT, "ENDED", 1, 20).total()).isEqualTo(1);
        assertThat(service.merchantOrders(MERCHANT, null, 1, 20).total()).isEqualTo(1);
        assertThat(service.merchantOrders(OTHER_MERCHANT, null, 1, 20).total()).isZero();
        assertRejected(Error.NOT_FOUND, () -> service.readOrder(OTHER, c.rentalOrderId()));
    }

    @Test
    void recordsAreValidated() {
        RentalConfirmationView c = contracted();
        service.onPaymentConfirmed(c.tradeOrderId(), c.rentalOrderId());

        assertInvalid(() -> service.recordHandover(MERCHANT, OPERATOR, c.rentalOrderId(), new RentalRecordDraft(null, " ")));
        assertInvalid(() -> service.recordHandover(MERCHANT, OPERATOR, c.rentalOrderId(),
                new RentalRecordDraft(null, "说".repeat(201))));
        assertInvalid(() -> service.recordHandover(MERCHANT, OPERATOR, c.rentalOrderId(),
                new RentalRecordDraft(NOW_UTC.plusHours(1), "编号 DOG-0118")));
        assertInvalid(() -> service.recordHandover(MERCHANT, OPERATOR, c.rentalOrderId(),
                new RentalRecordDraft(NOW_UTC.minusDays(1), "编号 DOG-0118")));
        assertRejected(Error.NOT_FOUND, () -> service.recordHandover(OTHER_MERCHANT, OPERATOR, c.rentalOrderId(),
                new RentalRecordDraft(null, "编号 DOG-0118")));
    }

    @Test
    void anUnpaidOrderClosesAndALatePaymentIsRefusedForARefund() {
        RentalConfirmationView c = contracted();

        service.onPaymentClosed(c.tradeOrderId(), c.rentalOrderId());
        service.onPaymentClosed(c.tradeOrderId(), c.rentalOrderId());

        RentalOrderView closed = service.readOrder(ACCOUNT, c.rentalOrderId());
        assertThat(closed.status()).isEqualTo(RentalOrderStatus.CLOSED);
        assertThat(closed.closedAt()).isEqualTo(NOW_UTC);
        assertThat(service.readInquiry(ACCOUNT, closed.inquiryId()).order().status()).isEqualTo(RentalOrderStatus.CLOSED);
        // 关闭之后才到账：不恢复，交易模块整笔退款。
        assertThat(service.onPaymentConfirmed(c.tradeOrderId(), c.rentalOrderId())).isFalse();
        assertThat(service.readOrder(ACCOUNT, c.rentalOrderId()).status()).isEqualTo(RentalOrderStatus.CLOSED);
        assertThat(mapper.events).extracting(InMemoryMapper.Event::type).endsWith("PAYMENT_CLOSED", "LATE_PAYMENT_REFUSED");
        // 交易订单和租赁订单对不上：当作不认识。
        assertThat(service.onPaymentConfirmed(UUID.randomUUID(), c.rentalOrderId())).isFalse();
        service.onPaymentClosed(UUID.randomUUID(), c.rentalOrderId());
        // 已付款之后的关闭通知不动它。
        RentalConfirmationView paid = contracted();
        service.onPaymentConfirmed(paid.tradeOrderId(), paid.rentalOrderId());
        service.onPaymentClosed(paid.tradeOrderId(), paid.rentalOrderId());
        assertThat(service.readOrder(ACCOUNT, paid.rentalOrderId()).status()).isEqualTo(RentalOrderStatus.AWAITING_HANDOVER);
    }

    @Test
    void theLessorCancelsBeforeTheHandoverWithAFullRefund() {
        RentalConfirmationView c = contracted();
        assertRejected(Error.STATE_INVALID, () -> service.cancel(MERCHANT, OPERATOR, c.rentalOrderId(), "档期冲突"));
        service.onPaymentConfirmed(c.tradeOrderId(), c.rentalOrderId());
        record("handover", c.rentalOrderId());

        RentalOrderView cancelled = service.cancel(MERCHANT, OPERATOR, c.rentalOrderId(), " 档期临时冲突 ");

        assertThat(cancelled.status()).isEqualTo(RentalOrderStatus.CANCELLED);
        assertThat(cancelled.cancelReason()).isEqualTo("档期临时冲突");
        assertThat(cancelled.refund().amountMinor()).isEqualTo(38000L * 5 + 5000);
        assertThat(cancelled.refund().status()).isEqualTo("REQUESTED");
        assertThat(payments.refunds).singleElement().satisfies(refund -> {
            assertThat(refund.tradeOrderId()).isEqualTo(c.tradeOrderId());
            assertThat(refund.amountMinor()).isEqualTo(38000L * 5 + 5000);
            assertThat(refund.reason()).isEqualTo(RentalService.CANCEL_REFUND_REASON);
        });
        // 先锁交易订单，再锁租赁订单。
        assertThat(payments.locked).containsOnly(c.tradeOrderId());
        assertThat(service.cancel(MERCHANT, OPERATOR, c.rentalOrderId(), "再取消")).isEqualTo(cancelled);
        assertThat(payments.refunds).hasSize(1);
        assertRejected(Error.STATE_INVALID, () -> service.confirmHandover(ACCOUNT, c.rentalOrderId()));
        assertInvalid(() -> service.cancel(MERCHANT, OPERATOR, c.rentalOrderId(), null));
        assertRejected(Error.NOT_FOUND, () -> service.cancel(OTHER_MERCHANT, OPERATOR, c.rentalOrderId(), "档期冲突"));
        // 交易模块不能退时，取消整步回滚。
        RentalConfirmationView refused = contracted();
        service.onPaymentConfirmed(refused.tradeOrderId(), refused.rentalOrderId());
        payments.refundable = false;
        assertRejected(Error.REFUND_NOT_ALLOWED, () -> service.cancel(MERCHANT, OPERATOR, refused.rentalOrderId(), "档期冲突"));
    }

    // ---- 定时任务 ----

    @Test
    void theJobClosesUnquotedInquiriesAndExpiresLapsedQuotes() {
        UUID unquoted = submit(UUID.randomUUID(), draft()).inquiry().inquiryId();
        UUID quoted = submit(UUID.randomUUID(), draft()).inquiry().inquiryId();
        quote(quoted, 38000);

        assertThat(at(NOW.plusSeconds(3 * 86400 - 1)).closeLapsed()).isZero();
        assertThat(at(NOW.plusSeconds(3 * 86400)).closeLapsed()).isEqualTo(1);
        RentalInquiryView expired = service.readInquiry(ACCOUNT, quoted);
        assertThat(expired.status()).isEqualTo(RentalInquiryStatus.EXPIRED);
        assertThat(expired.closable()).isTrue();
        assertThat(at(NOW.plusSeconds(7 * 86400)).closeLapsed()).isEqualTo(1);
        RentalInquiryView closed = service.readInquiry(ACCOUNT, unquoted);
        assertThat(closed.status()).isEqualTo(RentalInquiryStatus.CLOSED);
        assertThat(closed.closeReason()).isEqualTo(RentalCloseReason.NO_QUOTE);
        assertThat(mapper.events).filteredOn(event -> event.actor() == null).extracting(InMemoryMapper.Event::type)
                .containsExactly("QUOTE_EXPIRED", "CLOSED_NO_QUOTE");
        // 过期后商家可以再报：回到待确认，版本继续加。
        RentalService later = at(NOW.plusSeconds(4 * 86400));
        assertThat(later.quote(MERCHANT, OPERATOR, quoted, quoteDraft(36000, 5, 1, NOW_UTC.plusDays(5))).quote().version())
                .isEqualTo(2);
        assertThat(at(NOW.plusSeconds(4 * 86400 + 60)).closeLapsed()).isZero();
    }

    @Test
    void phonesAreMaskedLikeTrade() {
        assertThat(RentalService.mask("13800001234")).isEqualTo("138****1234");
        assertThat(RentalService.mask("123")).isEqualTo("123");
    }

    // ---- 辅助 ----

    private RentalService at(Instant instant) {
        int[] next = {0};
        return new RentalService(mapper, new FakeOffers(), new FakeAddresses(), payments,
                ids -> ids.stream().filter(reviewedMerchants::contains).collect(java.util.stream.Collectors.toSet()),
                Clock.fixed(instant, ZoneOffset.UTC), () -> String.format("RC20261119%06d", ++next[0]));
    }

    private RentalInquiryCreated submit(UUID key, RentalInquiryDraft draft) {
        return service.submit(ACCOUNT, COMPANY, key, draft);
    }

    private RentalInquiryView quote(UUID id, long daily) {
        return service.quote(MERCHANT, OPERATOR, id, quoteDraft(daily, 5, 1, NOW_UTC.plusDays(3)));
    }

    private RentalConfirmationView contracted() {
        UUID id = submit(UUID.randomUUID(), draft()).inquiry().inquiryId();
        quote(id, 38000);
        return service.confirm(ACCOUNT, id, service.readInquiry(ACCOUNT, id).version(), 1, true);
    }

    private RentalOrderView record(String kind, UUID orderId) {
        RentalRecordDraft draft = new RentalRecordDraft(null, "编号 DOG-0118，配件齐全");
        return kind.equals("handover")
                ? service.recordHandover(MERCHANT, OPERATOR, orderId, draft)
                : service.recordReturn(MERCHANT, OPERATOR, orderId, draft);
    }

    private static RentalQuoteDraft quoteDraft(long daily, int days, int quantity, OffsetDateTime validUntil) {
        return new RentalQuoteDraft(daily, days, quantity,
                List.of(new RentalFeeDraft("送达与取回", "东营市内", 0L), new RentalFeeDraft("保险", null, 5000L)),
                true, validUntil, "押金不在线收取。\n超期按天计费。");
    }

    private static RentalInquiryDraft draft() {
        return draft(OFFER);
    }

    private static RentalInquiryDraft draft(UUID offer) {
        return new RentalInquiryDraft(offer, 1, "2026-11-22", 5, "DELIVERY", "COLLECT", ADDRESS, " 夜间巡逻\n需要讲解 ");
    }

    private static RentalInquiryDraft with(RentalInquiryDraft d, String field, Object value) {
        return new RentalInquiryDraft(d.offerId(),
                field.equals("quantity") ? (Integer) value : d.quantity(),
                field.equals("startDate") ? (String) value : d.startDate(),
                field.equals("days") ? (Integer) value : d.days(),
                field.equals("handoverMode") ? (String) value : d.handoverMode(),
                field.equals("returnMode") ? (String) value : d.returnMode(),
                d.addressId(),
                field.equals("note") ? (String) value : d.note());
    }

    private static void assertRejected(Error error, ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(RentalException.class,
                exception -> assertThat(exception.error()).isEqualTo(error));
    }

    private static void assertInvalid(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(IllegalArgumentException.class);
    }

    private static final class FakeOffers implements RentalPorts.Offers {
        @Override
        public Optional<Offer> find(UUID offerId) {
            if (offerId.equals(OFFER)) {
                return Optional.of(new Offer(OFFER, PRODUCT, "巡检机器狗", "标准版", null, MERCHANT, "示例机器人商家",
                        "370500", 38000L, "DAY", true));
            }
            if (offerId.equals(JINAN_OFFER)) {
                return Optional.of(new Offer(JINAN_OFFER, PRODUCT, "巡检机器狗", "标准版", null, MERCHANT, "示例机器人商家",
                        "370100", 38000L, "DAY", true));
            }
            if (offerId.equals(UNPAID_OFFER)) {
                return Optional.of(new Offer(UNPAID_OFFER, PRODUCT, "巡检机器狗", "标准版", null, NO_ACCOUNT_MERCHANT,
                        "示例设备租赁商", "370500", 38000L, "DAY", true));
            }
            if (offerId.equals(BUY_OFFER)) {
                return Optional.of(new Offer(BUY_OFFER, PRODUCT, "编程机器人", "标准版", null, MERCHANT, "示例机器人商家",
                        "370500", 129900L, "ITEM", false));
            }
            return Optional.empty();
        }
    }

    private static final class FakeAddresses implements RentalPorts.Addresses {
        @Override
        public Optional<Address> find(UUID accountId, UUID addressId) {
            return accountId.equals(ACCOUNT) && addressId.equals(ADDRESS)
                    ? Optional.of(new Address(ADDRESS, "王女士", "13800001234", "山东省", "370500", "东营市",
                            "东营港 示例港路 6 号"))
                    : Optional.empty();
        }
    }

    private static final class FakePayments implements RentalPorts.Payments {
        final List<OpenPayment> opened = new ArrayList<>();
        final List<Refund> refunds = new ArrayList<>();
        final List<UUID> locked = new ArrayList<>();
        boolean collecting = true;
        boolean refundable = true;

        @Override
        public boolean merchantCanCollect(UUID organizationId) {
            return collecting && !organizationId.equals(NO_ACCOUNT_MERCHANT);
        }

        @Override
        public OpenedPayment open(OpenPayment request) {
            if (!merchantCanCollect(request.merchantId())) {
                throw new RentalException(Error.PAYMENT_UNAVAILABLE);
            }
            opened.add(request);
            return new OpenedPayment(UUID.nameUUIDFromBytes(request.rentalOrderId().toString().getBytes()),
                    request.payBefore());
        }

        @Override
        public void lockOrder(UUID tradeOrderId) {
            locked.add(tradeOrderId);
        }

        @Override
        public UUID requestRefund(UUID tradeOrderId, long amountMinor, String reason, UUID idempotencyKey,
                                  UUID actorId) {
            if (!refundable) {
                throw new RentalException(Error.REFUND_NOT_ALLOWED);
            }
            UUID id = UUID.randomUUID();
            refunds.add(new Refund(id, tradeOrderId, amountMinor, reason));
            return id;
        }

        @Override
        public Map<UUID, RefundProgress> refundStates(Collection<UUID> refundIds) {
            Map<UUID, RefundProgress> states = new HashMap<>();
            refunds.stream().filter(refund -> refundIds.contains(refund.id()))
                    .forEach(refund -> states.put(refund.id(), new RefundProgress("REQUESTED", refund.amountMinor())));
            return states;
        }

        record Refund(UUID id, UUID tradeOrderId, long amountMinor, String reason) {
        }
    }

    /** 内存里的 mapper：每个方法按对应 SQL 的条件写，V001 的约束在这里用异常代替。 */
    private static final class InMemoryMapper implements RentalMapper {

        final List<Inquiry> inquiries = new ArrayList<>();
        final List<Quote> quotes = new ArrayList<>();
        final List<FeeRow> fees = new ArrayList<>();
        final List<Order> orders = new ArrayList<>();
        final List<ContractRow> contracts = new ArrayList<>();
        final List<Rec> records = new ArrayList<>();
        final List<Event> events = new ArrayList<>();

        private Inquiry inquiry(UUID id) {
            return inquiries.stream().filter(row -> row.id.equals(id)).findFirst().orElse(null);
        }

        private Order order(UUID id) {
            return orders.stream().filter(row -> row.id.equals(id)).findFirst().orElse(null);
        }

        @Override
        public Integer lockAccount(UUID accountId) {
            return 1;
        }

        @Override
        public KeyRow findByKey(UUID accountId, UUID idempotencyKey) {
            return inquiries.stream().filter(row -> row.accountId.equals(accountId) && row.key.equals(idempotencyKey))
                    .map(row -> new KeyRow(row.id, row.hash)).findFirst().orElse(null);
        }

        @Override
        public int insertInquiry(UUID id, UUID accountId, String lesseeKind, UUID lesseeOrganizationId,
                                 String lesseeOrganizationName, String lesseeContactName, UUID offerId, UUID productId,
                                 String productName, String skuName, String coverUrl, UUID merchantId,
                                 String merchantName, Long referenceAmountMinor, String referenceUnit, int quantity,
                                 LocalDate startDate, int days, String handoverMode, String returnMode,
                                 UUID addressId, String recipientName, String recipientPhone, String provinceName,
                                 String cityCode, String cityName, String addressDetail, String note,
                                 OffsetDateTime quoteDeadline, UUID idempotencyKey, String requestHash,
                                 OffsetDateTime at) {
            if (findByKey(accountId, idempotencyKey) != null || quantity < 1 || quantity > 10 || days < 1 || days > 90
                    || ("ORGANIZATION".equals(lesseeKind)) != (lesseeOrganizationId != null)) {
                throw new IllegalStateException("rental_inquiry check");
            }
            Inquiry row = new Inquiry();
            row.id = id;
            row.accountId = accountId;
            row.key = idempotencyKey;
            row.hash = requestHash;
            row.data = new InquiryRow(id, accountId, lesseeKind, lesseeOrganizationName, lesseeContactName, offerId,
                    productId, productName, skuName, coverUrl, merchantId, merchantName, referenceAmountMinor,
                    referenceUnit, quantity, startDate, days, handoverMode, returnMode, addressId, recipientName,
                    recipientPhone, provinceName, cityCode, cityName, addressDetail, note, "PENDING", null, null,
                    null, quoteDeadline, at, at, 0);
            inquiries.add(row);
            return 1;
        }

        private InquiryRow row(Inquiry i) {
            InquiryRow d = i.data;
            return new InquiryRow(d.id(), d.accountId(), d.lesseeKind(), d.lesseeOrganizationName(),
                    d.lesseeContactName(), d.offerId(), d.productId(), d.productName(), d.skuName(), d.coverUrl(),
                    d.merchantId(), d.merchantName(), d.referenceAmountMinor(), d.referenceUnit(), d.quantity(),
                    d.startDate(), d.days(), d.handoverMode(), d.returnMode(), d.addressId(), d.recipientName(),
                    d.recipientPhone(), d.provinceName(), d.cityCode(), d.cityName(), d.addressDetail(), d.note(),
                    i.status, i.closeReason, i.declineReason, i.quoteVersion, d.quoteDeadline(), d.createdAt(),
                    i.updatedAt == null ? d.updatedAt() : i.updatedAt, i.version);
        }

        private InquiryRow find(UUID id, Predicate<Inquiry> owner) {
            Inquiry i = inquiry(id);
            return i == null || !owner.test(i) ? null : row(i);
        }

        @Override
        public InquiryRow findInquiry(UUID accountId, UUID inquiryId) {
            return find(inquiryId, i -> i.accountId.equals(accountId));
        }

        @Override
        public InquiryRow lockInquiry(UUID accountId, UUID inquiryId) {
            return findInquiry(accountId, inquiryId);
        }

        @Override
        public InquiryRow findMerchantInquiry(UUID merchantId, UUID inquiryId) {
            return find(inquiryId, i -> i.data.merchantId().equals(merchantId));
        }

        @Override
        public InquiryRow lockMerchantInquiry(UUID merchantId, UUID inquiryId) {
            return findMerchantInquiry(merchantId, inquiryId);
        }

        private List<InquiryRow> page(Predicate<Inquiry> filter, String status, int limit, int offset) {
            return inquiries.stream().filter(filter).filter(i -> status == null || i.status.equals(status))
                    .map(this::row).sorted(Comparator.comparing(InquiryRow::updatedAt).reversed())
                    .skip(offset).limit(limit).toList();
        }

        @Override
        public long countInquiries(UUID accountId, String status) {
            return page(i -> i.accountId.equals(accountId), status, Integer.MAX_VALUE, 0).size();
        }

        @Override
        public List<InquiryRow> findInquiryPage(UUID accountId, String status, int limit, int offset) {
            return page(i -> i.accountId.equals(accountId), status, limit, offset);
        }

        @Override
        public long countMerchantInquiries(UUID merchantId, String status) {
            return page(i -> i.data.merchantId().equals(merchantId), status, Integer.MAX_VALUE, 0).size();
        }

        @Override
        public List<InquiryRow> findMerchantInquiryPage(UUID merchantId, String status, int limit, int offset) {
            return page(i -> i.data.merchantId().equals(merchantId), status, limit, offset);
        }

        private int move(UUID id, Set<String> from, java.util.function.Consumer<Inquiry> change, OffsetDateTime at) {
            Inquiry i = inquiry(id);
            if (i == null || !from.contains(i.status)) {
                return 0;
            }
            change.accept(i);
            i.version++;
            i.updatedAt = at;
            return 1;
        }

        @Override
        public int closeInquiry(UUID inquiryId, String reason, OffsetDateTime at) {
            return move(inquiryId, Set.of("PENDING", "QUOTED", "EXPIRED"), i -> {
                i.status = "CLOSED";
                i.closeReason = reason;
            }, at);
        }

        @Override
        public int declineInquiry(UUID inquiryId, String reason, OffsetDateTime at) {
            return move(inquiryId, Set.of("PENDING", "QUOTED", "EXPIRED"), i -> {
                i.status = "DECLINED";
                i.declineReason = reason;
            }, at);
        }

        @Override
        public int markQuoted(UUID inquiryId, int quoteVersion, OffsetDateTime at) {
            return move(inquiryId, Set.of("PENDING", "QUOTED", "EXPIRED"), i -> {
                i.status = "QUOTED";
                i.quoteVersion = quoteVersion;
            }, at);
        }

        @Override
        public int expireInquiry(UUID inquiryId, OffsetDateTime at) {
            return move(inquiryId, Set.of("QUOTED"), i -> i.status = "EXPIRED", at);
        }

        @Override
        public int confirmInquiry(UUID inquiryId, int version, OffsetDateTime at) {
            Inquiry i = inquiry(inquiryId);
            if (i == null || i.version != version) {
                return 0;
            }
            return move(inquiryId, Set.of("QUOTED"), found -> found.status = "CONFIRMED", at);
        }

        @Override
        public List<InquiryRow> lockUnquoted(OffsetDateTime now, int limit) {
            return inquiries.stream().filter(i -> i.status.equals("PENDING") && !i.data.quoteDeadline().isAfter(now))
                    .limit(limit).map(this::row).toList();
        }

        @Override
        public List<InquiryRow> lockLapsedQuotes(OffsetDateTime now, int limit) {
            return inquiries.stream().filter(i -> i.status.equals("QUOTED")
                            && !findQuote(i.id, i.quoteVersion).validUntil().isAfter(now))
                    .limit(limit).map(this::row).toList();
        }

        @Override
        public UUID insertQuote(UUID inquiryId, int quoteVersion, long dailyRentMinor, int days, int quantity,
                                long rentMinor, long totalMinor, boolean scheduleAvailable, OffsetDateTime validUntil,
                                String terms, UUID quotedBy, OffsetDateTime at) {
            if (rentMinor != dailyRentMinor * days * quantity || totalMinor < rentMinor || !validUntil.isAfter(at)
                    || quotes.stream().anyMatch(q -> q.row.inquiryId().equals(inquiryId)
                            && q.row.quoteVersion() == quoteVersion)) {
                throw new IllegalStateException("rental_quote check");
            }
            Quote q = new Quote();
            q.row = new QuoteRow(UUID.randomUUID(), inquiryId, quoteVersion, dailyRentMinor, days, quantity,
                    rentMinor, totalMinor, scheduleAvailable, validUntil, terms, at);
            quotes.add(q);
            return q.row.id();
        }

        @Override
        public int insertFee(UUID quoteId, String name, String note, long amountMinor, int sortOrder) {
            fees.add(new FeeRow(quoteId, name, note, amountMinor));
            return 1;
        }

        @Override
        public QuoteRow findQuote(UUID inquiryId, int quoteVersion) {
            return quotes.stream().map(q -> q.row)
                    .filter(q -> q.inquiryId().equals(inquiryId) && q.quoteVersion() == quoteVersion)
                    .findFirst().orElse(null);
        }

        @Override
        public List<QuoteRow> findCurrentQuotes(List<UUID> inquiryIds) {
            requireIds(inquiryIds);
            return inquiryIds.stream().map(this::inquiry).filter(i -> i != null && i.quoteVersion != null)
                    .map(i -> findQuote(i.id, i.quoteVersion)).toList();
        }

        @Override
        public List<FeeRow> findFees(List<UUID> quoteIds) {
            requireIds(quoteIds);
            return fees.stream().filter(fee -> quoteIds.contains(fee.quoteId())).toList();
        }

        @Override
        public List<OrderSummaryRow> findOrderSummaries(List<UUID> inquiryIds) {
            requireIds(inquiryIds);
            return orders.stream().filter(o -> inquiryIds.contains(o.inquiryId))
                    .map(o -> new OrderSummaryRow(o.inquiryId, o.id, o.status, o.tradeOrderId)).toList();
        }

        @Override
        public int insertOrder(UUID id, UUID inquiryId, UUID quoteId, UUID accountId, UUID merchantId,
                               UUID tradeOrderId, long totalMinor, OffsetDateTime payBefore, OffsetDateTime at) {
            if (orders.stream().anyMatch(o -> o.inquiryId.equals(inquiryId) || o.tradeOrderId.equals(tradeOrderId))) {
                throw new IllegalStateException("UNIQUE rental_order");
            }
            Order o = new Order();
            o.id = id;
            o.inquiryId = inquiryId;
            o.quoteId = quoteId;
            o.accountId = accountId;
            o.merchantId = merchantId;
            o.tradeOrderId = tradeOrderId;
            o.total = totalMinor;
            o.payBefore = payBefore;
            o.createdAt = at;
            o.updatedAt = at;
            orders.add(o);
            return 1;
        }

        @Override
        public int insertContract(UUID orderId, String contractNo, UUID inquiryId, UUID quoteId, OffsetDateTime at) {
            InquiryRow i = row(inquiry(inquiryId));
            QuoteRow q = quotes.stream().map(x -> x.row).filter(x -> x.id().equals(quoteId)).findFirst().orElseThrow();
            if (contracts.stream().anyMatch(c -> c.contractNo().equals(contractNo))) {
                throw new IllegalStateException("UNIQUE contract_no");
            }
            contracts.add(new ContractRow(orderId, contractNo, q.id(), q.quoteVersion(), i.lesseeKind(),
                    i.lesseeOrganizationName(), i.lesseeContactName(), i.merchantId(), i.merchantName(),
                    i.productName(), i.skuName(), q.quantity(), i.startDate(), i.startDate().plusDays(q.days() - 1L),
                    q.days(), q.dailyRentMinor(), q.rentMinor(), q.totalMinor(), i.handoverMode(), i.returnMode(),
                    i.recipientName(), i.recipientPhone(), i.provinceName(), i.cityCode(), i.cityName(),
                    i.addressDetail(), q.terms(), at));
            return 1;
        }

        private OrderRow orderRow(Order o) {
            return new OrderRow(o.id, o.inquiryId, o.quoteId, o.accountId, o.merchantId, o.tradeOrderId, o.status,
                    o.total, o.payBefore, o.paidAt, o.closedAt, o.startedAt, o.endedAt, o.cancelledAt,
                    o.cancelReason, o.refundId, o.createdAt, o.updatedAt, o.version);
        }

        @Override
        public OrderRow findOrder(UUID accountId, UUID orderId) {
            Order o = order(orderId);
            return o == null || !o.accountId.equals(accountId) ? null : orderRow(o);
        }

        @Override
        public OrderRow lockOrder(UUID accountId, UUID orderId) {
            return findOrder(accountId, orderId);
        }

        @Override
        public OrderRow findMerchantOrder(UUID merchantId, UUID orderId) {
            Order o = order(orderId);
            return o == null || !o.merchantId.equals(merchantId) ? null : orderRow(o);
        }

        @Override
        public OrderRow lockMerchantOrder(UUID merchantId, UUID orderId) {
            return findMerchantOrder(merchantId, orderId);
        }

        @Override
        public OrderRow lockPaidOrder(UUID orderId, UUID tradeOrderId) {
            Order o = order(orderId);
            return o == null || !o.tradeOrderId.equals(tradeOrderId) ? null : orderRow(o);
        }

        @Override
        public List<OrderRow> findOrdersOfInquiries(List<UUID> inquiryIds) {
            requireIds(inquiryIds);
            return orders.stream().filter(o -> inquiryIds.contains(o.inquiryId)).map(this::orderRow).toList();
        }

        private List<OrderRow> orderPage(Predicate<Order> owner, String status, int limit, int offset) {
            return orders.stream().filter(owner).filter(o -> status == null || o.status.equals(status))
                    .map(this::orderRow).skip(offset).limit(limit).toList();
        }

        @Override
        public long countOrders(UUID accountId, String status) {
            return orderPage(o -> o.accountId.equals(accountId), status, Integer.MAX_VALUE, 0).size();
        }

        @Override
        public List<OrderRow> findOrderPage(UUID accountId, String status, int limit, int offset) {
            return orderPage(o -> o.accountId.equals(accountId), status, limit, offset);
        }

        @Override
        public long countMerchantOrders(UUID merchantId, String status) {
            return orderPage(o -> o.merchantId.equals(merchantId), status, Integer.MAX_VALUE, 0).size();
        }

        @Override
        public List<OrderRow> findMerchantOrderPage(UUID merchantId, String status, int limit, int offset) {
            return orderPage(o -> o.merchantId.equals(merchantId), status, limit, offset);
        }

        private int moveOrder(UUID id, Set<String> from, java.util.function.Consumer<Order> change, OffsetDateTime at) {
            Order o = order(id);
            if (o == null || !from.contains(o.status)) {
                return 0;
            }
            change.accept(o);
            o.version++;
            o.updatedAt = at;
            return 1;
        }

        @Override
        public int markPaid(UUID orderId, OffsetDateTime at) {
            return moveOrder(orderId, Set.of("PENDING_PAYMENT"), o -> {
                o.status = "AWAITING_HANDOVER";
                o.paidAt = at;
            }, at);
        }

        @Override
        public int closeUnpaid(UUID orderId, OffsetDateTime at) {
            return moveOrder(orderId, Set.of("PENDING_PAYMENT"), o -> {
                o.status = "CLOSED";
                o.closedAt = at;
            }, at);
        }

        @Override
        public int moveOrder(UUID orderId, String fromStatus, String toStatus, OffsetDateTime at) {
            return moveOrder(orderId, Set.of(fromStatus), o -> {
                o.status = toStatus;
                if (toStatus.equals("IN_USE")) {
                    o.startedAt = at;
                }
                if (toStatus.equals("ENDED")) {
                    o.endedAt = at;
                }
            }, at);
        }

        @Override
        public int cancelOrder(UUID orderId, String reason, UUID refundId, OffsetDateTime at) {
            return moveOrder(orderId, Set.of("AWAITING_HANDOVER", "HANDOVER_RECORDED"), o -> {
                o.status = "CANCELLED";
                o.cancelledAt = at;
                o.cancelReason = reason;
                o.refundId = refundId;
            }, at);
        }

        @Override
        public List<ContractRow> findContracts(List<UUID> orderIds) {
            requireIds(orderIds);
            return contracts.stream().filter(c -> orderIds.contains(c.orderId())).toList();
        }

        @Override
        public int saveRecord(UUID orderId, String kind, OffsetDateTime occurredAt, String note, UUID recordedBy,
                              OffsetDateTime at) {
            Rec existing = records.stream().filter(r -> r.orderId.equals(orderId) && r.kind.equals(kind))
                    .findFirst().orElse(null);
            if (existing == null) {
                existing = new Rec();
                existing.orderId = orderId;
                existing.kind = kind;
                records.add(existing);
            } else if (existing.confirmedAt != null) {
                return 0;
            }
            existing.occurredAt = occurredAt;
            existing.note = note;
            existing.recordedAt = at;
            return 1;
        }

        @Override
        public int confirmRecord(UUID orderId, String kind, UUID confirmedBy, OffsetDateTime at) {
            for (Rec r : records) {
                if (r.orderId.equals(orderId) && r.kind.equals(kind) && r.confirmedAt == null) {
                    r.confirmedAt = at;
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public List<RecordRow> findRecords(List<UUID> orderIds) {
            requireIds(orderIds);
            return records.stream().filter(r -> orderIds.contains(r.orderId))
                    .map(r -> new RecordRow(r.orderId, r.kind, r.occurredAt, r.note, r.recordedAt, r.confirmedAt))
                    .toList();
        }

        @Override
        public int insertEvent(UUID inquiryId, UUID orderId, String eventType, UUID actorId, Integer quoteVersion,
                               OffsetDateTime at) {
            Set<String> withOrder = Set.of("PAYMENT_CONFIRMED", "PAYMENT_CLOSED", "LATE_PAYMENT_REFUSED",
                    "HANDOVER_RECORDED", "HANDOVER_CONFIRMED", "RETURN_RECORDED", "RETURN_CONFIRMED", "CANCELLED",
                    "CONFIRMED");
            Set<String> withoutActor = Set.of("CLOSED_NO_QUOTE", "QUOTE_EXPIRED", "PAYMENT_CONFIRMED",
                    "PAYMENT_CLOSED", "LATE_PAYMENT_REFUSED");
            if (withOrder.contains(eventType) != (orderId != null)
                    || (actorId == null && !withoutActor.contains(eventType))) {
                throw new IllegalStateException("rental_event check " + eventType);
            }
            events.add(new Event(eventType, actorId));
            return 1;
        }

        private static void requireIds(List<UUID> ids) {
            if (ids.isEmpty()) {
                throw new IllegalStateException("IN () is not valid SQL");
            }
        }

        static final class Inquiry {
            UUID id;
            UUID accountId;
            UUID key;
            String hash;
            InquiryRow data;
            String status = "PENDING";
            String closeReason;
            String declineReason;
            Integer quoteVersion;
            OffsetDateTime updatedAt;
            int version;
        }

        static final class Quote {
            QuoteRow row;
        }

        static final class Order {
            UUID id;
            UUID inquiryId;
            UUID quoteId;
            UUID accountId;
            UUID merchantId;
            UUID tradeOrderId;
            String status = "PENDING_PAYMENT";
            long total;
            OffsetDateTime payBefore;
            OffsetDateTime paidAt;
            OffsetDateTime closedAt;
            OffsetDateTime startedAt;
            OffsetDateTime endedAt;
            OffsetDateTime cancelledAt;
            String cancelReason;
            UUID refundId;
            OffsetDateTime createdAt;
            OffsetDateTime updatedAt;
            int version;
        }

        static final class Rec {
            UUID orderId;
            String kind;
            OffsetDateTime occurredAt;
            String note;
            OffsetDateTime recordedAt;
            OffsetDateTime confirmedAt;
        }

        record Event(String type, UUID actor) {
        }
    }
}
