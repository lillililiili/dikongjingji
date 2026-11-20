package com.lowaltitude.inspection.mall.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import com.lowaltitude.inspection.mall.application.MallInquiryException.Error;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallInquiryMapper;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * 询价商品的规则测试（F4，§25.1）。mapper 换成内存实现（按 SQL 的条件和 V003 的约束写），商家收款换成替身。
 * 时钟固定；换时间时用同一份数据另起一个服务。SQL 文本由 MallInquiryMapperContractTest 检查，真实库上由 inquiry.cjs 验证。
 */
class MallInquiryServiceTest {

    /** 北京时间 2026-11-19 10:00。 */
    private static final Instant NOW = Instant.parse("2026-11-19T02:00:00Z");
    private static final OffsetDateTime NOW_UTC = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("11111111-1111-1111-1111-222222222222");
    private static final UUID CUSTOMER_ORG = UUID.fromString("11111111-1111-1111-1111-aaaaaaaaaaaa");
    private static final UUID PRODUCT = UUID.fromString("c2000000-0000-4000-8000-000000000203");
    private static final UUID OTHER_PRODUCT = UUID.fromString("c2000000-0000-4000-8000-000000000101");
    private static final UUID ROBOT = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID AGENT = UUID.fromString("c1000000-0000-4000-8000-000000000005");
    private static final UUID MAKER = UUID.fromString("c1000000-0000-4000-8000-000000000004");
    private static final UUID STORE = UUID.fromString("c1000000-0000-4000-8000-000000000003");
    private static final UUID ROBOT_OFFER = UUID.fromString("a2030000-0000-4000-8000-000000000001");
    private static final UUID AGENT_OFFER = UUID.fromString("a2030000-0000-4000-8000-000000000005");
    private static final UUID MAKER_OFFER = UUID.fromString("a2030000-0000-4000-8000-000000000004");
    private static final UUID JINAN_OFFER = UUID.fromString("a2030000-0000-4000-8000-000000000003");
    private static final UUID BUY_OFFER = UUID.fromString("a1010000-0000-4000-8000-000000000001");
    private static final UUID SECOND_ROBOT_OFFER = UUID.fromString("a2030000-0000-4000-8000-0000000000a1");
    private static final UUID OPERATOR = UUID.fromString("c1000000-0000-4000-8000-0000000000a1");
    private static final UUID ORDER = UUID.fromString("d0000000-0000-4000-8000-000000000001");
    private static final MallInquiryBuyer COMPANY = new MallInquiryBuyer(CUSTOMER_ORG, "东营港务物流", "王女士");

    private final InMemoryMapper mapper = new InMemoryMapper();
    private final MallInquiryService service = at(NOW);

    {
        mapper.offer(ROBOT_OFFER, PRODUCT, "INQUIRY", "370500", ROBOT, "示例机器人商家", "FACTORY_DIRECT");
        mapper.offer(AGENT_OFFER, PRODUCT, "INQUIRY", "370500", AGENT, "示例行业装备代理", "AUTHORIZED_DEALER");
        mapper.offer(MAKER_OFFER, PRODUCT, "INQUIRY", null, MAKER, "示例无人机厂家", "FACTORY_DIRECT");
        mapper.offer(JINAN_OFFER, PRODUCT, "INQUIRY", "370100", STORE, "示例科创器材店", "LOCAL_STORE");
        mapper.offer(BUY_OFFER, OTHER_PRODUCT, "BUY", "370500", ROBOT, "示例机器人商家", "FACTORY_DIRECT");
        mapper.offer(SECOND_ROBOT_OFFER, PRODUCT, "INQUIRY", "370500", ROBOT, "示例机器人商家", "FACTORY_DIRECT");
    }

    // ---- 询价供给和询价单 ----

    @Test
    void offersInTheCityTellWhichMerchantsCanBeAsked() {
        MallInquiryOfferListView offers = service.offers(PRODUCT, "370500");

        assertThat(offers.items()).extracting(MallInquiryOfferView::offerId, MallInquiryOfferView::canInquire)
                .containsExactlyInAnyOrder(tuple(ROBOT_OFFER, true), tuple(AGENT_OFFER, true), tuple(MAKER_OFFER, false),
                        tuple(SECOND_ROBOT_OFFER, true));
        assertThat(service.offers(PRODUCT, "370100").items()).extracting(MallInquiryOfferView::offerId)
                .containsExactlyInAnyOrder(MAKER_OFFER, JINAN_OFFER);
        assertInvalid(() -> service.offers(PRODUCT, "dongying"));
    }

    @Test
    void anInquiryIsSentToOneToThreeMerchantsAndHasSeventyTwoHours() {
        MallInquiryCreated created = submit(UUID.randomUUID(), List.of(ROBOT_OFFER, AGENT_OFFER));
        MallInquiryView view = created.inquiry();

        assertThat(created.created()).isTrue();
        assertThat(view.status()).isEqualTo(MallInquiryStatus.OPEN);
        assertThat(view.buyer()).isEqualTo(new MallInquiryBuyerView(MallInquiryBuyerKind.ORGANIZATION, "东营港务物流", "王女士"));
        assertThat(view.product().name()).isEqualTo("行业测绘无人机");
        assertThat(view.requirement()).isEqualTo("测绘版 · RTK\n含培训");
        assertThat(view.quantity()).isEqualTo(2);
        assertThat(view.expectedDate()).isEqualTo(LocalDate.parse("2026-12-15"));
        assertThat(view.merchants()).extracting(merchant -> merchant.merchant().name(), MallInquiryMerchantView::status)
                .containsExactly(tuple("示例机器人商家", MallInquiryInvitationStatus.INVITED),
                        tuple("示例行业装备代理", MallInquiryInvitationStatus.INVITED));
        assertThat(view.quoteDeadline()).isEqualTo(NOW_UTC.plusHours(72));
        assertThat(view.quotingOpen()).isTrue();
        assertThat(view.closable()).isTrue();
        assertThat(view.chosen()).isNull();
        // 个人身份。
        MallInquiryView personal = service.submit(ACCOUNT, new MallInquiryBuyer(null, null, "王女士"), UUID.randomUUID(),
                draft(List.of(ROBOT_OFFER))).inquiry();
        assertThat(personal.buyer().kind()).isEqualTo(MallInquiryBuyerKind.PERSONAL);
    }

