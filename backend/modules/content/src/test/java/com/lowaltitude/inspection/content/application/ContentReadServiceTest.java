package com.lowaltitude.inspection.content.application;

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
import java.util.UUID;
import java.util.function.Predicate;

import com.lowaltitude.inspection.content.infrastructure.persistence.ContentMapper;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * 发现页图文内容和专题的读取规则（H1，§27.1）。mapper 换成内存实现（按 ContentMapper 的 SQL 条件写）。
 * SQL 文本由 ContentMapperContractTest 检查，真实库上由一次性库上的迁移和开发种子检查。
 */
class ContentReadServiceTest {

    /** 北京时间 2026-11-20 00:30：“今天”按北京时间算。 */
    private static final Instant NOW = Instant.parse("2026-11-19T16:30:00Z");
    private static final OffsetDateTime NOW_UTC = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final String DONGYING = "370500";
    private static final String JINAN = "370100";
    private static final UUID EAGLE = UUID.fromString("f2000000-0000-4000-8000-000000000001");
    private static final UUID ROBOT = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID DOG = UUID.fromString("c2000000-0000-4000-8000-000000000401");
    private static final UUID ACTIVITY = UUID.fromString("a2000000-0000-4000-8000-000000000001");
    private static final UUID VENUE = UUID.fromString("b2000000-0000-4000-8000-000000000001");

    private final InMemoryMapper mapper = new InMemoryMapper();
    private final ContentReadService service = new ContentReadService(mapper, Clock.fixed(NOW, ZoneOffset.UTC));

    private final ContentMapper.ArticleRow dogCase = mapper.article("CASE", "一台机器狗如何完成园区夜间巡逻", "PROVIDER", EAGLE,
            "鹰眼智能巡检", false, DONGYING, 22);
    private final ContentMapper.ArticleRow buyDrone = mapper.article("KNOWLEDGE", "新手买无人机前，先弄清这 4 件事", "PLATFORM",
            null, "东营城市运营中心", false, null, 17);
    private final ContentMapper.ArticleRow robotTips = mapper.article("KNOWLEDGE", "给孩子选编程机器人，看这 5 点", "MERCHANT",
            ROBOT, "示例机器人商家", true, null, 24);
    private final ContentMapper.ArticleRow jinanCase = mapper.article("CASE", "济南的案例", "PROVIDER", EAGLE, "鹰眼智能巡检",
            false, JINAN, 1);

    // ---- 发现页 ----

    @Test
    void theRecommendFeedHasTheCitysContentAndCitylessKnowledgeNewestFirst() {
        ContentFeedView feed = service.feed(DONGYING, null, 1, 20);

        assertThat(feed.channel()).isEqualTo(ContentFeedChannel.RECOMMEND);
        assertThat(feed.items()).extracting(ContentArticleCardView::title).containsExactly(
                "新手买无人机前，先弄清这 4 件事", "一台机器狗如何完成园区夜间巡逻", "给孩子选编程机器人，看这 5 点");
        assertThat(feed.total()).isEqualTo(3);
        assertThat(feed.hasMore()).isFalse();
        assertThat(feed.items().get(1).author()).isEqualTo(new ContentAuthorView(ContentAuthorKind.PROVIDER, EAGLE,
                "鹰眼智能巡检"));
        assertThat(feed.items().get(0).author().organizationId()).isNull();
        assertThat(feed.items().get(0).cityCode()).isNull();
        assertThat(feed.items().get(2).sponsored()).isTrue();
        assertThat(feed.items().get(0).publishedAt()).isEqualTo(NOW_UTC.minusDays(17));
        // 济南只看到济南的案例和不分城市的知识。
        assertThat(service.feed(JINAN, "RECOMMEND", 1, 20).items()).extracting(ContentArticleCardView::title)
                .containsExactly("济南的案例", "新手买无人机前，先弄清这 4 件事", "给孩子选编程机器人，看这 5 点");
    }

