package com.lowaltitude.inspection.content.application.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.content.application.ops.ContentOpsCodes.OpsAuthorKind;
import com.lowaltitude.inspection.content.application.ops.ContentOpsCodes.OpsBlockType;
import com.lowaltitude.inspection.content.application.ops.ContentOpsCodes.OpsContentStatus;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.ArticleDraft;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.AuthorDraft;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.BlockDraft;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.BlockItemDraft;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.MerchantItemDraft;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.Operator;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.OpsArticleView;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.OpsBlockImageView;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.OpsTopicView;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.OpsVideoView;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.RefDraft;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.TopicDraft;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.VideoDraft;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentMapper;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentOpsMapper;
import com.lowaltitude.inspection.ops.common.OpsChangeView;
import com.lowaltitude.inspection.ops.common.OpsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** 运营端“发现内容”（S3-1）：录入校验、上线前必填、版本号、上线下线、专题的商家内容和改动记录。 */
class ContentOpsServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-23T02:00:00Z");
    private static final OffsetDateTime NOW_UTC = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final UUID EAGLE = UUID.fromString("f2000000-0000-4000-8000-000000000001");
    private static final UUID ROBOT = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID CUSTOMER = UUID.fromString("d0000000-0000-4000-8000-000000000001");
    private static final UUID KIT = UUID.fromString("c2000000-0000-4000-8000-000000000101");
    private static final UUID DOG = UUID.fromString("c2000000-0000-4000-8000-000000000401");
    private static final UUID ACTIVITY = UUID.fromString("a2000000-0000-4000-8000-000000000001");
    private static final UUID VENUE = UUID.fromString("b2000000-0000-4000-8000-000000000001");
    private static final String DONGYING = "370500";
    private static final Operator OPERATOR = new Operator(UUID.fromString("90000000-0000-4000-8000-0000000000b1"),
            "平台运营（示例）");
    private static final String COVER = "https://media.example.com/ops-media/cover.webp";

    private final FakeStore store = new FakeStore();
    private final Map<UUID, ContentOpsPorts.Organization> organizations = new HashMap<>(Map.of(
            EAGLE, new ContentOpsPorts.Organization(EAGLE, "鹰眼智能巡检", "PROVIDER"),
            ROBOT, new ContentOpsPorts.Organization(ROBOT, "示例机器人商家", "PROVIDER"),
            CUSTOMER, new ContentOpsPorts.Organization(CUSTOMER, "示例企业客户", "CUSTOMER")));
    private final Set<UUID> soldInDongying = Set.of(KIT, DOG);
    private final ContentOpsService service = new ContentOpsService(store, store,
            id -> Optional.ofNullable(organizations.get(id)),
            code -> Map.of(DONGYING, "东营市", "370100", "济南市").containsKey(code)
                    ? Optional.of("城市") : Optional.empty(),
            (productId, city) -> DONGYING.equals(city) && soldInDongying.contains(productId),
            Clock.fixed(NOW, ZoneOffset.UTC));

    // ---- 图文 ----

    @Test
    void aNewArticleIsADraftWithEveryBlockTypeAndTheAuthorsNameSnapshot() {
        OpsArticleView created = service.createArticle(caseDraft(null, null), OPERATOR);

        assertThat(created.status()).isEqualTo(OpsContentStatus.DRAFT);
        assertThat(created.version()).isZero();
        assertThat(created.publishedAt()).isNull();
        assertThat(created.author().name()).isEqualTo("鹰眼智能巡检");
        assertThat(created.sponsored()).isFalse();
        assertThat(created.blocks()).extracting(block -> block.type()).containsExactly(OpsBlockType.FACTS,
                OpsBlockType.PARAGRAPH, OpsBlockType.HEADING, OpsBlockType.STEPS, OpsBlockType.IMAGE,
                OpsBlockType.LIST, OpsBlockType.TIP, OpsBlockType.NOTICE);
        assertThat(created.blocks().get(4).image()).isEqualTo(new OpsBlockImageView(null, "无人机在园区上空巡查",
                "围墙外侧由无人机补充巡查，机器狗负责地面路线"));
        assertThat(created.blocks().get(1).text()).isEqualTo("第一段\n第二段");
        assertThat(created.blocks().get(3).items()).hasSize(2);
        assertThat(created.refs()).extracting(ref -> ref.refId()).containsExactly(DOG, ACTIVITY);
        // 没封面、配图没地址：上线还差这两样。
        assertThat(created.publishMissing()).containsExactly("coverUrl", "imageUrl");
        assertThat(service.articleChanges(created.articleId())).singleElement().satisfies(change -> {
            assertThat(change.action()).isEqualTo("CREATE");
            assertThat(change.subjectType()).isEqualTo("ARTICLE");
            assertThat(change.operatorName()).isEqualTo("平台运营（示例）");
            assertThat(change.summary()).isEqualTo("新建草稿：一台机器狗如何完成园区夜间巡逻");
        });
    }

    @Test
    void publishingNeedsTheRequiredFieldsAndKeepsTheTimeOnOffline() {
        OpsArticleView draft = service.createArticle(caseDraft(null, null), OPERATOR);

        OpsException incomplete = rejected(() -> service.publishArticle(draft.articleId(), 0, null, OPERATOR));
        assertThat(incomplete.error()).isEqualTo(ContentOpsError.PUBLISH_INCOMPLETE);
        assertThat(incomplete.details()).isEqualTo(Map.of("missing", List.of("coverUrl", "imageUrl")));

        OpsArticleView complete = service.updateArticle(draft.articleId(),
                caseDraft(COVER, "https://media.example.com/ops-media/patrol.png"), 0, OPERATOR);
        assertThat(complete.version()).isEqualTo(1);
        assertThat(complete.publishMissing()).isEmpty();
        // 带旧版本号：别人刚改过。
        assertThat(rejected(() -> service.publishArticle(draft.articleId(), 0, null, OPERATOR)).error())
                .isEqualTo(ContentOpsError.VERSION_CONFLICT);

        OpsArticleView published = service.publishArticle(draft.articleId(), 1, null, OPERATOR);
        assertThat(published.status()).isEqualTo(OpsContentStatus.PUBLISHED);
        assertThat(published.publishedAt()).isEqualTo(NOW_UTC);
        assertThat(rejected(() -> service.publishArticle(draft.articleId(), 2, null, OPERATOR)).error())
                .isEqualTo(ContentOpsError.STATUS_CONFLICT);
        // 已上线的改完也要齐：去掉封面不行。
        assertThat(rejected(() -> service.updateArticle(draft.articleId(), caseDraft(null,
                "https://media.example.com/ops-media/patrol.png"), 2, OPERATOR)).details())
                .isEqualTo(Map.of("missing", List.of("coverUrl")));

        OpsArticleView offline = service.offlineArticle(draft.articleId(), 2, OPERATOR);
        assertThat(offline.status()).isEqualTo(OpsContentStatus.OFFLINE);
        assertThat(offline.publishedAt()).isEqualTo(NOW_UTC);
        assertThat(rejected(() -> service.offlineArticle(draft.articleId(), 3, OPERATOR)).error())
                .isEqualTo(ContentOpsError.STATUS_CONFLICT);

        // 下线的可以再上线，定在以后发布；太早、太晚都不收。
        assertThatThrownBy(() -> service.publishArticle(draft.articleId(), 3, NOW_UTC.minusHours(1), OPERATOR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.publishArticle(draft.articleId(), 3, NOW_UTC.plusDays(400), OPERATOR))
                .isInstanceOf(IllegalArgumentException.class);
        OpsArticleView scheduled = service.publishArticle(draft.articleId(), 3,
                OffsetDateTime.parse("2026-11-25T09:30:00+08:00"), OPERATOR);
        assertThat(scheduled.publishedAt()).isEqualTo(OffsetDateTime.parse("2026-11-25T01:30:00Z"));

        assertThat(service.articleChanges(draft.articleId())).extracting(OpsChangeView::action)
                .containsExactly("PUBLISH", "OFFLINE", "PUBLISH", "UPDATE", "CREATE");
        assertThat(service.articleChanges(draft.articleId()).get(0).summary())
                .isEqualTo("上线，定时发布 11-25 09:30（北京时间）");
        assertThat(service.articleChanges(draft.articleId()).get(2).summary()).isEqualTo("上线，立即发布");
    }

    @Test
    void authorsCitiesAndBlocksAreChecked() {
        // 案例不能由平台发布；机构要是在用的服务商类机构。
        assertThat(rejected(() -> service.createArticle(article("CASE", platform("东营城市运营中心"), DONGYING,
                List.of(paragraph())), OPERATOR)).error()).isEqualTo(ContentOpsError.AUTHOR_INVALID);
        assertThat(rejected(() -> service.createArticle(article("CASE", provider(CUSTOMER), DONGYING,
                List.of(paragraph())), OPERATOR)).error()).isEqualTo(ContentOpsError.AUTHOR_INVALID);
        assertThat(rejected(() -> service.createArticle(article("CASE", provider(UUID.randomUUID()), DONGYING,
                List.of(paragraph())), OPERATOR)).error()).isEqualTo(ContentOpsError.AUTHOR_INVALID);
        assertThat(rejected(() -> service.createArticle(article("CASE", provider(EAGLE), "999999",
                List.of(paragraph())), OPERATOR)).error()).isEqualTo(ContentOpsError.CITY_UNKNOWN);
        invalid(() -> service.createArticle(article("CASE", provider(EAGLE), null, List.of(paragraph())), OPERATOR));

        // 知识可以不分城市；商家发布的是广告；平台署名不带机构。
        OpsArticleView knowledge = service.createArticle(article("KNOWLEDGE",
                new AuthorDraft("MERCHANT", ROBOT, null), null, List.of(paragraph())), OPERATOR);
        assertThat(knowledge.cityCode()).isNull();
        assertThat(knowledge.sponsored()).isTrue();
        assertThat(knowledge.author().kind()).isEqualTo(OpsAuthorKind.MERCHANT);
        invalid(() -> service.createArticle(article("KNOWLEDGE", new AuthorDraft("PLATFORM", EAGLE, "平台"), null,
                List.of(paragraph())), OPERATOR));

        // 块的类型和内容要对上。
        for (BlockDraft bad : List.of(
                new BlockDraft("PARAGRAPH", " ", null, null, null, null),
                new BlockDraft("PARAGRAPH", "有字", List.of(new BlockItemDraft("条目", null)), null, null, null),
                new BlockDraft("HEADING", "很".repeat(61), null, null, null, null),
                new BlockDraft("FACTS", null, List.of(), null, null, null),
                new BlockDraft("STEPS", "多了字", List.of(new BlockItemDraft("一", null)), null, null, null),
                new BlockDraft("IMAGE", null, null, COVER, null, null),
                new BlockDraft("IMAGE", "多了字", null, COVER, "替代", null),
                new BlockDraft("IMAGE", null, null, "http://media.example.com/a.png", "替代", null),
                new BlockDraft("TIP", "提示", null, COVER, null, null),
                new BlockDraft("VIDEO", "x", null, null, null, null))) {
            invalid(() -> service.createArticle(article("KNOWLEDGE", platform("平台"), null, List.of(bad)), OPERATOR));
        }
        // 同一个对象提到两次、封面不是 https 都不收。
        invalid(() -> service.createArticle(new ArticleDraft("KNOWLEDGE", "标题", platform("平台"), null, null, false,
                List.of(paragraph()), List.of(new RefDraft("PRODUCT", DOG, "狗"), new RefDraft("product", DOG, "狗"))),
                OPERATOR));
        invalid(() -> service.createArticle(new ArticleDraft("KNOWLEDGE", "标题", platform("平台"), null,
                "http://media.example.com/a.jpg", false, List.of(paragraph()), List.of()), OPERATOR));
    }

    @Test
    void anArticleWithoutBlocksOrWithAStoppedAuthorCannotGoOnline() {
        OpsArticleView empty = service.createArticle(new ArticleDraft("CASE", "空的", provider(EAGLE), DONGYING, COVER,
                true, List.of(), List.of()), OPERATOR);
        assertThat(empty.publishMissing()).containsExactly("blocks");
        assertThat(empty.sample()).isTrue();

        OpsArticleView ready = service.createArticle(article("CASE", provider(EAGLE), DONGYING, List.of(paragraph())),
                OPERATOR);
        organizations.remove(EAGLE);
        assertThat(rejected(() -> service.publishArticle(ready.articleId(), 0, null, OPERATOR)).details())
                .isEqualTo(Map.of("missing", List.of("author", "coverUrl")));
    }

    @Test
    void listsFilterAndPage() {
        service.createArticle(caseDraft(null, null), OPERATOR);
        service.createArticle(article("KNOWLEDGE", platform("东营城市运营中心"), null, List.of(paragraph())), OPERATOR);
        service.createVideo(video(null, null), OPERATOR);

        assertThat(service.articles(null, null, null, null, 1, 20).items()).hasSize(2);
        assertThat(service.articles(null, "case", null, null, 1, 20).items()).singleElement()
                .satisfies(item -> assertThat(item.kind()).isEqualTo("CASE"));
        assertThat(service.articles("运营中心", null, null, null, 1, 20).items()).hasSize(1);
        assertThat(service.articles(null, null, DONGYING, "DRAFT", 1, 1)).satisfies(page -> {
            assertThat(page.total()).isEqualTo(1);
            assertThat(page.hasMore()).isFalse();
        });
        assertThat(service.articles(null, null, null, null, 1, 1).hasMore()).isTrue();
        assertThat(service.videos(null, null, null, 1, 20).items()).singleElement()
                .satisfies(item -> assertThat(item.kind()).isEqualTo("VIDEO"));
        invalid(() -> service.articles(null, "VIDEO", null, null, 1, 20));
        invalid(() -> service.articles(null, null, "abc", null, 1, 20));
        invalid(() -> service.articles(null, null, null, "GONE", 1, 20));
        invalid(() -> service.articles(null, null, null, null, 1, 51));
    }

    // ---- 视频 ----

    @Test
    void videosNeedACoverAndAPlayAddressAndAreNotArticles() {
        OpsVideoView created = service.createVideo(video(null, null), OPERATOR);
        assertThat(created.tags()).containsExactly("航拍", "黄河口");
        assertThat(created.rank()).isEqualTo(60);
        assertThat(created.durationSeconds()).isEqualTo(95);
        assertThat(created.publishMissing()).containsExactly("coverUrl", "playUrl");
        assertThat(rejected(() -> service.article(created.videoId())).error()).isEqualTo(ContentOpsError.NOT_FOUND);
        assertThat(rejected(() -> service.publishVideo(created.videoId(), 0, null, OPERATOR)).details())
                .isEqualTo(Map.of("missing", List.of("coverUrl", "playUrl")));

        OpsVideoView ready = service.updateVideo(created.videoId(),
                video(COVER, "https://media.example.com/ops-media/clip.mp4"), 0, OPERATOR);
        OpsVideoView published = service.publishVideo(ready.videoId(), ready.version(), null, OPERATOR);
        assertThat(published.status()).isEqualTo(OpsContentStatus.PUBLISHED);
        assertThat(published.playUrl()).isEqualTo("https://media.example.com/ops-media/clip.mp4");
        assertThat(service.videoChanges(created.videoId())).extracting(OpsChangeView::subjectType)
                .containsOnly("VIDEO");

        OpsArticleView article = service.createArticle(caseDraft(null, null), OPERATOR);
        assertThat(rejected(() -> service.video(article.articleId())).error()).isEqualTo(ContentOpsError.NOT_FOUND);

        invalid(() -> service.createVideo(new VideoDraft("标题", provider(EAGLE), DONGYING, null, false, 0,
                "PORTRAIT", null, null, List.of(), null, List.of()), OPERATOR));
        invalid(() -> service.createVideo(new VideoDraft("标题", provider(EAGLE), DONGYING, null, false, 30,
                "PORTRAIT", null, null, List.of("有 空格"), null, List.of()), OPERATOR));
        invalid(() -> service.createVideo(new VideoDraft("标题", provider(EAGLE), DONGYING, null, false, 30,
                "PORTRAIT", null, null, List.of(), 10_000, List.of()), OPERATOR));
        invalid(() -> service.createVideo(new VideoDraft("标题", provider(EAGLE), null, null, false, 30,
                "PORTRAIT", null, null, List.of(), null, List.of()), OPERATOR));
    }

    // ---- 专题 ----

    @Test
    void aTopicHoldsActivitiesVenuesArticlesAndMerchantItems() {
        OpsArticleView article = service.createArticle(caseDraft(null, null), OPERATOR);
        OpsVideoView video = service.createVideo(video(null, null), OPERATOR);

        OpsTopicView created = service.createTopic(topic(COVER, List.of(article.articleId()),
                List.of(new MerchantItemDraft(KIT, ROBOT))), OPERATOR);

        assertThat(created.status()).isEqualTo(OpsContentStatus.DRAFT);
        assertThat(created.activityIds()).containsExactly(ACTIVITY);
        assertThat(created.venueIds()).containsExactly(VENUE);
        assertThat(created.articleIds()).containsExactly(article.articleId());
        assertThat(created.merchantItems()).singleElement().satisfies(item -> {
            assertThat(item.productId()).isEqualTo(KIT);
            assertThat(item.merchant().kind()).isEqualTo(OpsAuthorKind.MERCHANT);
            assertThat(item.merchant().organizationId()).isEqualTo(ROBOT);
            assertThat(item.merchant().name()).isEqualTo("示例机器人商家");
        });
        assertThat(created.startsOn()).isEqualTo("2026-11-01");
        assertThat(created.publishMissing()).isEmpty();
        assertThat(store.topicItems.get(created.topicId())).extracting(ContentMapper.TopicItemRow::itemType)
                .containsExactly("ACTIVITY", "VENUE", "ARTICLE", "PRODUCT");

        // 挂的图文要是本模块的图文；商品要在专题的城市有卖；商家要是在用的服务商类机构。
        assertThat(rejected(() -> service.createTopic(topic(COVER, List.of(video.videoId()), List.of()), OPERATOR))
                .error()).isEqualTo(ContentOpsError.ARTICLE_UNKNOWN);
        assertThat(rejected(() -> service.createTopic(topic(COVER, List.of(UUID.randomUUID()), List.of()), OPERATOR))
                .error()).isEqualTo(ContentOpsError.ARTICLE_UNKNOWN);
        assertThat(rejected(() -> service.createTopic(topic(COVER, List.of(),
                List.of(new MerchantItemDraft(UUID.randomUUID(), ROBOT))), OPERATOR)).error())
                .isEqualTo(ContentOpsError.PRODUCT_UNKNOWN);
        assertThat(rejected(() -> service.createTopic(topic(COVER, List.of(),
                List.of(new MerchantItemDraft(KIT, CUSTOMER))), OPERATOR)).error())
                .isEqualTo(ContentOpsError.AUTHOR_INVALID);
        invalid(() -> service.createTopic(topic(COVER, List.of(),
                List.of(new MerchantItemDraft(KIT, ROBOT), new MerchantItemDraft(KIT, ROBOT))), OPERATOR));
        invalid(() -> service.createTopic(new TopicDraft("专题", null, platform("东营城市运营中心"), DONGYING,
                "2026-11-30", "2026-11-01", null, true, null, null, null, null), OPERATOR));
        invalid(() -> service.createTopic(new TopicDraft("专题", null, platform("东营城市运营中心"), DONGYING,
                "11/01", "2026-11-30", null, true, null, null, null, null), OPERATOR));

        OpsTopicView published = service.publishTopic(created.topicId(), 0, OPERATOR);
        assertThat(published.status()).isEqualTo(OpsContentStatus.PUBLISHED);
        assertThat(service.offlineTopic(created.topicId(), 1, OPERATOR).status()).isEqualTo(OpsContentStatus.OFFLINE);
        assertThat(service.topicChanges(created.topicId())).extracting(OpsChangeView::action)
                .containsExactly("OFFLINE", "PUBLISH", "CREATE");
        assertThat(service.topics(null, DONGYING, "OFFLINE", 1, 20).items()).singleElement()
                .satisfies(item -> assertThat(item.publisherName()).isEqualTo("东营城市运营中心"));
    }

    @Test
    void anEmptyTopicCannotGoOnlineAndAnUpdateReplacesItsItems() {
        OpsTopicView empty = service.createTopic(new TopicDraft("黄河口低空体验季", "到馆体验、赛事、研学合集",
                platform("东营城市运营中心"), DONGYING, "2026-11-01", "2026-11-30", null, true, null, null, null,
                null), OPERATOR);
        assertThat(empty.publishMissing()).containsExactly("coverUrl", "items");
        assertThat(rejected(() -> service.publishTopic(empty.topicId(), 0, OPERATOR)).error())
                .isEqualTo(ContentOpsError.PUBLISH_INCOMPLETE);
        assertThat(rejected(() -> service.offlineTopic(empty.topicId(), 0, OPERATOR)).error())
                .isEqualTo(ContentOpsError.STATUS_CONFLICT);

        OpsTopicView updated = service.updateTopic(empty.topicId(), topic(COVER, List.of(),
                List.of(new MerchantItemDraft(DOG, EAGLE))), 0, OPERATOR);
        assertThat(updated.merchantItems()).extracting(item -> item.productId()).containsExactly(DOG);
        assertThat(updated.version()).isEqualTo(1);
        assertThat(rejected(() -> service.updateTopic(empty.topicId(), topic(COVER, List.of(), List.of()), 0,
                OPERATOR)).error()).isEqualTo(ContentOpsError.VERSION_CONFLICT);
        assertThat(rejected(() -> service.topic(UUID.randomUUID())).error()).isEqualTo(ContentOpsError.NOT_FOUND);
    }

    // ---- 小工具 ----

    private static AuthorDraft provider(UUID organizationId) {
        return new AuthorDraft("PROVIDER", organizationId, null);
    }

    private static AuthorDraft platform(String name) {
        return new AuthorDraft("PLATFORM", null, name);
    }

    private static BlockDraft paragraph() {
        return new BlockDraft("PARAGRAPH", "一段正文", null, null, null, null);
    }

    private static ArticleDraft article(String kind, AuthorDraft author, String city, List<BlockDraft> blocks) {
        return new ArticleDraft(kind, "标题", author, city, null, false, blocks, List.of());
    }

    /** 画板 ContentArticle 的案例：全部块类型，“怎么巡”后面一张配图。 */
    private static ArticleDraft caseDraft(String cover, String imageUrl) {
        return new ArticleDraft("CASE", " 一台机器狗如何完成园区夜间巡逻 ", provider(EAGLE), DONGYING, cover, true, List.of(
                new BlockDraft("FACTS", null, List.of(new BlockItemDraft("场景", "物流园区夜间安防")), null, null, null),
                new BlockDraft("PARAGRAPH", "第一段\r\n第二段", null, null, null, null),
                new BlockDraft("HEADING", "怎么巡", null, null, null, null),
                new BlockDraft("STEPS", null, List.of(new BlockItemDraft("白天踏勘，定路线", "服务商到现场走一遍。"),
                        new BlockItemDraft("夜间按路线巡逻", null)), null, null, null),
                new BlockDraft("IMAGE", null, null, imageUrl, "无人机在园区上空巡查", "围墙外侧由无人机补充巡查，机器狗负责地面路线"),
                new BlockDraft("LIST", null, List.of(new BlockItemDraft("巡检报告", "每趟的路线、点位和时间")), null, null,
                        null),
                new BlockDraft("TIP", "提示", null, null, null, null),
                new BlockDraft("NOTICE", "案例由服务商提供。", null, null, null, null)),
                List.of(new RefDraft("PRODUCT", DOG, "巡检机器狗"), new RefDraft("ACTIVITY", ACTIVITY, "编程挑战赛")));
    }

    private static VideoDraft video(String cover, String playUrl) {
        return new VideoDraft("4K 航拍黄河口", provider(EAGLE), DONGYING, cover, true, 95, "LANDSCAPE", playUrl,
                "一句话说明", List.of("#航拍", "黄河口", "航拍"), 60, List.of(new RefDraft("PRODUCT", DOG, "巡检机器狗")));
    }

    private static TopicDraft topic(String cover, List<UUID> articles, List<MerchantItemDraft> merchantItems) {
        return new TopicDraft("黄河口低空体验季", "到馆体验、赛事、研学合集", platform("东营城市运营中心"), DONGYING,
                "2026-11-01", "2026-11-30", cover, true, List.of(ACTIVITY), List.of(VENUE), articles, merchantItems);
    }

    private static OpsException rejected(Executable call) {
        try {
            call.execute();
        } catch (OpsException rejection) {
            return rejection;
        } catch (Throwable other) {
            throw new AssertionError("expected an OpsException", other);
        }
        throw new AssertionError("expected an OpsException");
    }

    private static void invalid(Executable call) {
        assertThatThrownBy(call::execute).isInstanceOf(IllegalArgumentException.class);
    }

    /** 内存里的内容表：运营端的读写和公开读取共用的那几个查询。 */
    static final class FakeStore implements ContentOpsMapper, ContentMapper {

        final Map<UUID, OpsArticleRow> articles = new LinkedHashMap<>();
        final List<BlockRow> blocks = new ArrayList<>();
        final Map<UUID, UUID> blockArticle = new HashMap<>();
        final List<BlockItemRow> blockItems = new ArrayList<>();
        final Map<UUID, List<RefRow>> refs = new HashMap<>();
        final Map<UUID, OpsTopicRow> topics = new LinkedHashMap<>();
        final Map<UUID, List<TopicItemRow>> topicItems = new HashMap<>();
        final List<ChangeRow> changes = new ArrayList<>();

        private List<OpsArticleRow> filtered(boolean video, String kind, String cityCode, String status,
                                             String keyword) {
            return articles.values().stream()
                    .filter(row -> "VIDEO".equals(row.kind()) == video)
                    .filter(row -> kind == null || kind.equals(row.kind()))
                    .filter(row -> cityCode == null || cityCode.equals(row.cityCode()))
                    .filter(row -> status == null || status.equals(row.status()))
                    .filter(row -> keyword == null || row.title().contains(keyword)
                            || row.authorName().contains(keyword))
                    .sorted(Comparator.comparing(OpsArticleRow::updatedAt).reversed())
                    .toList();
        }

        @Override
        public long countArticles(boolean video, String kind, String cityCode, String status, String keyword) {
            return filtered(video, kind, cityCode, status, keyword).size();
        }

        @Override
        public List<OpsArticleRow> findArticles(boolean video, String kind, String cityCode, String status,
                                                String keyword, int limit, int offset) {
            return filtered(video, kind, cityCode, status, keyword).stream().skip(offset).limit(limit).toList();
        }

        @Override
        public OpsArticleRow findArticle(UUID id) {
            return articles.get(id);
        }

        @Override
        public OpsArticleRow lockArticle(UUID id) {
            return articles.get(id);
        }

        @Override
        public int countPlainArticle(UUID id) {
            OpsArticleRow row = articles.get(id);
            return row != null && !"VIDEO".equals(row.kind()) ? 1 : 0;
        }

        @Override
        public int insertArticle(ArticleWrite row, OffsetDateTime at) {
            articles.put(row.id(), new OpsArticleRow(row.id(), row.kind(), row.title(), row.authorKind(),
                    row.authorOrgId(), row.authorName(), row.sponsored(), row.cityCode(), row.coverUrl(), row.sample(),
                    "DRAFT", null, row.videoDurationSeconds(), row.videoOrientation(), row.videoUrl(),
                    row.videoCaption(), row.videoTags(), row.videoRank(), 0, at.plusNanos(articles.size())));
            return 1;
        }

        @Override
        public int updateArticle(ArticleWrite row, int version, OffsetDateTime at) {
            OpsArticleRow current = articles.get(row.id());
            if (current == null || current.version() != version) {
                return 0;
            }
            articles.put(row.id(), new OpsArticleRow(row.id(), row.kind(), row.title(), row.authorKind(),
                    row.authorOrgId(), row.authorName(), row.sponsored(), row.cityCode(), row.coverUrl(), row.sample(),
                    current.status(), current.publishedAt(), row.videoDurationSeconds(), row.videoOrientation(),
                    row.videoUrl(), row.videoCaption(), row.videoTags(), row.videoRank(), version + 1, at));
            return 1;
        }

        @Override
        public int updateArticleStatus(UUID id, String status, OffsetDateTime publishedAt, int version,
                                       OffsetDateTime at) {
            OpsArticleRow c = articles.get(id);
            if (c == null || c.version() != version) {
                return 0;
            }
            articles.put(id, new OpsArticleRow(c.id(), c.kind(), c.title(), c.authorKind(), c.authorOrgId(),
                    c.authorName(), c.sponsored(), c.cityCode(), c.coverUrl(), c.sample(), status, publishedAt,
                    c.videoDurationSeconds(), c.videoOrientation(), c.videoUrl(), c.videoCaption(), c.videoTags(),
                    c.videoRank(), version + 1, at));
            return 1;
        }

        @Override
        public int deleteBlocks(UUID articleId) {
            List<UUID> gone = blocks.stream().map(BlockRow::id).filter(id -> articleId.equals(blockArticle.get(id)))
                    .toList();
            blocks.removeIf(block -> gone.contains(block.id()));
            blockItems.removeIf(item -> gone.contains(item.blockId()));
            return gone.size();
        }

        @Override
        public int insertBlock(UUID id, UUID articleId, int sortOrder, String blockType, String text, String imageUrl,
                               String imageAlt, String imageCaption) {
            blocks.add(new BlockRow(id, blockType, text, imageUrl, imageAlt, imageCaption));
            blockArticle.put(id, articleId);
            return 1;
        }

        @Override
        public int insertBlockItem(UUID blockId, int sortOrder, String title, String text) {
            blockItems.add(new BlockItemRow(blockId, title, text));
            return 1;
        }

        @Override
        public int deleteRefs(UUID articleId) {
            List<RefRow> removed = refs.remove(articleId);
            return removed == null ? 0 : removed.size();
        }

        @Override
        public int insertRef(UUID articleId, int sortOrder, String refType, UUID refId, String title) {
            refs.computeIfAbsent(articleId, id -> new ArrayList<>()).add(new RefRow(refType, refId, title));
            return 1;
        }

        private List<OpsTopicRow> filteredTopics(String cityCode, String status, String keyword) {
            return topics.values().stream()
                    .filter(row -> cityCode == null || cityCode.equals(row.cityCode()))
                    .filter(row -> status == null || status.equals(row.status()))
                    .filter(row -> keyword == null || row.title().contains(keyword))
                    .sorted(Comparator.comparing(OpsTopicRow::updatedAt).reversed())
                    .toList();
        }

        @Override
        public long countTopics(String cityCode, String status, String keyword) {
            return filteredTopics(cityCode, status, keyword).size();
        }

        @Override
        public List<OpsTopicRow> findTopics(String cityCode, String status, String keyword, int limit, int offset) {
            return filteredTopics(cityCode, status, keyword).stream().skip(offset).limit(limit).toList();
        }

        @Override
        public OpsTopicRow findTopic(UUID id) {
            return topics.get(id);
        }

        @Override
        public OpsTopicRow lockTopic(UUID id) {
            return topics.get(id);
        }

        @Override
        public int insertTopic(TopicWrite row, OffsetDateTime at) {
            topics.put(row.id(), new OpsTopicRow(row.id(), row.title(), row.subtitle(), row.publisherKind(),
                    row.publisherOrgId(), row.publisherName(), row.cityCode(), row.startsOn(), row.endsOn(),
                    row.coverUrl(), row.sample(), "DRAFT", 0, at));
            return 1;
        }

        @Override
        public int updateTopic(TopicWrite row, int version, OffsetDateTime at) {
            OpsTopicRow c = topics.get(row.id());
            if (c == null || c.version() != version) {
                return 0;
            }
            topics.put(row.id(), new OpsTopicRow(row.id(), row.title(), row.subtitle(), row.publisherKind(),
                    row.publisherOrgId(), row.publisherName(), row.cityCode(), row.startsOn(), row.endsOn(),
                    row.coverUrl(), row.sample(), c.status(), version + 1, at));
            return 1;
        }

        @Override
        public int updateTopicStatus(UUID id, String status, int version, OffsetDateTime at) {
            OpsTopicRow c = topics.get(id);
            if (c == null || c.version() != version) {
                return 0;
            }
            topics.put(id, new OpsTopicRow(c.id(), c.title(), c.subtitle(), c.publisherKind(), c.publisherOrgId(),
                    c.publisherName(), c.cityCode(), c.startsOn(), c.endsOn(), c.coverUrl(), c.sample(), status,
                    version + 1, at));
            return 1;
        }

        @Override
        public int deleteTopicItems(UUID topicId) {
            List<TopicItemRow> removed = topicItems.remove(topicId);
            return removed == null ? 0 : removed.size();
        }

        @Override
        public int insertTopicItem(UUID topicId, int sortOrder, String itemType, UUID itemId, UUID merchantOrgId,
                                   String merchantName) {
            topicItems.computeIfAbsent(topicId, id -> new ArrayList<>())
                    .add(new TopicItemRow(itemType, itemId, merchantOrgId, merchantName));
            return 1;
        }

        @Override
        public int insertChange(UUID id, String subjectType, UUID subjectId, String action, String summary,
                                UUID operatorId, String operatorName, OffsetDateTime at) {
            changes.add(new ChangeRow(id, subjectType, subjectId, action, summary, operatorName,
                    at.plusNanos(changes.size())));
            return 1;
        }

        @Override
        public List<ChangeRow> findChanges(String subjectType, UUID subjectId, int limit) {
            return changes.stream()
                    .filter(change -> change.subjectType().equals(subjectType) && change.subjectId().equals(subjectId))
                    .sorted(Comparator.comparing(ChangeRow::changedAt).reversed())
                    .limit(limit)
                    .toList();
        }

        // ---- 公开读取那边用到的 ----

        @Override
        public List<BlockRow> findBlocks(UUID articleId) {
            return blocks.stream().filter(block -> articleId.equals(blockArticle.get(block.id()))).toList();
        }

        @Override
        public List<BlockItemRow> findBlockItems(UUID articleId) {
            return blockItems.stream().filter(item -> articleId.equals(blockArticle.get(item.blockId()))).toList();
        }

        @Override
        public List<RefRow> findRefs(UUID articleId) {
            return refs.getOrDefault(articleId, List.of());
        }

        @Override
        public List<TopicItemRow> findTopicItems(UUID topicId) {
            return topicItems.getOrDefault(topicId, List.of());
        }

        @Override
        public long countFeed(String cityCode, String kind, OffsetDateTime now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ArticleRow> findFeedPage(String cityCode, String kind, OffsetDateTime now, int limit, int offset) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ArticleRow> findVideoFeedPage(String cityCode, String kind, OffsetDateTime now, int limit,
                                                  int offset) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ArticleRow findArticle(UUID articleId, String cityCode, OffsetDateTime now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ArticleRow> findMentioning(String refType, UUID refId, String cityCode, OffsetDateTime now,
                                               int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TopicRow> findCurrentTopics(String cityCode, LocalDate today) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TopicRow findTopic(UUID topicId, String cityCode) {
            throw new UnsupportedOperationException();
        }
    }
}