    @Test
    void onlyInquiryOffersOfThisProductInTheCityFromCollectingMerchantsCanBeAsked() {
        assertRejected(Error.OFFER_UNAVAILABLE, () -> submit(UUID.randomUUID(), List.of(BUY_OFFER)));
        assertRejected(Error.OFFER_UNAVAILABLE, () -> submit(UUID.randomUUID(), List.of(JINAN_OFFER)));
        assertRejected(Error.OFFER_UNAVAILABLE, () -> submit(UUID.randomUUID(), List.of(UUID.randomUUID())));
        assertRejected(Error.MERCHANT_UNAVAILABLE, () -> submit(UUID.randomUUID(), List.of(ROBOT_OFFER, MAKER_OFFER)));
        assertInvalid(() -> submit(UUID.randomUUID(), List.of(ROBOT_OFFER, SECOND_ROBOT_OFFER)));
        assertInvalid(() -> submit(UUID.randomUUID(), List.of()));
        assertInvalid(() -> submit(UUID.randomUUID(), List.of(ROBOT_OFFER, ROBOT_OFFER)));
        assertInvalid(() -> submit(UUID.randomUUID(), List.of(ROBOT_OFFER, AGENT_OFFER, MAKER_OFFER, JINAN_OFFER)));
        assertInvalid(() -> service.submit(ACCOUNT, COMPANY, UUID.randomUUID(),
                new MallInquiryDraft(PRODUCT, " ", 2, "370500", null, List.of(ROBOT_OFFER))));
        assertInvalid(() -> service.submit(ACCOUNT, COMPANY, UUID.randomUUID(),
                new MallInquiryDraft(PRODUCT, "说".repeat(201), 2, "370500", null, List.of(ROBOT_OFFER))));
        assertInvalid(() -> service.submit(ACCOUNT, COMPANY, UUID.randomUUID(),
                new MallInquiryDraft(PRODUCT, "配置", 100, "370500", null, List.of(ROBOT_OFFER))));
        assertInvalid(() -> service.submit(ACCOUNT, COMPANY, UUID.randomUUID(),
                new MallInquiryDraft(PRODUCT, "配置", 2, "370500", "2026-11-19", List.of(ROBOT_OFFER))));
        assertThat(mapper.inquiries).isEmpty();
    }

    @Test
    void theSameKeyReplaysAndAnotherRequestIsRefused() {
        UUID key = UUID.randomUUID();
        MallInquiryView first = submit(key, List.of(ROBOT_OFFER)).inquiry();

        assertThat(submit(key, List.of(ROBOT_OFFER)).created()).isFalse();
        assertThat(submit(key, List.of(ROBOT_OFFER)).inquiry()).isEqualTo(first);
        assertRejected(Error.IDEMPOTENCY_CONFLICT, () -> submit(key, List.of(AGENT_OFFER)));
        assertThat(mapper.inquiries).hasSize(1);
    }

    // ---- 报价 ----

    @Test
    void merchantsQuoteReviseAndNeverSeeTheBuyer() {
        UUID id = submit(UUID.randomUUID(), List.of(ROBOT_OFFER, AGENT_OFFER)).inquiry().inquiryId();

        MallMerchantInquiryView quoted = quote(ROBOT, id, 2980000, 7);
        assertThat(quoted.myQuote().version()).isEqualTo(1);
        assertThat(quoted.myQuote().totalMinor()).isEqualTo(5960000);
        assertThat(quoted.invitationStatus()).isEqualTo(MallInquiryInvitationStatus.QUOTED);
        assertThat(service.read(ACCOUNT, id).status()).isEqualTo(MallInquiryStatus.QUOTED);
        // 一样的内容再报一次没有变化；改价是新的一版，旧版不再对外。
        assertThat(quote(ROBOT, id, 2980000, 7).myQuote().version()).isEqualTo(1);
        MallMerchantInquiryView revised = quote(ROBOT, id, 2880000, 12);
        assertThat(revised.myQuote().version()).isEqualTo(2);
        assertThat(mapper.quotes).filteredOn(quote -> quote.merchantId.equals(ROBOT)).extracting(quote -> quote.status)
                .containsExactly("SUPERSEDED", "ACTIVE");
        // 商家看到的形状里没有客户的身份。
        assertThat(java.util.Arrays.stream(MallMerchantInquiryView.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)).doesNotContain("buyer", "accountId");
        // 没被邀请的商家看不到。
        assertRejected(Error.NOT_FOUND, () -> service.merchantInquiry(MAKER, id));
        assertRejected(Error.NOT_FOUND, () -> quote(MAKER, id, 100, 1));
        assertThat(service.merchantInquiries(AGENT, null, 1, 20).total()).isEqualTo(1);
        assertThat(service.merchantInquiries(MAKER, null, 1, 20).total()).isZero();
        assertThat(mapper.events).extracting(InMemoryMapper.Event::type).containsExactly("SUBMITTED", "QUOTED",
                "QUOTE_REVISED");
    }