    @Test
    void caseAndKnowledgeChannelsFilterByKindAndPagesCount() {
        assertThat(service.feed(DONGYING, "case", 1, 20).items()).extracting(ContentArticleCardView::kind)
                .containsExactly(ContentArticleKind.CASE);
        ContentFeedView knowledge = service.feed(DONGYING, "KNOWLEDGE", 1, 1);
        assertThat(knowledge.items()).extracting(ContentArticleCardView::title)
                .containsExactly("新手买无人机前，先弄清这 4 件事");
        assertThat(knowledge.total()).isEqualTo(2);
        assertThat(knowledge.hasMore()).isTrue();
        assertThat(service.feed(DONGYING, "KNOWLEDGE", 2, 1).hasMore()).isFalse();
        assertInvalid(() -> service.feed(DONGYING, "VIDEO", 1, 20));
        assertInvalid(() -> service.feed(DONGYING, "CONTEST", 1, 20));
        assertInvalid(() -> service.feed("dongying", null, 1, 20));
        assertInvalid(() -> service.feed(null, null, 1, 20));
        assertInvalid(() -> service.feed(DONGYING, null, 0, 20));
        assertInvalid(() -> service.feed(DONGYING, null, 1, 51));
    }

    @Test
    void draftsOfflineAndFutureArticlesAreNotShown() {
        mapper.status(mapper.article("CASE", "草稿", "PROVIDER", EAGLE, "鹰眼智能巡检", false, DONGYING, 1), "DRAFT");
        mapper.status(mapper.article("CASE", "下线", "PROVIDER", EAGLE, "鹰眼智能巡检", false, DONGYING, 1), "OFFLINE");
        mapper.article("CASE", "明天发布", "PROVIDER", EAGLE, "鹰眼智能巡检", false, DONGYING, -1);

        assertThat(service.feed(DONGYING, null, 1, 20).items()).extracting(ContentArticleCardView::title)
                .doesNotContain("草稿", "下线", "明天发布");
        assertThat(service.feed(DONGYING, null, 1, 20).total()).isEqualTo(3);
    }

    @Test
    void currentTopicsOfTheCityComeOnTheFirstRecommendPageOnly() {
        LocalDate today = LocalDate.parse("2026-11-20");
        mapper.topic("黄河口低空体验季", DONGYING, today.minusDays(19), today.plusDays(10));
        mapper.topic("今天结束", DONGYING, today.minusDays(5), today);
        mapper.topic("已结束", DONGYING, today.minusDays(30), today.minusDays(1));
        mapper.topic("明天开始", DONGYING, today.plusDays(1), today.plusDays(9));
        mapper.topic("济南专题", JINAN, today.minusDays(1), today.plusDays(1));
        mapper.topic("草稿专题", DONGYING, today.minusDays(1), today.plusDays(1)).status = "DRAFT";

        ContentFeedView feed = service.feed(DONGYING, "RECOMMEND", 1, 20);

        assertThat(feed.topics()).extracting(ContentTopicCardView::title).containsExactly("今天结束", "黄河口低空体验季");
        assertThat(feed.topics().get(1).publisher())
                .isEqualTo(new ContentAuthorView(ContentAuthorKind.PLATFORM, null, "东营城市运营中心"));
        assertThat(feed.topics().get(1).subtitle()).isEqualTo("到馆体验、赛事、研学合集");
        assertThat(service.feed(DONGYING, "RECOMMEND", 2, 20).topics()).isEmpty();
        assertThat(service.feed(DONGYING, "CASE", 1, 20).topics()).isEmpty();
        assertThat(mapper.askedToday).containsOnly(today);
    }

    // ---- 内容详情 ----

