package com.lowaltitude.inspection.content.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.content.application.ops.ContentOpsCodes.OpsArticleKind;
import com.lowaltitude.inspection.content.application.ops.ContentOpsCodes.OpsAuthorKind;
import com.lowaltitude.inspection.content.application.ops.ContentOpsCodes.OpsBlockType;
import com.lowaltitude.inspection.content.application.ops.ContentOpsCodes.OpsContentStatus;
import com.lowaltitude.inspection.content.application.ops.ContentOpsError;
import com.lowaltitude.inspection.content.application.ops.ContentOpsService;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews;
import com.lowaltitude.inspection.ops.OpsTestSupport;
import com.lowaltitude.inspection.ops.common.OpsException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 运营端“发现内容”的控制器（S3-1）：只给平台运营，请求体换成草稿交给服务，业务拒绝按契约给状态码。 */
class OpsContentControllerTest {

    private static final UUID ARTICLE = UUID.fromString("f3000000-0000-4000-8000-000000000001");
    private static final UUID EAGLE = UUID.fromString("f2000000-0000-4000-8000-000000000001");
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-23T02:00:00Z");

    private final ContentOpsService service = mock(ContentOpsService.class);
    private final MockMvc mockMvc = OpsTestSupport.mockMvc(new OpsContentController(service, OpsTestSupport.actors()));