    @Test
    void quotesAreValidatedAndOnlyAcceptedBeforeTheDeadline() {
        UUID id = submit(UUID.randomUUID(), List.of(ROBOT_OFFER)).inquiry().inquiryId();

        assertInvalid(() -> service.quote(ROBOT, OPERATOR, id, new MallInquiryQuoteDraft(0L, "型号", 7, null,
                NOW_UTC.plusDays(3))));
        assertInvalid(() -> service.quote(ROBOT, OPERATOR, id, new MallInquiryQuoteDraft(100L, " ", 7, null,
                NOW_UTC.plusDays(3))));
        assertInvalid(() -> service.quote(ROBOT, OPERATOR, id, new MallInquiryQuoteDraft(100L, "型".repeat(61), 7, null,
                NOW_UTC.plusDays(3))));
        assertInvalid(() -> service.quote(ROBOT, OPERATOR, id, new MallInquiryQuoteDraft(100L, "型号", 366, null,
                NOW_UTC.plusDays(3))));
        assertInvalid(() -> service.quote(ROBOT, OPERATOR, id, new MallInquiryQuoteDraft(100L, "型号", 7, "含".repeat(121),
                NOW_UTC.plusDays(3))));
        assertInvalid(() -> service.quote(ROBOT, OPERATOR, id, new MallInquiryQuoteDraft(100L, "型号", 7, null,
                NOW_UTC.plusDays(7).plusSeconds(1))));
        assertInvalid(() -> service.quote(ROBOT, OPERATOR, id, new MallInquiryQuoteDraft(100L, "型号", 7, null, NOW_UTC)));
        assertThat(mapper.quotes).isEmpty();
        MallInquiryService late = at(NOW.plusSeconds(72 * 3600));
        assertRejected(Error.QUOTING_CLOSED, () -> late.quote(ROBOT, OPERATOR, id,
                new MallInquiryQuoteDraft(100L, "型号", 7, null, NOW_UTC.plusDays(4))));
    }

    @Test
    void aDeclineWithdrawsTheQuoteAndEndsThatMerchant() {
        UUID id = submit(UUID.randomUUID(), List.of(ROBOT_OFFER, AGENT_OFFER)).inquiry().inquiryId();
        quote(AGENT, id, 3150000, 10);

        MallMerchantInquiryView declined = service.decline(AGENT, OPERATOR, id, " 缺货 ");

        assertThat(declined.invitationStatus()).isEqualTo(MallInquiryInvitationStatus.DECLINED);
        assertThat(declined.declineReason()).isEqualTo("缺货");
        assertThat(declined.myQuote().status()).isEqualTo(MallInquiryQuoteStatus.WITHDRAWN);
        assertThat(declined.quotingOpen()).isFalse();
        assertThat(service.decline(AGENT, OPERATOR, id, "再拒绝一次")).isEqualTo(declined);
        assertRejected(Error.STATE_INVALID, () -> quote(AGENT, id, 3000000, 10));
        // 没有有效报价了：回到待报价；客户看不到撤回的报价。
        MallInquiryDetailView detail = service.detail(ACCOUNT, id, null);
        assertThat(detail.inquiry().status()).isEqualTo(MallInquiryStatus.OPEN);
        assertThat(detail.quotes()).isEmpty();
        assertThat(detail.inquiry().merchants()).extracting(MallInquiryMerchantView::status)
                .containsExactly(MallInquiryInvitationStatus.INVITED, MallInquiryInvitationStatus.DECLINED);
        assertInvalid(() -> service.decline(ROBOT, OPERATOR, id, null));
    }

    // ---- 比选和选定 ----

    @Test
    void quotesAreComparedByTimePriceOrLeadTime() {
        UUID id = submit(UUID.randomUUID(), List.of(ROBOT_OFFER, AGENT_OFFER)).inquiry().inquiryId();
        quote(ROBOT, id, 2980000, 12);
        at(NOW.plusSeconds(60)).quote(AGENT, OPERATOR, id, quoteDraft(2880000, 7, NOW_UTC.plusDays(3)));

        assertThat(service.detail(ACCOUNT, id, null).quotes()).extracting(quote -> quote.merchant().organizationId())
                .containsExactly(ROBOT, AGENT);
        assertThat(service.detail(ACCOUNT, id, "PRICE").quotes()).extracting(quote -> quote.merchant().organizationId())
                .containsExactly(AGENT, ROBOT);
        MallInquiryDetailView byLead = service.detail(ACCOUNT, id, "LEAD_TIME");
        assertThat(byLead.quotes()).extracting(quote -> quote.merchant().organizationId()).containsExactly(AGENT, ROBOT);
        // 报价里的商家带出资质是否审核过（经身份模块的端口，一次问齐这张询价单报了价的商家）。
        assertThat(byLead.quotes()).extracting(quote -> quote.merchant().qualificationReviewed())
                .containsExactly(false, true);
        assertThat(qualificationQueries.getLast()).containsExactlyInAnyOrder(ROBOT, AGENT);
        assertThat(byLead.sort()).isEqualTo(MallInquirySort.LEAD_TIME);
        assertThat(byLead.quotes()).allSatisfy(quote -> assertThat(quote.choosable()).isTrue());
        assertThat(byLead.inquiry().quoteCount()).isEqualTo(2);
        assertInvalid(() -> service.detail(ACCOUNT, id, "RATING"));
        assertRejected(Error.NOT_FOUND, () -> service.detail(OTHER, id, null));
        // 过了有效期的读成已过期，不能选。
        assertThat(at(NOW.plusSeconds(4 * 86400)).detail(ACCOUNT, id, null).quotes())
                .allSatisfy(quote -> {
                    assertThat(quote.status()).isEqualTo(MallInquiryQuoteStatus.EXPIRED);
                    assertThat(quote.choosable()).isFalse();
                });
    }