    @Test
    void anArticleHasItsBlocksInOrderAndItsRefsByStableId() {
        UUID facts = mapper.block(dogCase, "FACTS", null, List.of(new ContentBlockItemView("场景", "物流园区夜间安防")));
        mapper.block(dogCase, "PARAGRAPH", "白天人多车多，夜里出问题的往往是周界和堆场角落。", List.of());
        mapper.block(dogCase, "HEADING", "怎么巡", List.of());
        mapper.block(dogCase, "STEPS", null, List.of(new ContentBlockItemView("白天踏勘，定路线", "服务商到现场走一遍。"),
                new ContentBlockItemView("夜间按路线巡逻", "机器狗按预设路线走。")));
        mapper.block(dogCase, "LIST", null, List.of(new ContentBlockItemView("影像存档", " ")));
        mapper.ref(dogCase, "INSPECTION_SERVICE", UUID.fromString("77777777-7777-7777-7777-777777777777"), "园区综合巡检服务");
        mapper.ref(dogCase, "PRODUCT", DOG, "巡检机器狗");

        ContentArticleView view = service.article(dogCase.id(), DONGYING);

        assertThat(view.title()).isEqualTo("一台机器狗如何完成园区夜间巡逻");
        assertThat(view.blocks()).extracting(ContentBlockView::type).containsExactly(ContentBlockType.FACTS,
                ContentBlockType.PARAGRAPH, ContentBlockType.HEADING, ContentBlockType.STEPS, ContentBlockType.LIST);
        assertThat(view.blocks().get(0).items()).containsExactly(new ContentBlockItemView("场景", "物流园区夜间安防"));
        assertThat(view.blocks().get(0).text()).isNull();
        assertThat(view.blocks().get(1).items()).isEmpty();
        assertThat(view.blocks().get(3).items()).extracting(ContentBlockItemView::title)
                .containsExactly("白天踏勘，定路线", "夜间按路线巡逻");
        assertThat(view.blocks().get(4).items()).containsExactly(new ContentBlockItemView("影像存档", null));
        assertThat(view.refs()).extracting(ContentRefView::type, ContentRefView::refId).containsExactly(
                tuple(ContentRefType.INSPECTION_SERVICE, UUID.fromString("77777777-7777-7777-7777-777777777777")),
                tuple(ContentRefType.PRODUCT, DOG));
        assertThat(facts).isNotNull();
    }

    @Test
    void anArticleNotVisibleInTheCityIsNotFound() {
        assertRejected(() -> service.article(jinanCase.id(), DONGYING));
        assertRejected(() -> service.article(UUID.randomUUID(), DONGYING));
        assertThat(service.article(buyDrone.id(), JINAN).cityCode()).isNull();
        ContentMapper.ArticleRow offline = mapper.article("CASE", "下线", "PROVIDER", EAGLE, "鹰眼智能巡检", false,
                DONGYING, 1);
        mapper.status(offline, "OFFLINE");
        assertRejected(() -> service.article(offline.id(), DONGYING));
        assertInvalid(() -> service.article(null, DONGYING));
        assertInvalid(() -> service.article(dogCase.id(), ""));
    }

    @Test
    void onlyHttpsCoversWithAHostAreShown() {
        assertThat(ContentReadService.secureMedia("https://media.example.com/a.jpg", "题"))
                .isEqualTo(new ContentMediaView("https://media.example.com/a.jpg", "题"));
        assertThat(ContentReadService.secureMedia("http://media.example.com/a.jpg", "题")).isNull();
        assertThat(ContentReadService.secureMedia("https:///a.jpg", "题")).isNull();
        assertThat(ContentReadService.secureMedia(" ", "题")).isNull();
        assertThat(ContentReadService.secureMedia("https://bad host/a.jpg", "题")).isNull();
        assertThat(service.article(dogCase.id(), DONGYING).coverImage()).isNull();
    }

    // ---- 专题 ----

