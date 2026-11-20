package com.lowaltitude.inspection.content.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 发现页视频的接口（1.2.0）：令牌里的账号、机构、角色怎么交给服务；201 / 200；业务拒绝转成契约里的错误码。
 * 不带令牌时写接口的 401 由安全过滤器给出，在 AuthenticationSecurityIntegrationTest 里检查。
 */
class ContentVideoControllerTest {

    private static final UUID VIDEO = UUID.fromString("f3200000-0000-4000-8000-000000000001");
    private static final UUID COMMENT = UUID.fromString("f3300000-0000-4000-8000-000000000001");
    private static final UUID MERCHANT = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID CUSTOMER_ORG = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID SUBJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OPERATOR = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AGENT = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID KEY = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-19T10:00:00Z");

    private final ContentVideoService service = mock(ContentVideoService.class);
    private final TestingAuthenticationToken customer = signedIn(SUBJECT, CUSTOMER_ORG, "CUSTOMER");
    private final TestingAuthenticationToken operator = signedIn(OPERATOR, MERCHANT, "PROVIDER_OPERATOR");
    private final TestingAuthenticationToken agent = signedIn(AGENT, null, "PLATFORM_SUPPORT");
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ContentVideoController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
    }

    @Test
    void theStreamPassesItsParametersAndTheViewer() throws Exception {
        when(service.stream("370500", "FOLLOWING", VIDEO, "abc", 3, new ContentActor(SUBJECT, null)))
                .thenReturn(new ContentVideoPageView("370500", ContentVideoFeed.FOLLOWING, List.of(video()), "next"));

        mockMvc.perform(get("/api/v1/content/videos?cityCode=370500&feed=FOLLOWING&startVideoId=" + VIDEO
                        + "&cursor=abc&size=3").principal(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.feed").value("FOLLOWING"))
                .andExpect(jsonPath("$.nextCursor").value("next"))
                .andExpect(jsonPath("$.items[0].length()").value(18))
                .andExpect(jsonPath("$.items[0].orientation").value("LANDSCAPE"))
                .andExpect(jsonPath("$.items[0].playUrl").isEmpty())
                .andExpect(jsonPath("$.items[0].tags[0]").value("编程机器人"))
                .andExpect(jsonPath("$.items[0].refs[0].type").value("PRODUCT"))
                .andExpect(jsonPath("$.items[0].likeCount").value(12))
                .andExpect(jsonPath("$.items[0].followingAuthor").value(false));
    }

    @Test
    void anonymousReadsHaveNoViewerAndOperatorsCarryTheirOrganization() throws Exception {
        when(service.video(VIDEO, "370500", null)).thenReturn(video());
        mockMvc.perform(get("/api/v1/content/videos/{id}?cityCode=370500", VIDEO)).andExpect(status().isOk())
                .andExpect(jsonPath("$.videoId").value(VIDEO.toString()));

        when(service.comments(VIDEO, 2, 10, new ContentActor(OPERATOR, MERCHANT)))
                .thenReturn(new ContentCommentPageView(2, 10, 11, false, List.of(comment())));
        mockMvc.perform(get("/api/v1/content/videos/{id}/comments?page=2&size=10", VIDEO).principal(operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].length()").value(7))
                .andExpect(jsonPath("$.items[0].authorName").value("王**"));
    }

    @Test
    void commentsAreCreatedOrReplayedAndRefusalsHaveTheirCodes() throws Exception {
        when(service.comment(VIDEO, new ContentActor(SUBJECT, null), KEY, "好看"))
                .thenReturn(new ContentCommentPosted(comment(), true))
                .thenReturn(new ContentCommentPosted(comment(), false))
                .thenThrow(new ContentException(ContentException.Error.COMMENT_BLOCKED))
                .thenThrow(new ContentException(ContentException.Error.COMMENT_TOO_FREQUENT))
                .thenThrow(new ContentException(ContentException.Error.IDEMPOTENCY_CONFLICT))
                .thenThrow(new ContentNotFoundException());

        var request = post("/api/v1/content/videos/{id}/comments", VIDEO).principal(customer)
                .header("Idempotency-Key", KEY.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"好看\"}");
        mockMvc.perform(request).andExpect(status().isCreated()).andExpect(jsonPath("$.mine").value(true));
        mockMvc.perform(request).andExpect(status().isOk());
        mockMvc.perform(request).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONTENT_COMMENT_BLOCKED"));
        mockMvc.perform(request).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("CONTENT_COMMENT_TOO_FREQUENT"));
        mockMvc.perform(request).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTENT_IDEMPOTENCY_KEY_REUSED"));
        mockMvc.perform(request).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
        // 没带幂等键：400。
        mockMvc.perform(post("/api/v1/content/videos/{id}/comments", VIDEO).principal(customer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"好看\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void likesFollowsAndReportsUseTheSignedInAccount() throws Exception {
        ContentActor actor = new ContentActor(SUBJECT, null);
        when(service.like(VIDEO, actor)).thenReturn(new ContentVideoLikeView(VIDEO, true, 13));
        when(service.unlike(VIDEO, actor)).thenReturn(new ContentVideoLikeView(VIDEO, false, 12));
        when(service.follow(MERCHANT, actor)).thenReturn(new ContentFollowView(MERCHANT, true));
        when(service.unfollow(MERCHANT, actor)).thenReturn(new ContentFollowView(MERCHANT, false));
        when(service.report(COMMENT, actor, "ABUSE"))
                .thenReturn(new ContentCommentReported(
                        new ContentCommentReportView(COMMENT, ContentCommentReportReason.ABUSE, AT), true))
                .thenReturn(new ContentCommentReported(
                        new ContentCommentReportView(COMMENT, ContentCommentReportReason.ABUSE, AT), false));

        mockMvc.perform(put("/api/v1/content/videos/{id}/like", VIDEO).principal(customer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.likeCount").value(13));
        mockMvc.perform(delete("/api/v1/content/videos/{id}/like", VIDEO).principal(customer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.liked").value(false));
        mockMvc.perform(put("/api/v1/content/follows/{id}", MERCHANT).principal(customer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.following").value(true));
        mockMvc.perform(delete("/api/v1/content/follows/{id}", MERCHANT).principal(customer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.following").value(false));
        var report = post("/api/v1/content/comments/{id}/reports", COMMENT).principal(customer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"ABUSE\"}");
        mockMvc.perform(report).andExpect(status().isCreated()).andExpect(jsonPath("$.reason").value("ABUSE"));
        mockMvc.perform(report).andExpect(status().isOk());
    }

    @Test
    void onlyPlatformSupportAndOperatorsMayHideComments() throws Exception {
        when(service.hide(eq(COMMENT), any())).thenReturn(new ContentCommentHiddenView(COMMENT, true, AT));

        mockMvc.perform(post("/api/v1/content/comments/{id}/hide", COMMENT).principal(agent))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hidden").value(true));
        verify(service).hide(COMMENT, new ContentModerator(AGENT, true, null));
        mockMvc.perform(post("/api/v1/content/comments/{id}/hide", COMMENT).principal(operator))
                .andExpect(status().isOk());
        verify(service).hide(COMMENT, new ContentModerator(OPERATOR, false, MERCHANT));

        ContentVideoService untouched = mock(ContentVideoService.class);
        MockMvc other = MockMvcBuilders.standaloneSetup(new ContentVideoController(untouched))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        for (TestingAuthenticationToken who : List.of(customer, signedIn(OPERATOR, null, "PROVIDER_OPERATOR"))) {
            other.perform(post("/api/v1/content/comments/{id}/hide", COMMENT).principal(who))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        }
        verifyNoInteractions(untouched);
    }

    private static ContentVideoView video() {
        return new ContentVideoView(VIDEO, "3 分钟看懂编程机器人",
                new ContentAuthorView(ContentAuthorKind.MERCHANT, MERCHANT, "示例机器人商家"), true, "370500", null, true,
                AT, 192, ContentVideoOrientation.LANDSCAPE, null, "它是怎么“看见”障碍的？", List.of("编程机器人"),
                List.of(new ContentRefView(ContentRefType.PRODUCT, UUID.fromString("c2000000-0000-4000-8000-000000000101"),
                        "编程机器人探索套装")), 12, 3, false, false);
    }

    private static ContentCommentView comment() {
        return new ContentCommentView(COMMENT, VIDEO, "王**", false, true, "好看", AT);
    }

    private static TestingAuthenticationToken signedIn(UUID subject, UUID organization, String role) {
        CurrentSubject current = new CurrentSubject(subject, Set.of(role), organization, Set.of(), 1);
        TestingAuthenticationToken token = new TestingAuthenticationToken(current, null, "ROLE_" + role);
        token.setAuthenticated(true);
        return token;
    }
}