    @Test
    void choosingSettlesTheInquiryAndLapsesTheOtherQuotes() {
        UUID id = submit(UUID.randomUUID(), List.of(ROBOT_OFFER, AGENT_OFFER)).inquiry().inquiryId();
        UUID v1 = quote(ROBOT, id, 2980000, 12).myQuote().quoteId();
        UUID v2 = quote(ROBOT, id, 2880000, 12).myQuote().quoteId();
        quote(AGENT, id, 3150000, 7);
        int version = service.read(ACCOUNT, id).version();

        assertRejected(Error.VERSION_CONFLICT, () -> service.choose(ACCOUNT, id, version - 1, v2, 2));
        assertRejected(Error.QUOTE_CHANGED, () -> service.choose(ACCOUNT, id, version, v1, 1));
        assertRejected(Error.QUOTE_CHANGED, () -> service.choose(ACCOUNT, id, version, v2, 1));
        assertRejected(Error.NOT_FOUND, () -> service.choose(ACCOUNT, id, version, UUID.randomUUID(), 1));
        assertRejected(Error.NOT_FOUND, () -> service.choose(OTHER, id, version, v2, 2));
        assertInvalid(() -> service.choose(ACCOUNT, id, version, v2, null));

        MallInquiryView chosen = service.choose(ACCOUNT, id, version, v2, 2);

        assertThat(chosen.status()).isEqualTo(MallInquiryStatus.CHOSEN);
        assertThat(chosen.chosen().quoteId()).isEqualTo(v2);
        assertThat(chosen.chosen().quoteVersion()).isEqualTo(2);
        assertThat(chosen.chosen().merchant().name()).isEqualTo("示例机器人商家");
        assertThat(chosen.chosen().totalMinor()).isEqualTo(5760000);
        assertThat(chosen.chosen().orderId()).isNull();
        assertThat(chosen.closable()).isFalse();
        assertThat(service.choose(ACCOUNT, id, version, v2, 2)).isEqualTo(chosen);
        assertThat(service.merchantInquiry(AGENT, id).myQuote().status()).isEqualTo(MallInquiryQuoteStatus.LAPSED);
        assertThat(service.detail(ACCOUNT, id, null).quotes()).noneMatch(MallInquiryQuoteView::choosable);
        assertRejected(Error.STATE_INVALID, () -> quote(AGENT, id, 3000000, 7));
        assertRejected(Error.STATE_INVALID, () -> service.decline(AGENT, OPERATOR, id, "缺货"));
        assertRejected(Error.STATE_INVALID, () -> service.close(ACCOUNT, id));
        UUID agentQuote = mapper.quotes.stream().filter(quote -> quote.merchantId.equals(AGENT)).findFirst().orElseThrow().id;
        assertRejected(Error.STATE_INVALID, () -> service.choose(ACCOUNT, id, chosen.version(), agentQuote, 1));
    }

    @Test
    void anExpiredQuoteCannotBeChosen() {
        UUID id = submit(UUID.randomUUID(), List.of(ROBOT_OFFER)).inquiry().inquiryId();
        UUID quoteId = quote(ROBOT, id, 2980000, 7).myQuote().quoteId();
        int version = service.read(ACCOUNT, id).version();

        assertRejected(Error.QUOTE_EXPIRED, () -> at(NOW.plusSeconds(3 * 86400)).choose(ACCOUNT, id, version, quoteId, 1));
        // 截止之后、报价还在有效期内：仍可以选。
        UUID late = submit(UUID.randomUUID(), List.of(AGENT_OFFER)).inquiry().inquiryId();
        UUID lateQuote = service.quote(AGENT, OPERATOR, late, quoteDraft(3000000, 7, NOW_UTC.plusDays(7))).myQuote().quoteId();
        int lateVersion = service.read(ACCOUNT, late).version();
        assertThat(at(NOW.plusSeconds(4 * 86400)).choose(ACCOUNT, late, lateVersion, lateQuote, 1).status())
                .isEqualTo(MallInquiryStatus.CHOSEN);
    }

    @Test
    void theCustomerClosesBeforeChoosing() {
        UUID id = submit(UUID.randomUUID(), List.of(ROBOT_OFFER)).inquiry().inquiryId();
        quote(ROBOT, id, 2980000, 7);

        MallInquiryView closed = service.close(ACCOUNT, id);

        assertThat(closed.status()).isEqualTo(MallInquiryStatus.CLOSED);
        assertThat(closed.closeReason()).isEqualTo(MallInquiryCloseReason.CUSTOMER);
        assertThat(service.close(ACCOUNT, id)).isEqualTo(closed);
        assertThat(service.merchantInquiry(ROBOT, id).myQuote().status()).isEqualTo(MallInquiryQuoteStatus.LAPSED);
        assertRejected(Error.STATE_INVALID, () -> quote(ROBOT, id, 2880000, 7));
        assertThat(service.list(ACCOUNT, "CLOSED", 1, 20).total()).isEqualTo(1);
        assertInvalid(() -> service.list(ACCOUNT, "LATER", 1, 20));
    }

    // ---- 下单 ----

