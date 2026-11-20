package com.lowaltitude.inspection.demand.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.demand.application.DemandException.Error;
import com.lowaltitude.inspection.demand.application.DemandPorts.Provider;
import com.lowaltitude.inspection.demand.infrastructure.persistence.DemandMapper;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * 需求发布与报价比选的规则测试（F1，§22.2）。mapper 换成内存实现（按 SQL 的条件和 V001 的约束写，违反约束时抛异常），
 * 城市目录、服务商名录、站内信换成替身。时钟固定；换时间时用同一份数据另起一个服务。
 * SQL 文本由 DemandMapperContractTest 检查。
 */
class DemandServiceTest {

    /** 北京时间 2026-11-19 10:00。 */
    private static final Instant NOW = Instant.parse("2026-11-19T02:00:00Z");
    private static final OffsetDateTime NOW_UTC = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final OffsetDateTime DEADLINE = NOW_UTC.plusHours(72);

    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_ACCOUNT = UUID.fromString("11111111-1111-1111-1111-222222222222");
    private static final UUID CUSTOMER_ORG = UUID.fromString("11111111-1111-1111-1111-aaaaaaaaaaaa");
    /** 云翼科技：东营、济南；鹰眼：东营；济南飞服：只有济南；无资料的机构。 */
    private static final UUID YUNYI = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID EAGLE = UUID.fromString("22222222-2222-2222-2222-333333333333");
    private static final UUID JINAN = UUID.fromString("22222222-2222-2222-2222-444444444444");
    private static final UUID NO_PROFILE = UUID.fromString("22222222-2222-2222-2222-555555555555");
    private static final UUID OPERATOR = UUID.fromString("22222222-2222-2222-2222-aaaaaaaaaaaa");
    private static final UUID KEY = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    private static final UUID SECOND_KEY = UUID.fromString("e0000000-0000-4000-8000-000000000002");
    private static final DemandPublisher COMPANY = new DemandPublisher(CUSTOMER_ORG, "东营示例物流公司");

    private final InMemoryMapper mapper = new InMemoryMapper();
    private final FakeProviders providers = new FakeProviders();
    private final RecordingNotifier notifier = new RecordingNotifier();
    private final DemandService service = at(NOW);

    // ---- 草稿与提交 ----

    @Test
    void aDraftNeedsOnlyItsTypeAndIsRecordedUnderTheCustomerOrganization() {
        DemandCreated created = service.create(ACCOUNT, COMPANY, KEY, new DemandDraft(
                " INSPECTION ", null, null, null, null, null, null, null, null, null, null), false);

        DemandView view = created.demand();
        assertThat(created.created()).isTrue();
        assertThat(view.status()).isEqualTo(DemandStatus.DRAFT);
        assertThat(view.type()).isEqualTo(DemandType.INSPECTION);
        assertThat(view.title()).isEqualTo("巡检需求");
        assertThat(view.publisher()).isEqualTo(new DemandPublisherView(DemandPublisherKind.ORGANIZATION, "东营示例物流公司"));
        assertThat(view.quoteScope()).isEqualTo(DemandQuoteScope.MATCH);
        assertThat(view.deliverables()).isEmpty();
        assertThat(view.editable()).isTrue();
        assertThat(view.deletable()).isTrue();
        assertThat(view.cancellable()).isFalse();
        assertThat(view.quotingOpen()).isFalse();
        assertThat(view.submittedAt()).isNull();
        assertThat(mapper.events).extracting(InMemoryMapper.Event::type).containsExactly("CREATED");
        // 个人身份：两列都为空。
        DemandView personal = service.create(ACCOUNT, null, SECOND_KEY, draft("INSPECTION").build(), false).demand();
        assertThat(personal.publisher()).isEqualTo(new DemandPublisherView(DemandPublisherKind.PERSONAL, null));
    }

    @Test
    void createAndSubmitInOneStepOpensTheDemandForSeventyTwoHours() {
        DemandView view = submitted(KEY, complete());

        assertThat(view.status()).isEqualTo(DemandStatus.OPEN);
        assertThat(view.submittedAt()).isEqualTo(NOW_UTC);
        assertThat(view.quoteDeadline()).isEqualTo(DEADLINE);
        assertThat(view.quotingOpen()).isTrue();
        assertThat(view.cancellable()).isTrue();
        assertThat(view.editable()).isFalse();
        assertThat(view.deletable()).isFalse();
        assertThat(view.cityName()).isEqualTo("东营市");
        assertThat(view.dueDate()).isEqualTo(LocalDate.parse("2026-11-30"));
        assertThat(view.budgetMinMinor()).isEqualTo(1_000_000L);
        assertThat(view.budgetMaxMinor()).isEqualTo(2_000_000L);
        assertThat(view.deliverables()).containsExactly(
                new DemandDeliverableView(DemandDeliverable.INSPECTION_REPORT, "巡检报告"),
                new DemandDeliverableView(DemandDeliverable.HAZARD_LIST, "隐患清单"));
        assertThat(mapper.events).extracting(InMemoryMapper.Event::type).containsExactly("CREATED", "SUBMITTED");
    }

    @Test
    void draftContentIsValidatedWhenSaved() {
        assertInvalid(() -> create(draft(null)));
        assertInvalid(() -> create(draft("GARDENING")));
        assertInvalid(() -> create(with(complete(), d -> d.deliverables = List.of("PHOTOS"))));
        assertInvalid(() -> create(with(complete(), d -> d.deliverables = List.of("HAZARD_LIST", "HAZARD_LIST"))));
        assertInvalid(() -> create(with(complete(), d -> d.cityCode = "999999")));
        assertInvalid(() -> create(with(complete(), d -> d.address = "地".repeat(81))));
        assertInvalid(() -> create(with(complete(), d -> d.scope = "范".repeat(61))));
        assertInvalid(() -> create(with(complete(), d -> d.note = "说".repeat(501))));
        assertInvalid(() -> create(with(complete(), d -> d.address = "第一行\t第二行")));
        assertInvalid(() -> create(with(complete(), d -> d.dueDate = "2026/11/30")));
        assertInvalid(() -> create(with(complete(), d -> {
            d.budgetMin = 3_000_000L;
            d.budgetMax = 2_000_000L;
        })));
        assertInvalid(() -> create(with(complete(), d -> d.budgetMax = 0L)));
        assertInvalid(() -> create(with(complete(), d -> {
            d.quoteScope = "INVITE";
            d.invited = List.of(YUNYI, EAGLE, JINAN, UUID.randomUUID());
        })));
        assertInvalid(() -> create(with(complete(), d -> {
            d.quoteScope = "INVITE";
            d.invited = List.of(NO_PROFILE);
        })));
        assertInvalid(() -> service.create(ACCOUNT, COMPANY, null, complete().build(), false));
        assertThat(mapper.demands).isEmpty();
        // 补充说明可以换行。
        assertThat(create(with(complete(), d -> d.note = "第一行\n第二行")).note()).isEqualTo("第一行\n第二行");
    }