    @Test
    void aTopicListsItsActivitiesVenuesAndVisibleArticlesInOrder() {
        LocalDate today = LocalDate.parse("2026-11-20");
        MutableTopic topic = mapper.topic("黄河口低空体验季", DONGYING, today.minusDays(40), today.minusDays(10));
        UUID second = UUID.fromString("a2000000-0000-4000-8000-000000000005");
        mapper.item(topic, "ACTIVITY", ACTIVITY);
        mapper.item(topic, "ARTICLE", jinanCase.id());
        mapper.item(topic, "VENUE", VENUE);
        mapper.item(topic, "ARTICLE", dogCase.id());
        mapper.item(topic, "ACTIVITY", second);
        mapper.item(topic, "ARTICLE", buyDrone.id());

        // 过了期的专题按链接仍能打开。
        ContentTopicView view = service.topic(topic.id, DONGYING);

        assertThat(view.title()).isEqualTo("黄河口低空体验季");
        assertThat(view.cityCode()).isEqualTo(DONGYING);
        assertThat(view.startsOn()).isEqualTo(today.minusDays(40));
        assertThat(view.activityIds()).containsExactly(ACTIVITY, second);
        assertThat(view.venueIds()).containsExactly(VENUE);
        // 济南的案例在东营看不到，不给出。
        assertThat(view.articles()).extracting(ContentArticleCardView::articleId).containsExactly(dogCase.id(), buyDrone.id());
        assertThat(view.sample()).isTrue();
    }

    @Test
    void aTopicOfAnotherCityOrUnpublishedIsNotFound() {
        LocalDate today = LocalDate.parse("2026-11-20");
        MutableTopic jinan = mapper.topic("济南专题", JINAN, today, today);
        MutableTopic draft = mapper.topic("草稿专题", DONGYING, today, today);
        draft.status = "DRAFT";

        assertRejected(() -> service.topic(jinan.id, DONGYING));
        assertRejected(() -> service.topic(draft.id, DONGYING));
        assertRejected(() -> service.topic(UUID.randomUUID(), DONGYING));
        assertInvalid(() -> service.topic(null, DONGYING));
        assertInvalid(() -> service.topic(jinan.id, "x"));
    }

    // ---- 辅助 ----

    private static void assertRejected(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(ContentNotFoundException.class);
    }

    private static void assertInvalid(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(IllegalArgumentException.class);
    }

    static final class MutableTopic {
        UUID id = UUID.randomUUID();
        String title;
        String city;
        LocalDate starts;
        LocalDate ends;
        String status = "PUBLISHED";
        final List<ContentMapper.TopicItemRow> items = new ArrayList<>();
    }

    /** 内存里的 mapper：条件按 ContentMapper 的 SQL 写。 */
    private static final class InMemoryMapper implements ContentMapper {

        final List<ArticleRow> articles = new ArrayList<>();
        final List<String> statuses = new ArrayList<>();
        final List<BlockRow> blocks = new ArrayList<>();
        final List<UUID> blockArticles = new ArrayList<>();
        final List<BlockItemRow> items = new ArrayList<>();
        final List<UUID> refArticles = new ArrayList<>();
        final List<RefRow> refs = new ArrayList<>();
        final List<MutableTopic> topics = new ArrayList<>();
        final List<LocalDate> askedToday = new ArrayList<>();

        /** status 默认 PUBLISHED（用 status(...) 改成草稿、下线）。daysAgo 为负表示还没到发布时间。 */
        ArticleRow article(String kind, String title, String authorKind, UUID org, String author, boolean sponsored,
                              String city, int daysAgo) {
            ArticleRow row = new ArticleRow(UUID.randomUUID(), kind, title, authorKind, org, author, sponsored, city,
                    null, true, NOW_UTC.minusDays(daysAgo));
            articles.add(row);
            statuses.add("PUBLISHED");
            return row;
        }

        void status(ArticleRow row, String status) {
            statuses.set(articles.indexOf(row), status);
        }

        UUID block(ArticleRow article, String type, String text, List<ContentBlockItemView> blockItems) {
            BlockRow row = new BlockRow(UUID.randomUUID(), type, text);
            blocks.add(row);
            blockArticles.add(article.id());
            for (ContentBlockItemView item : blockItems) {
                items.add(new BlockItemRow(row.id(), item.title(), item.text()));
            }
            return row.id();
        }

        void ref(ArticleRow article, String type, UUID id, String title) {
            refArticles.add(article.id());
            refs.add(new RefRow(type, id, title));
        }