    @Test
    void operatorCreatesAnArticleWithAnImageBlockAndPublishesIt() throws Exception {
        when(service.createArticle(any(), any())).thenReturn(article(OpsContentStatus.DRAFT, null, 0));
        when(service.publishArticle(eq(ARTICLE), eq(0), org.mockito.ArgumentMatchers.argThat(
                (OffsetDateTime at) -> at != null && at.isEqual(OffsetDateTime.parse("2026-11-25T01:30Z"))), any()))
                .thenReturn(article(OpsContentStatus.PUBLISHED, AT, 1));

        mockMvc.perform(post("/api/v1/ops/content/articles").principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"CASE","title":"一台机器狗如何完成园区夜间巡逻","authorKind":"PROVIDER",
                                 "authorOrganizationId":"f2000000-0000-4000-8000-000000000001","cityCode":"370500",
                                 "blocks":[{"type":"STEPS","items":[{"title":"白天踏勘"}]},
                                           {"type":"IMAGE","imageUrl":"https://media.example.com/a.png",
                                            "imageAlt":"无人机在园区上空巡查","imageCaption":"围墙外侧由无人机补充巡查"}],
                                 "refs":[{"type":"PRODUCT","refId":"c2000000-0000-4000-8000-000000000401","title":"巡检机器狗"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.blocks[0].type").value("IMAGE"))
                .andExpect(jsonPath("$.blocks[0].image.caption").value("围墙外侧由无人机补充巡查"))
                .andExpect(jsonPath("$.publishMissing[0]").value("coverUrl"));
        ArgumentCaptor<ContentOpsViews.ArticleDraft> draft = ArgumentCaptor.forClass(ContentOpsViews.ArticleDraft.class);
        ArgumentCaptor<ContentOpsViews.Operator> operator = ArgumentCaptor.forClass(ContentOpsViews.Operator.class);
        verify(service).createArticle(draft.capture(), operator.capture());
        org.assertj.core.api.Assertions.assertThat(draft.getValue().author())
                .isEqualTo(new ContentOpsViews.AuthorDraft("PROVIDER", EAGLE, null));
        org.assertj.core.api.Assertions.assertThat(draft.getValue().blocks().get(1))
                .isEqualTo(new ContentOpsViews.BlockDraft("IMAGE", null, null, "https://media.example.com/a.png",
                        "无人机在园区上空巡查", "围墙外侧由无人机补充巡查"));
        org.assertj.core.api.Assertions.assertThat(operator.getValue())
                .isEqualTo(new ContentOpsViews.Operator(OpsTestSupport.OPERATOR_ID, OpsTestSupport.OPERATOR_NAME));

        mockMvc.perform(post("/api/v1/ops/content/articles/{id}/publish", ARTICLE).principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"publishAt\":\"2026-11-25T09:30:00+08:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.publishedAt").value("2026-11-23T02:00:00Z"));
    }

    @Test
    void rejectionsFollowTheContract() throws Exception {
        when(service.publishArticle(eq(ARTICLE), eq(3), any(), any())).thenThrow(
                OpsException.incomplete(ContentOpsError.PUBLISH_INCOMPLETE, List.of("coverUrl", "imageUrl")));
        when(service.offlineArticle(eq(ARTICLE), eq(3), any()))
                .thenThrow(new OpsException(ContentOpsError.STATUS_CONFLICT));
        when(service.updateTopic(eq(ARTICLE), any(), eq(1), any()))
                .thenThrow(new OpsException(ContentOpsError.PRODUCT_UNKNOWN));
        when(service.video(ARTICLE)).thenThrow(new OpsException(ContentOpsError.NOT_FOUND));
        when(service.articles(null, null, "abc", null, 1, 20)).thenThrow(new IllegalArgumentException("city"));

        mockMvc.perform(post("/api/v1/ops/content/articles/{id}/publish", ARTICLE).principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":3}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OPS_PUBLISH_INCOMPLETE"))
                .andExpect(jsonPath("$.details.missing[1]").value("imageUrl"));
        mockMvc.perform(post("/api/v1/ops/content/articles/{id}/offline", ARTICLE).principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPS_CONTENT_STATUS_CONFLICT"));
        mockMvc.perform(put("/api/v1/ops/content/topics/{id}", ARTICLE).principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"专题\",\"publisherKind\":\"PLATFORM\",\"cityCode\":\"370500\","
                                + "\"startsOn\":\"2026-11-01\",\"endsOn\":\"2026-11-30\",\"version\":1,"
                                + "\"merchantItems\":[{\"productId\":\"c2000000-0000-4000-8000-000000000101\","
                                + "\"merchantOrganizationId\":\"c1000000-0000-4000-8000-000000000001\"}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OPS_CONTENT_PRODUCT_UNKNOWN"));
        mockMvc.perform(get("/api/v1/ops/content/videos/{id}", ARTICLE).principal(OpsTestSupport.operator()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/ops/content/articles?cityCode=abc").principal(OpsTestSupport.operator()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(get("/api/v1/ops/content/articles/not-a-uuid").principal(OpsTestSupport.operator()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void otherRolesAndDisabledOperatorsAreForbidden() throws Exception {
        for (var who : List.of(OpsTestSupport.support(),
                OpsTestSupport.signedIn(OpsTestSupport.OPERATOR_ID, "PROVIDER_OPERATOR", "LIVE_HOST"),
                OpsTestSupport.signedIn(UUID.randomUUID(), "PLATFORM_OPERATOR"))) {
            mockMvc.perform(get("/api/v1/ops/content/topics").principal(who))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
            mockMvc.perform(post("/api/v1/ops/content/videos/{id}/publish", ARTICLE).principal(who)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
        mockMvc.perform(get("/api/v1/ops/content/videos?status=PUBLISHED&page=2&size=10")
                        .principal(OpsTestSupport.operator()))
                .andExpect(status().isOk());
        verify(service).videos(null, null, "PUBLISHED", 2, 10);
    }

    private static ContentOpsViews.OpsArticleView article(OpsContentStatus status, OffsetDateTime publishedAt,
                                                          int version) {
        return new ContentOpsViews.OpsArticleView(ARTICLE, OpsArticleKind.CASE, "一台机器狗如何完成园区夜间巡逻",
                new ContentOpsViews.OpsContentAuthorView(OpsAuthorKind.PROVIDER, EAGLE, "鹰眼智能巡检"), false,
                "370500", null, true, status, publishedAt,
                List.of(new ContentOpsViews.OpsBlockView(OpsBlockType.IMAGE, null, List.of(),
                        new ContentOpsViews.OpsBlockImageView("https://media.example.com/a.png", "无人机在园区上空巡查",
                                "围墙外侧由无人机补充巡查"))),
                List.of(), status == OpsContentStatus.DRAFT ? List.of("coverUrl") : List.of(), version, AT);
    }
}