    @Test
    void onlyTheChosenUnexpiredQuoteOfTheBuyerCanBeOrdered() {
        UUID id = submit(UUID.randomUUID(), List.of(ROBOT_OFFER, AGENT_OFFER)).inquiry().inquiryId();
        UUID robot = quote(ROBOT, id, 2880000, 12).myQuote().quoteId();
        UUID agent = quote(AGENT, id, 3150000, 7).myQuote().quoteId();
        assertRejected(Error.QUOTE_NOT_CHOSEN, () -> service.orderQuote(ACCOUNT, robot));
        service.choose(ACCOUNT, id, service.read(ACCOUNT, id).version(), robot, 1);

        MallInquiryOrderQuote quote = service.orderQuote(ACCOUNT, robot);

        assertThat(quote).isEqualTo(new MallInquiryOrderQuote(robot, ROBOT_OFFER, 2, 2880000, "测绘版 · RTK", null));
        assertRejected(Error.QUOTE_NOT_CHOSEN, () -> service.orderQuote(ACCOUNT, agent));
        assertRejected(Error.NOT_FOUND, () -> service.orderQuote(OTHER, robot));
        assertRejected(Error.QUOTE_EXPIRED, () -> at(NOW.plusSeconds(3 * 86400)).orderQuote(ACCOUNT, robot));
        // 下单后记下订单：询价单的选定摘要指向它，再取报价时带出上一张订单。
        service.recordOrder(ACCOUNT, robot, ORDER);
        assertThat(service.read(ACCOUNT, id).chosen().orderId()).isEqualTo(ORDER);
        assertThat(service.orderQuote(ACCOUNT, robot).lastOrderId()).isEqualTo(ORDER);
        assertThat(mapper.events).extracting(InMemoryMapper.Event::type).last().isEqualTo("ORDERED");
        assertRejected(Error.NOT_FOUND, () -> service.recordOrder(OTHER, robot, ORDER));
    }

    // ---- 定时任务 ----

    @Test
    void theJobExpiresQuotesAndClosesInquiriesWithoutLiveQuotesAfterTheDeadline() {
        UUID unquoted = submit(UUID.randomUUID(), List.of(ROBOT_OFFER)).inquiry().inquiryId();
        UUID lapsing = submit(UUID.randomUUID(), List.of(AGENT_OFFER)).inquiry().inquiryId();
        service.quote(AGENT, OPERATOR, lapsing, quoteDraft(3000000, 7, NOW_UTC.plusDays(2)));
        UUID live = submit(UUID.randomUUID(), List.of(ROBOT_OFFER)).inquiry().inquiryId();
        service.quote(ROBOT, OPERATOR, live, quoteDraft(2900000, 7, NOW_UTC.plusDays(7)));

        // 截止前报价过期：回到待报价。
        assertThat(at(NOW.plusSeconds(2 * 86400)).closeLapsed()).isZero();
        assertThat(service.read(ACCOUNT, lapsing).status()).isEqualTo(MallInquiryStatus.OPEN);
        assertThat(service.merchantInquiry(AGENT, lapsing).myQuote().status()).isEqualTo(MallInquiryQuoteStatus.EXPIRED);
        // 截止：一份都没报过的 NO_QUOTES，报过但都失效的 QUOTES_LAPSED；还有有效报价的不动。
        assertThat(at(NOW.plusSeconds(72 * 3600)).closeLapsed()).isEqualTo(2);
        assertThat(service.read(ACCOUNT, unquoted).closeReason()).isEqualTo(MallInquiryCloseReason.NO_QUOTES);
        assertThat(service.read(ACCOUNT, lapsing).closeReason()).isEqualTo(MallInquiryCloseReason.QUOTES_LAPSED);
        assertThat(service.read(ACCOUNT, live).status()).isEqualTo(MallInquiryStatus.QUOTED);
        assertThat(mapper.events).filteredOn(event -> event.actor() == null).extracting(InMemoryMapper.Event::type)
                .containsExactlyInAnyOrder("QUOTE_EXPIRED", "CLOSED_NO_QUOTES", "CLOSED_QUOTES_LAPSED");
        assertThat(at(NOW.plusSeconds(73 * 3600)).closeLapsed()).isZero();
        // 截止之后、报价过期了也还没选：关闭。
        assertThat(at(NOW.plusSeconds(7 * 86400)).closeLapsed()).isEqualTo(1);
        assertThat(service.read(ACCOUNT, live).closeReason()).isEqualTo(MallInquiryCloseReason.QUOTES_LAPSED);
    }

    // ---- 辅助 ----

    private MallInquiryService at(Instant instant) {
        return new MallInquiryService(mapper, merchantId -> !merchantId.equals(MAKER), this::reviewed,
                Clock.fixed(instant, ZoneOffset.UTC));
    }

    // R6-4：只有示例机器人商家审核过资质；记下每次问了哪些商家。
    private final List<List<UUID>> qualificationQueries = new java.util.ArrayList<>();

    private java.util.Set<UUID> reviewed(java.util.Collection<UUID> merchants) {
        qualificationQueries.add(List.copyOf(merchants));
        return merchants.contains(ROBOT) ? java.util.Set.of(ROBOT) : java.util.Set.of();
    }

    private MallInquiryCreated submit(UUID key, List<UUID> offers) {
        return service.submit(ACCOUNT, COMPANY, key, draft(offers));
    }

    private static MallInquiryDraft draft(List<UUID> offers) {
        return new MallInquiryDraft(PRODUCT, " 测绘版 · RTK\n含培训 ", 2, "370500", "2026-12-15", offers);
    }