        MutableTopic topic(String title, String city, LocalDate starts, LocalDate ends) {
            MutableTopic topic = new MutableTopic();
            topic.title = title;
            topic.city = city;
            topic.starts = starts;
            topic.ends = ends;
            topics.add(topic);
            return topic;
        }

        void item(MutableTopic topic, String type, UUID id) {
            topic.items.add(new TopicItemRow(type, id));
        }

        private Predicate<Integer> visible(String cityCode, OffsetDateTime now) {
            return i -> statuses.get(i).equals("PUBLISHED") && !articles.get(i).publishedAt().isAfter(now)
                    && (articles.get(i).cityCode() == null || articles.get(i).cityCode().equals(cityCode));
        }

        private List<ArticleRow> feed(String cityCode, String kind, OffsetDateTime now) {
            List<ArticleRow> found = new ArrayList<>();
            for (int i = 0; i < articles.size(); i++) {
                if (visible(cityCode, now).test(i) && (kind == null || articles.get(i).kind().equals(kind))) {
                    found.add(articles.get(i));
                }
            }
            found.sort(Comparator.comparing(ArticleRow::publishedAt).reversed());
            return found;
        }

        @Override
        public long countFeed(String cityCode, String kind, OffsetDateTime now) {
            return feed(cityCode, kind, now).size();
        }

        @Override
        public List<ArticleRow> findFeedPage(String cityCode, String kind, OffsetDateTime now, int limit, int offset) {
            return feed(cityCode, kind, now).stream().skip(offset).limit(limit).toList();
        }

        @Override
        public ArticleRow findArticle(UUID articleId, String cityCode, OffsetDateTime now) {
            for (int i = 0; i < articles.size(); i++) {
                if (articles.get(i).id().equals(articleId) && visible(cityCode, now).test(i)) {
                    return articles.get(i);
                }
            }
            return null;
        }

        @Override
        public List<BlockRow> findBlocks(UUID articleId) {
            List<BlockRow> found = new ArrayList<>();
            for (int i = 0; i < blocks.size(); i++) {
                if (blockArticles.get(i).equals(articleId)) {
                    found.add(blocks.get(i));
                }
            }
            return found;
        }

        @Override
        public List<BlockItemRow> findBlockItems(UUID articleId) {
            List<UUID> mine = findBlocks(articleId).stream().map(BlockRow::id).toList();
            return items.stream().filter(item -> mine.contains(item.blockId())).toList();
        }

        @Override
        public List<RefRow> findRefs(UUID articleId) {
            List<RefRow> found = new ArrayList<>();
            for (int i = 0; i < refs.size(); i++) {
                if (refArticles.get(i).equals(articleId)) {
                    found.add(refs.get(i));
                }
            }
            return found;
        }

        private TopicRow row(MutableTopic t) {
            return new TopicRow(t.id, t.title, t.title.equals("黄河口低空体验季") ? "到馆体验、赛事、研学合集" : " ",
                    "PLATFORM", null, "东营城市运营中心", t.city, t.starts, t.ends, null, true);
        }

        @Override
        public List<TopicRow> findCurrentTopics(String cityCode, LocalDate today) {
            askedToday.add(today);
            return topics.stream().filter(t -> t.status.equals("PUBLISHED") && t.city.equals(cityCode)
                            && !t.starts.isAfter(today) && !t.ends.isBefore(today))
                    .sorted(Comparator.comparing((MutableTopic t) -> t.starts).reversed())
                    .map(this::row).toList();
        }

        @Override
        public TopicRow findTopic(UUID topicId, String cityCode) {
            return topics.stream().filter(t -> t.id.equals(topicId) && t.status.equals("PUBLISHED")
                    && t.city.equals(cityCode)).findFirst().map(this::row).orElse(null);
        }

        @Override
        public List<TopicItemRow> findTopicItems(UUID topicId) {
            return topics.stream().filter(t -> t.id.equals(topicId)).findFirst().map(t -> t.items).orElse(List.of());
        }
    }
}
