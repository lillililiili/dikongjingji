package com.lowaltitude.inspection.content.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 发现页内容的公开读取接口（H1）：把查询参数原样交给读服务，把结果和 400/404 转成契约里的形状。
 * 不登录也能调，在 AuthenticationSecurityIntegrationTest 里检查。
 */
class ContentControllerTest {

    private static final UUID ARTICLE = UUID.fromString("f3000000-0000-4000-8000-000000000001");
    private static final UUID TOPIC = UUID.fromString("f3100000-0000-4000-8000-000000000001");
    private static final UUID EAGLE = UUID.fromString("f2000000-0000-4000-8000-000000000001");
    private static final UUID DOG = UUID.fromString("c2000000-0000-4000-8000-000000000401");
    private static final UUID ACTIVITY = UUID.fromString("a2000000-0000-4000-8000-000000000001");
    private static final UUID VENUE = UUID.fromString("b2000000-0000-4000-8000-000000000001");
    private static final OffsetDateTime PUBLISHED = OffsetDateTime.parse("2026-10-28T02:00:00Z");

    private final ContentReadService service = mock(ContentReadService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ContentController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
    }

    @Test
    void theFeedPassesItsParametersAndAnswersTopicsAndCards() throws Exception {
        when(service.feed("370500", "RECOMMEND", 2, 10)).thenReturn(new ContentFeedView(ContentFeedChannel.RECOMMEND,
                "370500", 2, 10, 11, false, List.of(topicCard()), List.of(card())));
        when(service.feed("370500", null, 1, 20)).thenReturn(new ContentFeedView(ContentFeedChannel.RECOMMEND,
                "370500", 1, 20, 0, false, List.of(), List.of()));

        mockMvc.perform(get("/api/v1/content/feed?cityCode=370500&channel=RECOMMEND&page=2&size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$.channel").value("RECOMMEND"))
                .andExpect(jsonPath("$.total").value(11))
                .andExpect(jsonPath("$.topics[0].length()").value(8))
                .andExpect(jsonPath("$.topics[0].startsOn").value("2026-11-01"))
                .andExpect(jsonPath("$.topics[0].publisher.organizationId").isEmpty())
                .andExpect(jsonPath("$.items[0].length()").value(10))
                .andExpect(jsonPath("$.items[0].video").isEmpty())
                .andExpect(jsonPath("$.items[0].kind").value("CASE"))
                .andExpect(jsonPath("$.items[0].author.kind").value("PROVIDER"))
                .andExpect(jsonPath("$.items[0].author.organizationId").value(EAGLE.toString()))
                .andExpect(jsonPath("$.items[0].sponsored").value(false))
                .andExpect(jsonPath("$.items[0].coverImage").isEmpty())
                .andExpect(jsonPath("$.items[0].sample").value(true))
                .andExpect(jsonPath("$.items[0].publishedAt").value("2026-10-28T02:00:00Z"));
        mockMvc.perform(get("/api/v1/content/feed?cityCode=370500")).andExpect(status().isOk());
    }