    private MallMerchantInquiryView quote(UUID merchant, UUID id, long unit, int lead) {
        return service.quote(merchant, OPERATOR, id, quoteDraft(unit, lead, NOW_UTC.plusDays(3)));
    }

    private static MallInquiryQuoteDraft quoteDraft(long unit, int lead, OffsetDateTime validUntil) {
        return new MallInquiryQuoteDraft(unit, "测绘版 · RTK", lead, "含 2 名飞手培训", validUntil);
    }

    private static void assertRejected(Error error, ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(MallInquiryException.class,
                exception -> assertThat(exception.error()).isEqualTo(error));
    }

    private static void assertInvalid(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(IllegalArgumentException.class);
    }

    /** 内存里的 mapper：条件按 MallInquiryMapper 的 SQL 写，V003 的约束在这里用异常代替。 */
    private static final class InMemoryMapper implements MallInquiryMapper {

        final List<OfferRow> offers = new ArrayList<>();
        final List<Inquiry> inquiries = new ArrayList<>();
        final List<Invitation> invitations = new ArrayList<>();
        final List<Quote> quotes = new ArrayList<>();
        final List<Event> events = new ArrayList<>();

        void offer(UUID id, UUID product, String saleMode, String city, UUID merchant, String name, String kind) {
            offers.add(new OfferRow(id, product, product.equals(PRODUCT) ? "行业测绘无人机" : "编程机器人", null, saleMode,
                    "PUBLISHED", "ACTIVE", "ACTIVE", city, merchant, name, kind, "按配置报价"));
        }

        private Inquiry inquiry(UUID id) {
            return inquiries.stream().filter(row -> row.id.equals(id)).findFirst().orElse(null);
        }

        @Override
        public List<OfferRow> findInquiryOffers(UUID productId, String cityCode) {
            return offers.stream().filter(offer -> offer.productId().equals(productId)
                            && offer.saleMode().equals("INQUIRY")
                            && (offer.cityCode() == null || offer.cityCode().equals(cityCode)))
                    .sorted(Comparator.comparing(OfferRow::merchantName)).toList();
        }

        @Override
        public List<OfferRow> findOffers(List<UUID> offerIds) {
            requireIds(offerIds);
            return offers.stream().filter(offer -> offerIds.contains(offer.offerId())).toList();
        }

        @Override
        public Integer lockAccount(UUID accountId) {
            return 1;
        }

        @Override
        public KeyRow findByKey(UUID accountId, UUID idempotencyKey) {
            return inquiries.stream().filter(row -> row.account.equals(accountId) && row.key.equals(idempotencyKey))
                    .map(row -> new KeyRow(row.id, row.hash)).findFirst().orElse(null);
        }

        @Override
        public int insertInquiry(UUID id, UUID accountId, String buyerKind, UUID buyerOrganizationId,
                                 String buyerOrganizationName, String buyerContactName, UUID productId,
                                 String productName, String coverUrl, String requirement, int quantity, String cityCode,
                                 LocalDate expectedDate, OffsetDateTime quoteDeadline, UUID idempotencyKey,
                                 String requestHash, OffsetDateTime at) {
            if (findByKey(accountId, idempotencyKey) != null || quantity < 1 || quantity > 99
                    || ("ORGANIZATION".equals(buyerKind)) != (buyerOrganizationId != null)) {
                throw new IllegalStateException("mall_inquiry check");
            }
            Inquiry row = new Inquiry();
            row.id = id;
            row.account = accountId;
            row.key = idempotencyKey;
            row.hash = requestHash;
            row.base = new InquiryRow(id, accountId, buyerKind, buyerOrganizationName, buyerContactName, productId,
                    productName, coverUrl, requirement, quantity, cityCode, expectedDate, "OPEN", null, quoteDeadline,
                    null, null, at, at, 0);
            row.updatedAt = at;
            inquiries.add(row);
            return 1;
        }

        @Override
        public int insertInvitation(UUID inquiryId, UUID offerId, UUID merchantId, String merchantName,
                                    String merchantKind, int sortOrder, OffsetDateTime at) {
            if (invitations.stream().anyMatch(v -> v.inquiryId.equals(inquiryId) && v.merchantId.equals(merchantId))) {
                throw new IllegalStateException("UNIQUE (inquiry_id, merchant_id)");
            }
            Invitation row = new Invitation();
            row.inquiryId = inquiryId;
            row.offerId = offerId;
            row.merchantId = merchantId;
            row.merchantName = merchantName;
            row.merchantKind = merchantKind;
            invitations.add(row);
            return 1;
        }

        private InquiryRow row(Inquiry i) {
            InquiryRow b = i.base;
            return new InquiryRow(b.id(), b.accountId(), b.buyerKind(), b.buyerOrganizationName(), b.buyerContactName(),
                    b.productId(), b.productName(), b.coverUrl(), b.requirement(), b.quantity(), b.cityCode(),
                    b.expectedDate(), i.status, i.closeReason, b.quoteDeadline(), i.chosenQuote, i.chosenAt,
                    b.createdAt(), i.updatedAt, i.version);
        }

        private InquiryRow find(UUID id, Predicate<Inquiry> visible) {
            Inquiry i = inquiry(id);
            return i == null || !visible.test(i) ? null : row(i);
        }

        private boolean invited(Inquiry i, UUID merchantId) {
            return invitations.stream().anyMatch(v -> v.inquiryId.equals(i.id) && v.merchantId.equals(merchantId));
        }

        @Override
        public InquiryRow findInquiry(UUID accountId, UUID inquiryId) {
            return find(inquiryId, i -> i.account.equals(accountId));
        }