    @Test
    void submittingChecksTheRequiredFieldsTopDown() {
        assertIncomplete("cityCode", with(complete(), d -> d.cityCode = null));
        assertIncomplete("address", with(complete(), d -> d.address = " "));
        assertIncomplete("scope", with(complete(), d -> d.scope = null));
        assertIncomplete("dueDate", with(complete(), d -> d.dueDate = "2026-11-18"));
        assertIncomplete("deliverables", with(complete(), d -> d.deliverables = List.of()));
        assertIncomplete("invitedProviderIds", with(complete(), d -> d.quoteScope = "INVITE"));
        // 邀请的服务商要服务发布的城市：济南飞服不服务东营。
        assertIncomplete("invitedProviderIds", with(complete(), d -> {
            d.quoteScope = "INVITE";
            d.invited = List.of(YUNYI, JINAN);
        }));
        // 今天、以及不填（时间可协商）都可以。
        assertThat(submitted(KEY, with(complete(), d -> d.dueDate = "2026-11-19")).status()).isEqualTo(DemandStatus.OPEN);
        assertThat(submitted(SECOND_KEY, with(complete(), d -> d.dueDate = null)).dueDate()).isNull();
    }

    @Test
    void aDraftCanBeCompletedThenSubmittedOnce() {
        DemandView draft = create(draft("SURVEY"));

        DemandView updated = service.updateDraft(ACCOUNT, draft.demandId(),
                with(complete(), d -> {
                    d.type = "SURVEY";
                    d.deliverables = List.of("POINT_CLOUD", "ORTHOPHOTO");
                    d.quoteScope = "INVITE";
                    d.invited = List.of(EAGLE, YUNYI);
                }).build());
        DemandView open = service.submit(ACCOUNT, draft.demandId());
        DemandView again = service.submit(ACCOUNT, draft.demandId());

        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.deliverables()).extracting(DemandDeliverableView::code)
                .containsExactly(DemandDeliverable.POINT_CLOUD, DemandDeliverable.ORTHOPHOTO);
        assertThat(updated.invitedProviders()).extracting(DemandProviderView::name).containsExactly("鹰眼智能巡检", "云翼科技");
        assertThat(updated.invitedProviders()).extracting(DemandProviderView::qualificationReviewed)
                .containsExactly(true, false);
        assertThat(open.status()).isEqualTo(DemandStatus.OPEN);
        assertThat(again).isEqualTo(open);
        assertThat(mapper.events).extracting(InMemoryMapper.Event::type)
                .containsExactly("CREATED", "UPDATED", "SUBMITTED");
        // 提交后不能再改、不能删；别人的需求当作不存在。
        assertRejected(Error.STATE_INVALID, () -> service.updateDraft(ACCOUNT, draft.demandId(), complete().build()));
        assertRejected(Error.STATE_INVALID, () -> service.deleteDraft(ACCOUNT, draft.demandId()));
        assertRejected(Error.NOT_FOUND, () -> service.read(OTHER_ACCOUNT, draft.demandId()));
        assertRejected(Error.NOT_FOUND, () -> service.submit(OTHER_ACCOUNT, draft.demandId()));
    }

    @Test
    void theSameKeyReturnsTheSameDemandAndAnotherRequestIsRefused() {
        DemandView first = service.create(ACCOUNT, COMPANY, KEY, complete().build(), true).demand();

        DemandCreated again = service.create(ACCOUNT, COMPANY, KEY, complete().build(), true);

        assertThat(again.created()).isFalse();
        assertThat(again.demand()).isEqualTo(first);
        assertThat(mapper.demands).hasSize(1);
        assertRejected(Error.IDEMPOTENCY_CONFLICT,
                () -> service.create(ACCOUNT, COMPANY, KEY, complete().build(), false));
        // 幂等键按账号隔离。
        assertThat(service.create(OTHER_ACCOUNT, null, KEY, complete().build(), false).created()).isTrue();
    }

    @Test
    void draftsCanBeDeletedForGood() {
        DemandView draft = create(complete());

        service.deleteDraft(ACCOUNT, draft.demandId());

        assertThat(mapper.demands).isEmpty();
        assertRejected(Error.NOT_FOUND, () -> service.deleteDraft(ACCOUNT, draft.demandId()));
    }

    @Test
    void myDemandsCanBeFilteredByStatus() {
        create(complete());
        DemandView open = submitted(SECOND_KEY, complete());
        service.create(OTHER_ACCOUNT, null, KEY, complete().build(), true);

        assertThat(service.list(ACCOUNT, null, 1, 20).total()).isEqualTo(2);
        assertThat(service.list(ACCOUNT, "OPEN", 1, 20).items()).extracting(DemandView::demandId)
                .containsExactly(open.demandId());
        assertThat(service.list(ACCOUNT, "DRAFT", 1, 1).hasMore()).isFalse();
        assertInvalid(() -> service.list(ACCOUNT, "LATER", 1, 20));
        assertInvalid(() -> service.list(ACCOUNT, null, 1, 51));
    }

    // ---- 服务商：能看到什么 ----

    @Test
    void platformMatchingShowsTheDemandToEveryProviderServingTheCity() {
        DemandView open = submitted(KEY, complete());
        create(complete());

        assertThat(service.listForProvider(YUNYI, 1, 20).items()).extracting(ProviderDemandView::demandId)
                .containsExactly(open.demandId());
        assertThat(service.listForProvider(EAGLE, 1, 20).total()).isEqualTo(1);
        assertThat(service.listForProvider(JINAN, 1, 20).items()).isEmpty();
        assertRejected(Error.NOT_FOUND, () -> service.readForProvider(JINAN, open.demandId()));
        ProviderDemandView seen = service.readForProvider(EAGLE, open.demandId());
        assertThat(seen.invited()).isFalse();
        assertThat(seen.quotingOpen()).isTrue();
        assertThat(seen.myQuote()).isNull();
        assertThat(seen.address()).isEqualTo("东营经济技术开发区");
    }

    @Test
    void inviteOnlyShowsTheDemandToTheInvitedProvidersOnly() {
        DemandView open = submitted(KEY, with(complete(), d -> {
            d.quoteScope = "INVITE";
            d.invited = List.of(EAGLE);
        }));

        assertThat(service.listForProvider(EAGLE, 1, 20).items()).singleElement()
                .satisfies(item -> assertThat(item.invited()).isTrue());
        assertThat(service.listForProvider(YUNYI, 1, 20).items()).isEmpty();
        assertRejected(Error.NOT_FOUND, () -> service.readForProvider(YUNYI, open.demandId()));
        assertRejected(Error.NOT_FOUND, () -> quote(YUNYI, open.demandId(), 100_000));
    }

    // 服务商只看到需求内容：看的形状里没有发布人的账号、组织和联系方式。
    @Test
    void providersNeverSeeWhoPublishedTheDemand() {
        assertThat(Arrays.stream(ProviderDemandView.class.getRecordComponents()).map(RecordComponent::getName))
                .doesNotContain("publisher", "accountId", "organizationId", "organizationName", "phone", "contact")
                .contains("address", "scope", "note");
        assertThat(Arrays.stream(DemandQuoteView.class.getRecordComponents()).map(RecordComponent::getName))
                .doesNotContain("submittedBy", "operatorId");
    }

    // ---- 服务商：报价 ----

    @Test
    void theFirstQuoteMovesTheDemandToQuotedAndTellsThePublisherOnce() {
        DemandView open = submitted(KEY, complete());

        ProviderDemandView quoted = quote(YUNYI, open.demandId(), 1_860_000);

        assertThat(quoted.myQuote().version()).isEqualTo(1);
        assertThat(quoted.myQuote().status()).isEqualTo(DemandQuoteStatus.ACTIVE);
        assertThat(service.read(ACCOUNT, open.demandId()).status()).isEqualTo(DemandStatus.QUOTED);
        assertThat(notifier.quotes).containsExactly(List.of(ACCOUNT, open.demandId(), "巡检需求",
                "demand-quote:" + quoted.myQuote().quoteId() + ":1"));
        // 先锁需求单，再锁报价。
        assertThat(mapper.locks).containsSubsequence("demand:" + open.demandId(), "quote:" + open.demandId());

        // 改价：版本加 1，不再通知；内容一样的重复提交没有变化。
        ProviderDemandView revised = quote(YUNYI, open.demandId(), 1_520_000);
        ProviderDemandView same = quote(YUNYI, open.demandId(), 1_520_000);
        assertThat(revised.myQuote().version()).isEqualTo(2);
        assertThat(revised.myQuote().amountMinor()).isEqualTo(1_520_000);
        assertThat(same.myQuote().version()).isEqualTo(2);
        assertThat(notifier.quotes).hasSize(1);
        assertThat(mapper.events).extracting(InMemoryMapper.Event::type, InMemoryMapper.Event::quoteVersion)
                .endsWith(tuple("QUOTED", 1), tuple("QUOTE_REVISED", 2));
    }

    @Test
    void aWithdrawnQuoteCanBeOfferedAgainBeforeTheDeadline() {
        DemandView open = submitted(KEY, complete());
        quote(YUNYI, open.demandId(), 1_860_000);

        ProviderDemandView withdrawn = service.withdrawQuote(YUNYI, OPERATOR, open.demandId());
        service.withdrawQuote(YUNYI, OPERATOR, open.demandId());

        assertThat(withdrawn.myQuote().status()).isEqualTo(DemandQuoteStatus.WITHDRAWN);
        assertThat(service.read(ACCOUNT, open.demandId()).status()).isEqualTo(DemandStatus.OPEN);
        assertThat(service.read(ACCOUNT, open.demandId()).quoteCount()).isZero();
        assertThat(service.quotes(ACCOUNT, open.demandId(), null).items()).isEmpty();

        ProviderDemandView again = quote(YUNYI, open.demandId(), 1_700_000);
        assertThat(again.myQuote().version()).isEqualTo(2);
        assertThat(service.read(ACCOUNT, open.demandId()).status()).isEqualTo(DemandStatus.QUOTED);
        assertThat(notifier.quotes).hasSize(2);
        assertThat(mapper.events).extracting(InMemoryMapper.Event::type)
                .endsWith("QUOTED", "QUOTE_WITHDRAWN", "QUOTED");
        assertRejected(Error.NOT_FOUND, () -> service.withdrawQuote(EAGLE, OPERATOR, open.demandId()));
    }

    @Test
    void quotesAreValidatedAndOnlyAcceptedBeforeTheDeadline() {
        DemandView open = submitted(KEY, complete());

        assertInvalid(() -> quote(YUNYI, open.demandId(), 0));
        assertInvalid(() -> service.submitQuote(YUNYI, OPERATOR, open.demandId(),
                new QuoteDraft(100L, "无人机", "共 6 次", 0, "巡检报告", NOW_UTC.plusDays(7))));
        assertInvalid(() -> service.submitQuote(YUNYI, OPERATOR, open.demandId(),
                new QuoteDraft(100L, "无".repeat(41), "共 6 次", 90, "巡检报告", NOW_UTC.plusDays(7))));
        assertInvalid(() -> service.submitQuote(YUNYI, OPERATOR, open.demandId(),
                new QuoteDraft(100L, "无人机", " ", 90, "巡检报告", NOW_UTC.plusDays(7))));
        assertInvalid(() -> service.submitQuote(YUNYI, OPERATOR, open.demandId(),
                new QuoteDraft(100L, "无人机", "共 6 次", 90, "报".repeat(121), NOW_UTC.plusDays(7))));
        assertInvalid(() -> service.submitQuote(YUNYI, OPERATOR, open.demandId(),
                new QuoteDraft(100L, "无人机", "共 6 次", 90, "巡检报告", NOW_UTC)));
        assertInvalid(() -> service.submitQuote(YUNYI, OPERATOR, open.demandId(),
                new QuoteDraft(100L, "无人机", "共 6 次", 90, "巡检报告", NOW_UTC.plusDays(30).plusSeconds(1))));
        assertRejected(Error.FORBIDDEN, () -> quote(NO_PROFILE, open.demandId(), 100_000));
        assertRejected(Error.NOT_FOUND, () -> quote(YUNYI, UUID.randomUUID(), 100_000));
        assertThat(mapper.quotes).isEmpty();

        DemandService late = at(DEADLINE.toInstant());
        assertRejected(Error.QUOTING_CLOSED, () -> late.submitQuote(YUNYI, OPERATOR, open.demandId(),
                new QuoteDraft(100L, "无人机", "共 6 次", 90, "巡检报告", DEADLINE.plusDays(1))));
        assertThat(late.readForProvider(YUNYI, open.demandId()).quotingOpen()).isFalse();
    }

    // ---- 客户：比选与选定 ----

    @Test
    void quotesAreSortedBySubmissionPriceOrDurationWithTiesInSubmissionOrder() {
        DemandView open = submitted(KEY, complete());
        quote(YUNYI, open.demandId(), 1_860_000, 90);
        DemandService later = at(NOW.plusSeconds(60));
        later.submitQuote(EAGLE, OPERATOR, open.demandId(), quoteDraft(1_520_000, 90, NOW_UTC.plusDays(7)));
        DemandService latest = at(NOW.plusSeconds(120));
        latest.submitQuote(SECOND_PROVIDER, OPERATOR, open.demandId(), quoteDraft(1_520_000, 60, NOW_UTC.plusDays(7)));

        assertThat(service.quotes(ACCOUNT, open.demandId(), null).items())
                .extracting(quote -> quote.provider().name())
                .containsExactly("云翼科技", "鹰眼智能巡检", "东营安巡科技");
        assertThat(service.quotes(ACCOUNT, open.demandId(), "PRICE").items())
                .extracting(quote -> quote.provider().name())
                .containsExactly("鹰眼智能巡检", "东营安巡科技", "云翼科技");
        DemandQuoteListView byDuration = service.quotes(ACCOUNT, open.demandId(), "DURATION");
        assertThat(byDuration.items()).extracting(quote -> quote.provider().name())
                .containsExactly("东营安巡科技", "云翼科技", "鹰眼智能巡检");
        assertThat(byDuration.sort()).isEqualTo(DemandQuoteSort.DURATION);
        assertThat(byDuration.demandStatus()).isEqualTo(DemandStatus.QUOTED);
        assertThat(byDuration.items()).allSatisfy(quote -> assertThat(quote.choosable()).isTrue());
        assertThat(byDuration.items().get(1).provider().certificationLabel()).isEqualTo("企业认证");
        // R6-4：资质审核来自服务商名录（机构的审核通过时间），每家各自的。
        assertThat(byDuration.items()).extracting(quote -> quote.provider().name(),
                        quote -> quote.provider().qualificationReviewed())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("东营安巡科技", false),
                        org.assertj.core.groups.Tuple.tuple("云翼科技", false),
                        org.assertj.core.groups.Tuple.tuple("鹰眼智能巡检", true));
        assertInvalid(() -> service.quotes(ACCOUNT, open.demandId(), "RATING"));
        assertRejected(Error.NOT_FOUND, () -> service.quotes(OTHER_ACCOUNT, open.demandId(), null));
        // 过了有效期：读成已过期，不能选。
        DemandQuoteListView expired = at(NOW_UTC.plusDays(8).toInstant()).quotes(ACCOUNT, open.demandId(), null);
        assertThat(expired.items()).extracting(DemandQuoteView::status).containsOnly(DemandQuoteStatus.EXPIRED);
        assertThat(expired.items()).noneMatch(DemandQuoteView::choosable);
    }

    @Test
    void choosingAQuoteSettlesTheDemandAndTheOtherQuotes() {
        DemandView open = submitted(KEY, complete());
        UUID chosenId = quote(YUNYI, open.demandId(), 1_860_000).myQuote().quoteId();
        at(NOW.plusSeconds(60)).submitQuote(EAGLE, OPERATOR, open.demandId(), quoteDraft(1_520_000, 90, NOW_UTC.plusDays(7)));
        int version = service.read(ACCOUNT, open.demandId()).version();
        mapper.locks.clear();

        DemandView chosen = service.choose(ACCOUNT, open.demandId(), version, chosenId, 1);

        assertThat(chosen.status()).isEqualTo(DemandStatus.CHOSEN);
        assertThat(chosen.chosen()).isEqualTo(new DemandChosenView(chosenId, 1, "云翼科技", 1_860_000, NOW_UTC));
        assertThat(chosen.cancellable()).isFalse();
        assertThat(chosen.quotingOpen()).isFalse();
        assertThat(mapper.locks).first().isEqualTo("demand:" + open.demandId());
        assertThat(service.quotes(ACCOUNT, open.demandId(), null).items())
                .extracting(quote -> quote.provider().name(), DemandQuoteView::status, DemandQuoteView::choosable)
                .containsExactly(
                        tuple("云翼科技", DemandQuoteStatus.CHOSEN, false),
                        tuple("鹰眼智能巡检", DemandQuoteStatus.NOT_CHOSEN, false));
        // 同一份同一版再选一次（页面的版本已经过时）：原样返回。别的报价：不能再选。
        assertThat(service.choose(ACCOUNT, open.demandId(), version, chosenId, 1)).isEqualTo(chosen);
        UUID other = mapper.quotes.get(1).id;
        assertRejected(Error.STATE_INVALID, () -> service.choose(ACCOUNT, open.demandId(), chosen.version(), other, 1));
        // 选定之后服务商不能改价、撤回；没被选的也不能。
        assertRejected(Error.QUOTE_LOCKED, () -> quote(YUNYI, open.demandId(), 1_000_000));
        assertRejected(Error.QUOTE_LOCKED, () -> service.withdrawQuote(YUNYI, OPERATOR, open.demandId()));
        assertRejected(Error.QUOTE_LOCKED, () -> quote(EAGLE, open.demandId(), 1_000_000));
        // 被选的服务商还看得到它（看结果）；平台匹配的其他服务商看不到了。
        assertThat(service.readForProvider(YUNYI, open.demandId()).myQuote().status()).isEqualTo(DemandQuoteStatus.CHOSEN);
        assertRejected(Error.NOT_FOUND, () -> service.readForProvider(SECOND_PROVIDER, open.demandId()));
        assertThat(mapper.events).extracting(InMemoryMapper.Event::type).last().isEqualTo("CHOSEN");
        assertRejected(Error.STATE_INVALID, () -> service.cancel(ACCOUNT, open.demandId()));
    }

    @Test
    void choosingChecksTheDemandVersionTheQuoteVersionAndItsValidity() {
        DemandView open = submitted(KEY, complete());
        UUID quoteId = quote(YUNYI, open.demandId(), 1_860_000).myQuote().quoteId();
        int version = service.read(ACCOUNT, open.demandId()).version();

        assertRejected(Error.VERSION_CONFLICT, () -> service.choose(ACCOUNT, open.demandId(), version - 1, quoteId, 1));
        quote(YUNYI, open.demandId(), 1_700_000);
        assertRejected(Error.QUOTE_CHANGED, () -> service.choose(ACCOUNT, open.demandId(), version, quoteId, 1));
        assertRejected(Error.NOT_FOUND, () -> service.choose(ACCOUNT, open.demandId(), version, UUID.randomUUID(), 1));
        assertInvalid(() -> service.choose(ACCOUNT, open.demandId(), version, quoteId, null));
        assertRejected(Error.QUOTE_EXPIRED, () -> at(NOW_UTC.plusDays(7).toInstant())
                .choose(ACCOUNT, open.demandId(), version, quoteId, 2));
        service.withdrawQuote(YUNYI, OPERATOR, open.demandId());
        int afterWithdraw = service.read(ACCOUNT, open.demandId()).version();
        assertRejected(Error.QUOTE_CHANGED, () -> service.choose(ACCOUNT, open.demandId(), afterWithdraw, quoteId, 2));
        assertRejected(Error.NOT_FOUND, () -> service.choose(OTHER_ACCOUNT, open.demandId(), afterWithdraw, quoteId, 2));
        assertThat(mapper.demand(open.demandId()).status).isEqualTo("OPEN");
    }

    // 截止后不再收报价，但在报价的有效期内仍可以选。
    @Test
    void aQuoteCanStillBeChosenAfterTheDeadlineWhileItIsValid() {
        DemandView open = submitted(KEY, complete());
        UUID quoteId = quote(YUNYI, open.demandId(), 1_860_000).myQuote().quoteId();
        int version = service.read(ACCOUNT, open.demandId()).version();

        DemandView chosen = at(DEADLINE.plusHours(1).toInstant()).choose(ACCOUNT, open.demandId(), version, quoteId, 1);

        assertThat(chosen.status()).isEqualTo(DemandStatus.CHOSEN);
    }

    // ---- 取消与定时关闭 ----

    @Test
    void cancellingClosesTheDemandAndItsQuotesOnlyBeforeAChoice() {
        DemandView open = submitted(KEY, complete());
        quote(YUNYI, open.demandId(), 1_860_000);

        DemandView cancelled = service.cancel(ACCOUNT, open.demandId());
        DemandView again = service.cancel(ACCOUNT, open.demandId());

        assertThat(cancelled.status()).isEqualTo(DemandStatus.CLOSED);
        assertThat(cancelled.closeReason()).isEqualTo(DemandCloseReason.CANCELLED);
        assertThat(again).isEqualTo(cancelled);
        assertThat(mapper.quotes).extracting(row -> row.status).containsOnly("CLOSED");
        assertRejected(Error.QUOTE_LOCKED, () -> quote(YUNYI, open.demandId(), 1_000_000));
        assertRejected(Error.NOT_FOUND, () -> quote(EAGLE, open.demandId(), 1_000_000));
        assertRejected(Error.STATE_INVALID, () -> service.cancel(ACCOUNT, create(complete()).demandId()));
        assertThat(service.list(ACCOUNT, "CLOSED", 1, 20).total()).isEqualTo(1);
    }

    @Test
    void demandsWithoutQuotesCloseAtTheDeadlineAndThePublisherIsTold() {
        DemandView lonely = submitted(KEY, complete());
        DemandView quoted = submitted(SECOND_KEY, complete());
        quote(YUNYI, quoted.demandId(), 1_860_000);

        assertThat(at(DEADLINE.minusSeconds(1).toInstant()).closeExpired()).isZero();
        int closed = at(DEADLINE.toInstant()).closeExpired();

        assertThat(closed).isEqualTo(1);
        DemandView view = service.read(ACCOUNT, lonely.demandId());
        assertThat(view.status()).isEqualTo(DemandStatus.CLOSED);
        assertThat(view.closeReason()).isEqualTo(DemandCloseReason.NO_QUOTES);
        assertThat(service.read(ACCOUNT, quoted.demandId()).status()).isEqualTo(DemandStatus.QUOTED);
        assertThat(notifier.closed).containsExactly(List.of(ACCOUNT, lonely.demandId(), "巡检需求"));
        assertThat(mapper.events).filteredOn(event -> event.type().equals("CLOSED_NO_QUOTES"))
                .singleElement().satisfies(event -> assertThat(event.actor()).isNull());
        assertThat(at(DEADLINE.toInstant()).closeExpired()).isZero();
    }

    // ---- 可邀请的服务商 ----

    @Test
    void invitableProvidersAreThoseServingTheCity() {
        assertThat(service.invitableProviders("370500", "INSPECTION").items())
                .extracting(DemandProviderView::name)
                .containsExactly("东营安巡科技", "云翼科技", "鹰眼智能巡检");
        assertThat(service.invitableProviders("370100", null).items()).extracting(DemandProviderView::providerId)
                .containsExactly(YUNYI, JINAN);
        assertInvalid(() -> service.invitableProviders(null, null));
        assertInvalid(() -> service.invitableProviders("370500", "GARDENING"));
    }

    // ---- 辅助 ----

    /** 第三家东营服务商（只在比选排序里用）。 */
    private static final UUID SECOND_PROVIDER = UUID.fromString("22222222-2222-2222-2222-666666666666");

    private DemandService at(Instant instant) {
        return new DemandService(mapper, FakeCities.INSTANCE, providers, notifier, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private DemandView create(DraftBuilder draft) {
        return service.create(ACCOUNT, COMPANY, UUID.randomUUID(), draft.build(), false).demand();
    }

    private DemandView submitted(UUID key, DraftBuilder draft) {
        return service.create(ACCOUNT, COMPANY, key, draft.build(), true).demand();
    }

    private ProviderDemandView quote(UUID providerId, UUID demandId, long amount) {
        return quote(providerId, demandId, amount, 90);
    }

    private ProviderDemandView quote(UUID providerId, UUID demandId, long amount, int days) {
        return service.submitQuote(providerId, OPERATOR, demandId, quoteDraft(amount, days, NOW_UTC.plusDays(7)));
    }

    private static QuoteDraft quoteDraft(long amount, int days, OffsetDateTime validUntil) {
        return new QuoteDraft(amount, "无人机 + 机器狗", "共 6 次 · 11/02 首次进场", days, "巡检报告、隐患清单", validUntil);
    }

    private void assertIncomplete(String field, DraftBuilder draft) {
        int before = mapper.demands.size();
        assertThatThrownBy(() -> service.create(ACCOUNT, COMPANY, UUID.randomUUID(), draft.build(), true))
                .isInstanceOfSatisfying(DemandException.class, exception -> {
                    assertThat(exception.error()).isEqualTo(Error.INCOMPLETE);
                    assertThat(exception.field()).isEqualTo(field);
                });
        // 内存里没有回滚：失败的这一份留下了草稿，删掉免得影响后面的断言（数据库里整步回滚）。
        mapper.demands.subList(before, mapper.demands.size()).clear();
    }

    private static DraftBuilder draft(String type) {
        DraftBuilder builder = new DraftBuilder();
        builder.type = type;
        return builder;
    }

    private static DraftBuilder complete() {
        DraftBuilder builder = new DraftBuilder();
        builder.type = "INSPECTION";
        builder.cityCode = "370500";
        builder.address = "东营经济技术开发区";
        builder.scope = "约 1.2 平方公里";
        builder.dueDate = "2026-11-30";
        builder.budgetMin = 1_000_000L;
        builder.budgetMax = 2_000_000L;
        builder.deliverables = List.of("INSPECTION_REPORT", "HAZARD_LIST");
        builder.quoteScope = "MATCH";
        return builder;
    }

    private static DraftBuilder with(DraftBuilder builder, java.util.function.Consumer<DraftBuilder> change) {
        change.accept(builder);
        return builder;
    }

    private static void assertRejected(Error error, ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(DemandException.class,
                exception -> assertThat(exception.error()).isEqualTo(error));
    }

    private static void assertInvalid(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(IllegalArgumentException.class);
    }

    private static final class DraftBuilder {
        String type;
        String cityCode;
        String address;
        String scope;
        String dueDate;
        Long budgetMin;
        Long budgetMax;
        List<String> deliverables;
        String note;
        String quoteScope;
        List<UUID> invited;

        DemandDraft build() {
            return new DemandDraft(type, cityCode, address, scope, dueDate, budgetMin, budgetMax, deliverables, note,
                    quoteScope, invited);
        }
    }

    private enum FakeCities implements DemandPorts.CityDirectory {
        INSTANCE;

        @Override
        public Optional<String> cityName(String cityCode) {
            return Optional.ofNullable(Map.of("370500", "东营市", "370100", "济南市").get(cityCode));
        }
    }

    private static final class FakeProviders implements DemandPorts.ProviderDirectory {
        final Map<UUID, Provider> all = new LinkedHashMap<>();
        final Map<UUID, Set<String>> cities = Map.of(
                YUNYI, Set.of("370500", "370100"),
                EAGLE, Set.of("370500"),
                JINAN, Set.of("370100"),
                SECOND_PROVIDER, Set.of("370500"));

        FakeProviders() {
            all.put(YUNYI, new Provider(YUNYI, "云翼科技", "企业认证", "专业飞手团队", false));
            all.put(EAGLE, new Provider(EAGLE, "鹰眼智能巡检", "企业认证", null, true));
            all.put(JINAN, new Provider(JINAN, "济南飞服", null, null, false));
            all.put(SECOND_PROVIDER, new Provider(SECOND_PROVIDER, "东营安巡科技", "企业认证", null, false));
        }

        @Override
        public List<Provider> providersIn(String cityCode) {
            return all.values().stream()
                    .filter(provider -> cities.get(provider.providerId()).contains(cityCode))
                    .sorted(Comparator.comparing(Provider::name))
                    .toList();
        }

        @Override
        public List<Provider> find(List<UUID> providerIds) {
            return providerIds.stream().map(all::get).filter(java.util.Objects::nonNull).toList();
        }

        @Override
        public Set<String> citiesServed(UUID providerId) {
            return cities.getOrDefault(providerId, Set.of());
        }
    }

    private static final class RecordingNotifier implements DemandPorts.Notifier {
        final List<List<Object>> quotes = new ArrayList<>();
        final List<List<Object>> closed = new ArrayList<>();

        @Override
        public void quoteReceived(UUID recipientId, UUID demandId, String demandTitle, String sourceKey) {
            quotes.add(List.of(recipientId, demandId, demandTitle, sourceKey));
        }

        @Override
        public void closedWithoutQuotes(UUID recipientId, UUID demandId, String demandTitle) {
            closed.add(List.of(recipientId, demandId, demandTitle));
        }
    }

    /**
     * 内存里的 mapper：每个方法按对应 SQL 的条件写，V001 的约束在这里用异常代替。locks 按顺序记下每次加锁。
     */
    private static final class InMemoryMapper implements DemandMapper {

        private static final Set<String> EVENTS = Set.of("CREATED", "UPDATED", "SUBMITTED", "QUOTED", "QUOTE_REVISED",
                "QUOTE_WITHDRAWN", "CHOSEN", "CANCELLED", "CLOSED_NO_QUOTES");

        final List<Demand> demands = new ArrayList<>();
        final List<DeliverableRow> deliverables = new ArrayList<>();
        final List<InvitationRow> invitations = new ArrayList<>();
        final List<Quote> quotes = new ArrayList<>();
        final List<Event> events = new ArrayList<>();
        final List<String> locks = new ArrayList<>();

        Demand demand(UUID id) {
            return demands.stream().filter(row -> row.id.equals(id)).findFirst().orElse(null);
        }

        @Override
        public Integer lockAccount(UUID accountId) {
            locks.add("account:" + accountId);
            return 1;
        }

        @Override
        public KeyRow findByKey(UUID accountId, UUID idempotencyKey) {
            return demands.stream()
                    .filter(row -> row.accountId.equals(accountId) && row.key.equals(idempotencyKey))
                    .map(row -> new KeyRow(row.id, row.hash))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public int insertDemand(UUID demandId, UUID accountId, UUID organizationId, String organizationName,
                                String type, String cityCode, String cityName, String address, String scopeText,
                                LocalDate dueDate, Long budgetMinMinor, Long budgetMaxMinor, String note,
                                String quoteScope, UUID idempotencyKey, String requestHash, OffsetDateTime at) {
            if (findByKey(accountId, idempotencyKey) != null) {
                throw new IllegalStateException("UNIQUE (account_id, idempotency_key)");
            }
            if ((organizationId == null) != (organizationName == null) || (cityCode == null) != (cityName == null)
                    || requestHash.length() != 64) {
                throw new IllegalStateException("demand check");
            }
            Demand row = new Demand();
            row.id = demandId;
            row.accountId = accountId;
            row.organizationId = organizationId;
            row.organizationName = organizationName;
            row.key = idempotencyKey;
            row.hash = requestHash;
            row.createdAt = at;
            fill(row, type, cityCode, cityName, address, scopeText, dueDate, budgetMinMinor, budgetMaxMinor, note,
                    quoteScope, at);
            demands.add(row);
            return 1;
        }

        private static void fill(Demand row, String type, String cityCode, String cityName, String address,
                                 String scopeText, LocalDate dueDate, Long min, Long max, String note,
                                 String quoteScope, OffsetDateTime at) {
            if (min != null && max != null && min > max) {
                throw new IllegalStateException("ck_demand_budget");
            }
            row.type = type;
            row.cityCode = cityCode;
            row.cityName = cityName;
            row.address = address;
            row.scope = scopeText;
            row.dueDate = dueDate;
            row.budgetMin = min;
            row.budgetMax = max;
            row.note = note;
            row.quoteScope = quoteScope;
            row.updatedAt = at;
        }

        @Override
        public int updateDraft(UUID demandId, UUID accountId, String type, String cityCode, String cityName,
                               String address, String scopeText, LocalDate dueDate, Long budgetMinMinor,
                               Long budgetMaxMinor, String note, String quoteScope, OffsetDateTime at) {
            Demand row = demand(demandId);
            if (row == null || !row.accountId.equals(accountId) || !row.status.equals("DRAFT")) {
                return 0;
            }
            fill(row, type, cityCode, cityName, address, scopeText, dueDate, budgetMinMinor, budgetMaxMinor, note,
                    quoteScope, at);
            row.version++;
            return 1;
        }

        @Override
        public int deleteDeliverables(UUID demandId) {
            int before = deliverables.size();
            deliverables.removeIf(row -> row.demandId().equals(demandId));
            return before - deliverables.size();
        }

        @Override
        public int insertDeliverable(UUID demandId, String code, int sortOrder) {
            if (deliverables.contains(new DeliverableRow(demandId, code))) {
                throw new IllegalStateException("PRIMARY KEY (demand_id, code)");
            }
            deliverables.add(new DeliverableRow(demandId, code));
            return 1;
        }

        @Override
        public int deleteInvitations(UUID demandId) {
            int before = invitations.size();
            invitations.removeIf(row -> row.demandId().equals(demandId));
            return before - invitations.size();
        }

        @Override
        public int insertInvitation(UUID demandId, UUID providerId, int sortOrder) {
            invitations.add(new InvitationRow(demandId, providerId));
            return 1;
        }

        @Override
        public int submit(UUID demandId, OffsetDateTime deadline, OffsetDateTime at) {
            Demand row = demand(demandId);
            if (row == null || !row.status.equals("DRAFT")) {
                return 0;
            }
            if (row.cityCode == null || row.address == null || row.scope == null) {
                throw new IllegalStateException("ck_demand_submitted");
            }
            row.status = "OPEN";
            row.submittedAt = at;
            row.deadline = deadline;
            row.version++;
            return 1;
        }

        @Override
        public int deleteDraft(UUID accountId, UUID demandId) {
            Demand row = demand(demandId);
            if (row == null || !row.accountId.equals(accountId) || !row.status.equals("DRAFT")) {
                return 0;
            }
            demands.remove(row);
            deleteDeliverables(demandId);
            deleteInvitations(demandId);
            events.removeIf(event -> event.demandId().equals(demandId));
            return 1;
        }

        @Override
        public DemandRow findDemand(UUID accountId, UUID demandId) {
            Demand row = demand(demandId);
            return row == null || !row.accountId.equals(accountId) ? null : row.toRow();
        }

        @Override
        public DemandRow lockDemand(UUID accountId, UUID demandId) {
            locks.add("demand:" + demandId);
            return findDemand(accountId, demandId);
        }

        @Override
        public long countDemands(UUID accountId, String status) {
            return mine(accountId, status).size();
        }

        @Override
        public List<DemandRow> findDemandPage(UUID accountId, String status, int limit, int offset) {
            return mine(accountId, status).stream().skip(offset).limit(limit).map(Demand::toRow).toList();
        }

        private List<Demand> mine(UUID accountId, String status) {
            return demands.stream()
                    .filter(row -> row.accountId.equals(accountId) && (status == null || row.status.equals(status)))
                    .sorted(Comparator.comparing((Demand row) -> row.updatedAt).reversed())
                    .toList();
        }

        @Override
        public List<DeliverableRow> findDeliverables(List<UUID> demandIds) {
            requireIds(demandIds);
            return deliverables.stream().filter(row -> demandIds.contains(row.demandId())).toList();
        }

        @Override
        public List<InvitationRow> findInvitations(List<UUID> demandIds) {
            requireIds(demandIds);
            return invitations.stream().filter(row -> demandIds.contains(row.demandId())).toList();
        }

        @Override
        public int changeStatus(UUID demandId, String fromStatus, String toStatus, OffsetDateTime at) {
            Demand row = demand(demandId);
            if (row == null || !row.status.equals(fromStatus)) {
                return 0;
            }
            row.status = toStatus;
            row.version++;
            row.updatedAt = at;
            return 1;
        }

        @Override
        public int close(UUID demandId, String reason, OffsetDateTime at) {
            Demand row = demand(demandId);
            if (row == null || !Set.of("OPEN", "QUOTED").contains(row.status)) {
                return 0;
            }
            row.status = "CLOSED";
            row.closeReason = reason;
            row.closedAt = at;
            row.version++;
            return 1;
        }

        @Override
        public int choose(UUID demandId, UUID quoteId, int quoteVersion, int version, OffsetDateTime at) {
            Demand row = demand(demandId);
            if (row == null || !Set.of("OPEN", "QUOTED").contains(row.status) || row.version != version) {
                return 0;
            }
            row.status = "CHOSEN";
            row.chosenQuoteId = quoteId;
            row.chosenQuoteVersion = quoteVersion;
            row.chosenAt = at;
            row.version++;
            return 1;
        }

        @Override
        public List<DemandRow> lockExpiredOpen(OffsetDateTime now, int limit) {
            return demands.stream()
                    .filter(row -> row.status.equals("OPEN") && !row.deadline.isAfter(now))
                    .limit(limit)
                    .map(Demand::toRow)
                    .toList();
        }

        @Override
        public int insertEvent(UUID demandId, UUID quoteId, String eventType, UUID actorId, Integer quoteVersion,
                               Long amountMinor, OffsetDateTime at) {
            boolean quoteEvent = Set.of("QUOTED", "QUOTE_REVISED", "QUOTE_WITHDRAWN", "CHOSEN").contains(eventType);
            if (!EVENTS.contains(eventType) || quoteEvent != (quoteId != null)
                    || (actorId == null && !eventType.equals("CLOSED_NO_QUOTES"))) {
                throw new IllegalStateException("demand_event check");
            }
            events.add(new Event(demandId, eventType, actorId, quoteVersion));
            return 1;
        }

        @Override
        public DemandRow lockSubmittedDemand(UUID demandId) {
            locks.add("demand:" + demandId);
            return findSubmittedDemand(demandId);
        }

        @Override
        public DemandRow findSubmittedDemand(UUID demandId) {
            Demand row = demand(demandId);
            return row == null || row.status.equals("DRAFT") ? null : row.toRow();
        }

        @Override
        public int countInvitation(UUID demandId, UUID providerId) {
            return invitations.contains(new InvitationRow(demandId, providerId)) ? 1 : 0;
        }

        @Override
        public long countProviderDemands(UUID providerId, List<String> cities) {
            return visible(providerId, cities).size();
        }

        @Override
        public List<DemandRow> findProviderDemands(UUID providerId, List<String> cities, int limit, int offset) {
            return visible(providerId, cities).stream().skip(offset).limit(limit).map(Demand::toRow).toList();
        }

        private List<Demand> visible(UUID providerId, List<String> cities) {
            return demands.stream()
                    .filter(row -> !row.status.equals("DRAFT"))
                    .filter(row -> (Set.of("OPEN", "QUOTED").contains(row.status)
                            && ((row.quoteScope.equals("MATCH") && cities.contains(row.cityCode))
                                || invitations.contains(new InvitationRow(row.id, providerId))))
                            || quotes.stream().anyMatch(q -> q.demandId.equals(row.id) && q.providerId.equals(providerId)))
                    .sorted(Comparator.comparing((Demand row) -> row.submittedAt).reversed())
                    .toList();
        }

        @Override
        public List<QuoteRow> findQuotes(UUID demandId) {
            return quotes.stream().filter(row -> row.demandId.equals(demandId)).map(Quote::toRow).toList();
        }

        @Override
        public List<QuoteRow> findQuotesOf(List<UUID> demandIds) {
            requireIds(demandIds);
            return quotes.stream().filter(row -> demandIds.contains(row.demandId)).map(Quote::toRow).toList();
        }

        @Override
        public QuoteRow lockProviderQuote(UUID demandId, UUID providerId) {
            locks.add("quote:" + demandId);
            return quotes.stream()
                    .filter(row -> row.demandId.equals(demandId) && row.providerId.equals(providerId))
                    .map(Quote::toRow)
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public List<QuoteRow> findProviderQuotes(UUID providerId, List<UUID> demandIds) {
            requireIds(demandIds);
            return quotes.stream()
                    .filter(row -> row.providerId.equals(providerId) && demandIds.contains(row.demandId))
                    .map(Quote::toRow)
                    .toList();
        }

        @Override
        public UUID insertQuote(UUID demandId, UUID providerId, long amountMinor, String method, String scheduleText,
                                int durationDays, String deliverablesText, OffsetDateTime validUntil,
                                UUID submittedBy, OffsetDateTime at) {
            if (quotes.stream().anyMatch(row -> row.demandId.equals(demandId) && row.providerId.equals(providerId))) {
                throw new IllegalStateException("UNIQUE (demand_id, provider_id)");
            }
            Quote row = new Quote();
            row.id = UUID.randomUUID();
            row.demandId = demandId;
            row.providerId = providerId;
            row.firstSubmittedAt = at;
            content(row, amountMinor, method, scheduleText, durationDays, deliverablesText, validUntil, at);
            quotes.add(row);
            return row.id;
        }

        private static void content(Quote row, long amount, String method, String schedule, int days,
                                    String deliverables, OffsetDateTime validUntil, OffsetDateTime at) {
            if (amount <= 0 || days < 1 || days > 3650 || method.length() > 40 || schedule.length() > 60
                    || deliverables.length() > 120) {
                throw new IllegalStateException("demand_quote check");
            }
            row.amount = amount;
            row.method = method;
            row.schedule = schedule;
            row.days = days;
            row.deliverables = deliverables;
            row.validUntil = validUntil;
            row.revisedAt = at;
        }

        private Quote quote(UUID id) {
            return quotes.stream().filter(row -> row.id.equals(id)).findFirst().orElseThrow();
        }

        @Override
        public int reviseQuote(UUID quoteId, long amountMinor, String method, String scheduleText, int durationDays,
                               String deliverablesText, OffsetDateTime validUntil, UUID submittedBy,
                               OffsetDateTime at) {
            Quote row = quote(quoteId);
            if (!Set.of("ACTIVE", "WITHDRAWN").contains(row.status)) {
                return 0;
            }
            content(row, amountMinor, method, scheduleText, durationDays, deliverablesText, validUntil, at);
            row.status = "ACTIVE";
            row.quoteVersion++;
            return 1;
        }

        @Override
        public int withdrawQuote(UUID quoteId, OffsetDateTime at) {
            Quote row = quote(quoteId);
            if (!row.status.equals("ACTIVE")) {
                return 0;
            }
            row.status = "WITHDRAWN";
            row.revisedAt = at;
            return 1;
        }

        @Override
        public int countActiveQuotes(UUID demandId) {
            return (int) quotes.stream().filter(row -> row.demandId.equals(demandId) && row.status.equals("ACTIVE")).count();
        }

        @Override
        public int markChosen(UUID demandId, UUID quoteId, int quoteVersion, OffsetDateTime at) {
            Quote row = quote(quoteId);
            if (!row.demandId.equals(demandId) || !row.status.equals("ACTIVE") || row.quoteVersion != quoteVersion) {
                return 0;
            }
            row.status = "CHOSEN";
            return 1;
        }

        @Override
        public int endActiveQuotes(UUID demandId, String toStatus, OffsetDateTime at) {
            int changed = 0;
            for (Quote row : quotes) {
                if (row.demandId.equals(demandId) && row.status.equals("ACTIVE")) {
                    row.status = toStatus;
                    changed++;
                }
            }
            return changed;
        }

        private static void requireIds(List<UUID> ids) {
            if (ids.isEmpty()) {
                throw new IllegalStateException("IN () is not valid SQL");
            }
        }

        static final class Demand {
            UUID id;
            UUID accountId;
            UUID organizationId;
            String organizationName;
            String type;
            String cityCode;
            String cityName;
            String address;
            String scope;
            LocalDate dueDate;
            Long budgetMin;
            Long budgetMax;
            String note;
            String quoteScope;
            String status = "DRAFT";
            OffsetDateTime submittedAt;
            OffsetDateTime deadline;
            UUID chosenQuoteId;
            Integer chosenQuoteVersion;
            OffsetDateTime chosenAt;
            OffsetDateTime closedAt;
            String closeReason;
            UUID key;
            String hash;
            OffsetDateTime createdAt;
            OffsetDateTime updatedAt;
            int version;

            DemandRow toRow() {
                return new DemandRow(id, accountId, organizationId, organizationName, type, cityCode, cityName,
                        address, scope, dueDate, budgetMin, budgetMax, note, quoteScope, status, submittedAt, deadline,
                        chosenQuoteId, chosenQuoteVersion, chosenAt, closedAt, closeReason, createdAt, updatedAt,
                        version);
            }
        }

        static final class Quote {
            UUID id;
            UUID demandId;
            UUID providerId;
            String status = "ACTIVE";
            int quoteVersion = 1;
            long amount;
            String method;
            String schedule;
            int days;
            String deliverables;
            OffsetDateTime validUntil;
            OffsetDateTime firstSubmittedAt;
            OffsetDateTime revisedAt;

            QuoteRow toRow() {
                return new QuoteRow(id, demandId, providerId, status, quoteVersion, amount, method, schedule, days,
                        deliverables, validUntil, firstSubmittedAt, revisedAt);
            }
        }

        record Event(UUID demandId, String type, UUID actor, Integer quoteVersion) {
        }
    }
}