    @Test
    void anArticleCarriesItsBlocksAndRefs() throws Exception {
        when(service.article(ARTICLE, "370500")).thenReturn(new ContentArticleView(ARTICLE, ContentArticleKind.CASE,
                "一台机器狗如何完成园区夜间巡逻", card().author(), false, "370500", null, true, PUBLISHED,
                List.of(new ContentBlockView(ContentBlockType.FACTS, null,
                                List.of(new ContentBlockItemView("场景", "物流园区夜间安防"))),
                        new ContentBlockView(ContentBlockType.PARAGRAPH, "白天人多车多。", List.of())),
                List.of(new ContentRefView(ContentRefType.PRODUCT, DOG, "巡检机器狗"))));

        mockMvc.perform(get("/api/v1/content/articles/{id}?cityCode=370500", ARTICLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(11))
                .andExpect(jsonPath("$.blocks[0].type").value("FACTS"))
                .andExpect(jsonPath("$.blocks[0].text").isEmpty())
                .andExpect(jsonPath("$.blocks[0].items[0].title").value("场景"))
                .andExpect(jsonPath("$.blocks[0].items[0].text").value("物流园区夜间安防"))
                .andExpect(jsonPath("$.blocks[1].items.length()").value(0))
                .andExpect(jsonPath("$.refs[0].length()").value(3))
                .andExpect(jsonPath("$.refs[0].type").value("PRODUCT"))
                .andExpect(jsonPath("$.refs[0].refId").value(DOG.toString()));
    }

    @Test
    void aTopicCarriesTheIdsToReadAndItsArticles() throws Exception {
        when(service.topic(TOPIC, "370500")).thenReturn(new ContentTopicView(TOPIC, "黄河口低空体验季", "到馆体验、赛事、研学合集",
                topicCard().publisher(), "370500", LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30"), null, true,
                List.of(ACTIVITY), List.of(VENUE), List.of(card())));

        mockMvc.perform(get("/api/v1/content/topics/{id}?cityCode=370500", TOPIC))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(12))
                .andExpect(jsonPath("$.activityIds[0]").value(ACTIVITY.toString()))
                .andExpect(jsonPath("$.venueIds[0]").value(VENUE.toString()))
                .andExpect(jsonPath("$.articles[0].articleId").value(ARTICLE.toString()))
                .andExpect(jsonPath("$.endsOn").value("2026-11-30"));
    }

    @Test
    void badArgumentsAreBadRequestsAndInvisibleContentIsNotFound() throws Exception {
        when(service.feed(null, null, 1, 20)).thenThrow(new IllegalArgumentException("city code is invalid"));
        when(service.article(ARTICLE, "370100")).thenThrow(new ContentNotFoundException());
        when(service.topic(TOPIC, "370100")).thenThrow(new ContentNotFoundException());

        mockMvc.perform(get("/api/v1/content/feed"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(get("/api/v1/content/feed?cityCode=370500&page=x"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/content/articles/not-a-uuid?cityCode=370500"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/content/articles/{id}?cityCode=370100", ARTICLE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/content/topics/{id}?cityCode=370100", TOPIC))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
    }

    // R6-3 相关内容：参数原样交给读服务（limit 不传为 null，由读服务取默认值），答出卡片。
    @Test
    void relatedContentPassesItsParametersAndAnswersTheCards() throws Exception {
        when(service.related("PRODUCT", DOG, "370500", null))
                .thenReturn(new ContentRelatedView(ContentRefType.PRODUCT, DOG, List.of(card())));
        when(service.related("PRODUCT", DOG, "370500", 7)).thenThrow(new IllegalArgumentException("limit is invalid"));

        mockMvc.perform(get("/api/v1/content/related?refType=PRODUCT&refId={id}&cityCode=370500", DOG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refType").value("PRODUCT"))
                .andExpect(jsonPath("$.refId").value(DOG.toString()))
                .andExpect(jsonPath("$.items[0].articleId").value(ARTICLE.toString()))
                .andExpect(jsonPath("$.items[0].kind").value("CASE"))
                .andExpect(jsonPath("$.items[0].publishedAt").value("2026-10-28T02:00:00Z"));
        mockMvc.perform(get("/api/v1/content/related?refType=PRODUCT&refId={id}&cityCode=370500&limit=7", DOG))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(get("/api/v1/content/related?refType=PRODUCT&refId=not-a-uuid&cityCode=370500"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/content/related?refType=PRODUCT&refId={id}&cityCode=370500&limit=x", DOG))
                .andExpect(status().isBadRequest());
    }

    private static ContentArticleCardView card() {
        return new ContentArticleCardView(ARTICLE, ContentArticleKind.CASE, "一台机器狗如何完成园区夜间巡逻",
                new ContentAuthorView(ContentAuthorKind.PROVIDER, EAGLE, "鹰眼智能巡检"), null, false, "370500", null, true,
                PUBLISHED);
    }

    private static ContentTopicCardView topicCard() {
        return new ContentTopicCardView(TOPIC, "黄河口低空体验季", "到馆体验、赛事、研学合集",
                new ContentAuthorView(ContentAuthorKind.PLATFORM, null, "东营城市运营中心"), LocalDate.parse("2026-11-01"),
                LocalDate.parse("2026-11-30"), null, true);
    }
}