        @Override
        public InquiryRow lockInquiry(UUID accountId, UUID inquiryId) {
            return findInquiry(accountId, inquiryId);
        }

        private List<InquiryRow> page(Predicate<Inquiry> filter, String status, int limit, int offset) {
            return inquiries.stream().filter(filter).filter(i -> status == null || i.status.equals(status))
                    .map(this::row).skip(offset).limit(limit).toList();
        }

        @Override
        public long countInquiries(UUID accountId, String status) {
            return page(i -> i.account.equals(accountId), status, Integer.MAX_VALUE, 0).size();
        }

        @Override
        public List<InquiryRow> findInquiryPage(UUID accountId, String status, int limit, int offset) {
            return page(i -> i.account.equals(accountId), status, limit, offset);
        }

        @Override
        public InquiryRow findMerchantInquiry(UUID merchantId, UUID inquiryId) {
            return find(inquiryId, i -> invited(i, merchantId));
        }

        @Override
        public InquiryRow lockMerchantInquiry(UUID merchantId, UUID inquiryId) {
            return findMerchantInquiry(merchantId, inquiryId);
        }

        @Override
        public long countMerchantInquiries(UUID merchantId, String status) {
            return page(i -> invited(i, merchantId), status, Integer.MAX_VALUE, 0).size();
        }

        @Override
        public List<InquiryRow> findMerchantInquiryPage(UUID merchantId, String status, int limit, int offset) {
            return page(i -> invited(i, merchantId), status, limit, offset);
        }

        private InvitationRow invitationRow(Invitation v) {
            return new InvitationRow(v.inquiryId, v.offerId, v.merchantId, v.merchantName, v.merchantKind, v.status,
                    v.reason);
        }

        @Override
        public List<InvitationRow> findInvitations(List<UUID> inquiryIds) {
            requireIds(inquiryIds);
            return invitations.stream().filter(v -> inquiryIds.contains(v.inquiryId)).map(this::invitationRow).toList();
        }

        @Override
        public InvitationRow lockInvitation(UUID inquiryId, UUID merchantId) {
            return invitations.stream().filter(v -> v.inquiryId.equals(inquiryId) && v.merchantId.equals(merchantId))
                    .map(this::invitationRow).findFirst().orElse(null);
        }

        @Override
        public int markInvitation(UUID inquiryId, UUID merchantId, String status, String reason, OffsetDateTime at) {
            for (Invitation v : invitations) {
                if (v.inquiryId.equals(inquiryId) && v.merchantId.equals(merchantId) && !v.status.equals("DECLINED")) {
                    if (status.equals("DECLINED") == (reason == null)) {
                        throw new IllegalStateException("ck_mall_inquiry_invitation_declined");
                    }
                    v.status = status;
                    v.reason = reason;
                    return 1;
                }
            }
            return 0;
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
        public int changeStatus(UUID inquiryId, String fromStatus, String toStatus, OffsetDateTime at) {
            return move(inquiryId, Set.of(fromStatus), i -> i.status = toStatus, at);
        }

        @Override
        public int choose(UUID inquiryId, UUID quoteId, int version, OffsetDateTime at) {
            Inquiry i = inquiry(inquiryId);
            if (i == null || i.version != version) {
                return 0;
            }
            return move(inquiryId, Set.of("OPEN", "QUOTED"), found -> {
                found.status = "CHOSEN";
                found.chosenQuote = quoteId;
                found.chosenAt = at;
            }, at);
        }

        @Override
        public int close(UUID inquiryId, String reason, OffsetDateTime at) {
            return move(inquiryId, Set.of("OPEN", "QUOTED"), i -> {
                i.status = "CLOSED";
                i.closeReason = reason;
            }, at);
        }

        @Override
        public List<InquiryRow> lockDue(OffsetDateTime now, int limit) {
            return inquiries.stream().filter(i -> (i.status.equals("OPEN") && !i.base.quoteDeadline().isAfter(now))
                            || (i.status.equals("QUOTED") && quotes.stream().anyMatch(q -> q.inquiryId.equals(i.id)
                                    && q.status.equals("ACTIVE") && !q.validUntil.isAfter(now))))
                    .limit(limit).map(this::row).toList();
        }

        private QuoteRow quoteRow(Quote q) {
            return new QuoteRow(q.id, q.inquiryId, q.offerId, q.merchantId, q.version, q.status, q.unit, q.quantity,
                    q.unit * q.quantity, q.model, q.lead, q.includes, q.validUntil, q.quotedAt, q.lastOrder);
        }

        @Override
        public List<QuoteRow> findQuotes(List<UUID> inquiryIds) {
            requireIds(inquiryIds);
            return quotes.stream().filter(q -> inquiryIds.contains(q.inquiryId) && !q.status.equals("SUPERSEDED"))
                    .sorted(Comparator.comparing((Quote q) -> q.quotedAt)).map(this::quoteRow).toList();
        }

        @Override
        public QuoteRow findQuote(UUID inquiryId, UUID quoteId) {
            return quotes.stream().filter(q -> q.id.equals(quoteId) && q.inquiryId.equals(inquiryId))
                    .map(this::quoteRow).findFirst().orElse(null);
        }

        @Override
        public QuoteRow lockLatestQuote(UUID inquiryId, UUID merchantId) {
            return quotes.stream().filter(q -> q.inquiryId.equals(inquiryId) && q.merchantId.equals(merchantId))
                    .max(Comparator.comparingInt(q -> q.version)).map(this::quoteRow).orElse(null);
        }

        @Override
        public int supersede(UUID inquiryId, UUID merchantId, OffsetDateTime at) {
            int changed = 0;
            for (Quote q : quotes) {
                if (q.inquiryId.equals(inquiryId) && q.merchantId.equals(merchantId)
                        && Set.of("ACTIVE", "EXPIRED").contains(q.status)) {
                    q.status = "SUPERSEDED";
                    changed++;
                }
            }
            return changed;
        }

        @Override
        public UUID insertQuote(UUID inquiryId, UUID offerId, UUID merchantId, int quoteVersion, long unitAmountMinor,
                                int quantity, long totalMinor, String model, int leadDays, String includes,
                                OffsetDateTime validUntil, UUID quotedBy, OffsetDateTime at) {
            if (totalMinor != unitAmountMinor * quantity || !validUntil.isAfter(at) || leadDays < 1 || leadDays > 365
                    || quotes.stream().anyMatch(q -> q.inquiryId.equals(inquiryId) && q.merchantId.equals(merchantId)
                            && Set.of("ACTIVE", "CHOSEN").contains(q.status))) {
                throw new IllegalStateException("mall_inquiry_quote check");
            }
            Quote q = new Quote();
            q.id = UUID.randomUUID();
            q.inquiryId = inquiryId;
            q.offerId = offerId;
            q.merchantId = merchantId;
            q.version = quoteVersion;
            q.unit = unitAmountMinor;
            q.quantity = quantity;
            q.model = model;
            q.lead = leadDays;
            q.includes = includes;
            q.validUntil = validUntil;
            q.quotedAt = at;
            quotes.add(q);
            return q.id;
        }

        private int setStatus(Predicate<Quote> which, String to) {
            int changed = 0;
            for (Quote q : quotes) {
                if (which.test(q)) {
                    q.status = to;
                    changed++;
                }
            }
            return changed;
        }

        @Override
        public int withdraw(UUID inquiryId, UUID merchantId, OffsetDateTime at) {
            return setStatus(q -> q.inquiryId.equals(inquiryId) && q.merchantId.equals(merchantId)
                    && q.status.equals("ACTIVE"), "WITHDRAWN");
        }

        @Override
        public int markChosen(UUID inquiryId, UUID quoteId, OffsetDateTime at) {
            return setStatus(q -> q.id.equals(quoteId) && q.inquiryId.equals(inquiryId) && q.status.equals("ACTIVE"),
                    "CHOSEN");
        }

        @Override
        public int lapseActive(UUID inquiryId, OffsetDateTime at) {
            return setStatus(q -> q.inquiryId.equals(inquiryId) && q.status.equals("ACTIVE"), "LAPSED");
        }

        @Override
        public List<UUID> expireQuotes(UUID inquiryId, OffsetDateTime now) {
            List<UUID> expired = new ArrayList<>();
            for (Quote q : quotes) {
                if (q.inquiryId.equals(inquiryId) && q.status.equals("ACTIVE") && !q.validUntil.isAfter(now)) {
                    q.status = "EXPIRED";
                    expired.add(q.id);
                }
            }
            return expired;
        }

        @Override
        public int countActiveQuotes(UUID inquiryId) {
            return (int) quotes.stream().filter(q -> q.inquiryId.equals(inquiryId) && q.status.equals("ACTIVE")).count();
        }

        @Override
        public int countQuotes(UUID inquiryId) {
            return (int) quotes.stream().filter(q -> q.inquiryId.equals(inquiryId)).count();
        }

        @Override
        public OrderQuoteRow findOrderQuote(UUID accountId, UUID quoteId) {
            return quotes.stream().filter(q -> q.id.equals(quoteId)).findFirst()
                    .map(q -> {
                        Inquiry i = inquiry(q.inquiryId);
                        return i.account.equals(accountId)
                                ? new OrderQuoteRow(q.id, q.offerId, q.status, q.unit, q.quantity, q.model, q.validUntil,
                                        q.lastOrder, i.id, i.status, i.chosenQuote)
                                : null;
                    }).orElse(null);
        }

        @Override
        public int recordOrder(UUID quoteId, UUID orderId, OffsetDateTime at) {
            for (Quote q : quotes) {
                if (q.id.equals(quoteId) && q.status.equals("CHOSEN")) {
                    q.lastOrder = orderId;
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public int insertEvent(UUID inquiryId, UUID quoteId, String eventType, UUID actorId, UUID orderId,
                               OffsetDateTime at) {
            if ((actorId == null && !Set.of("QUOTE_EXPIRED", "CLOSED_NO_QUOTES", "CLOSED_QUOTES_LAPSED").contains(eventType))
                    || eventType.equals("ORDERED") != (orderId != null)) {
                throw new IllegalStateException("mall_inquiry_event check " + eventType);
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
            UUID account;
            UUID key;
            String hash;
            InquiryRow base;
            String status = "OPEN";
            String closeReason;
            UUID chosenQuote;
            OffsetDateTime chosenAt;
            OffsetDateTime updatedAt;
            int version;
        }

        static final class Invitation {
            UUID inquiryId;
            UUID offerId;
            UUID merchantId;
            String merchantName;
            String merchantKind;
            String status = "INVITED";
            String reason;
        }

        static final class Quote {
            UUID id;
            UUID inquiryId;
            UUID offerId;
            UUID merchantId;
            int version;
            String status = "ACTIVE";
            long unit;
            int quantity;
            String model;
            int lead;
            String includes;
            OffsetDateTime validUntil;
            OffsetDateTime quotedAt;
            UUID lastOrder;
        }

        record Event(String type, UUID actor) {
        }
    }
}
