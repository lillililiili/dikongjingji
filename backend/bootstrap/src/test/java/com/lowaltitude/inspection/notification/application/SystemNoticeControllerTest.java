package com.lowaltitude.inspection.notification.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import com.lowaltitude.inspection.notification.application.SystemNotices.Action;
import com.lowaltitude.inspection.notification.application.SystemNotices.AgreementCode;
import com.lowaltitude.inspection.notification.application.SystemNotices.AgreementUpdateView;
import com.lowaltitude.inspection.notification.application.SystemNotices.Audience;
import com.lowaltitude.inspection.notification.application.SystemNotices.AudienceScope;
import com.lowaltitude.inspection.notification.application.SystemNotices.Draft;
import com.lowaltitude.inspection.notification.application.SystemNotices.Fact;
import com.lowaltitude.inspection.notification.application.SystemNotices.Item;
import com.lowaltitude.inspection.notification.application.SystemNotices.Link;
import com.lowaltitude.inspection.notification.application.SystemNotices.LinkType;
import com.lowaltitude.inspection.notification.application.SystemNotices.Status;
import com.lowaltitude.inspection.notification.application.SystemNotices.SystemNoticeView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** S5-9：公告的公开读取；发布、列表、撤回只给平台运营。 */
class SystemNoticeControllerTest {

    private static final UUID NOTICE = UUID.fromString("c5000000-0000-4000-8000-000000000001");
    private static final UUID OPERATOR = UUID.fromString("90000000-0000-4000-8000-0000000000b1");
    private static final UUID ORGANIZATION = UUID.fromString("90000000-0000-4000-8000-000000000001");

    private final SystemNoticeService service = mock(SystemNoticeService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SystemNoticeController(service),
                        new SystemNoticeOpsController(service, new com.lowaltitude.inspection.ops.OpsActors(
                                subjectId -> Optional.of(new com.lowaltitude.inspection.identity.domain.IdentityProfile(
                                        subjectId, "平台运营（示例）", "低空产业平台（示例）", "PLATFORM")))))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void readsAPublishedNoticeWithItsParts() throws Exception {
        when(service.read(NOTICE)).thenReturn(view(Status.PUBLISHED));

        mockMvc.perform(get("/api/v1/system-notices/{id}", NOTICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(13))
                .andExpect(jsonPath("$.title").value("隐私政策更新摘要"))
                .andExpect(jsonPath("$.paragraphs[0]").value("我们更新了《隐私政策》。"))
                .andExpect(jsonPath("$.facts[0].label").value("版本"))
                .andExpect(jsonPath("$.items[0].link.type").value("PRIVACY_PERMISSIONS"))
                .andExpect(jsonPath("$.items[0].link.agreement").isEmpty())
                .andExpect(jsonPath("$.action.link.agreement").value("PRIVACY_POLICY"))
                .andExpect(jsonPath("$.audience.scope").value("ALL"))
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @Test
    void theLatestAgreementUpdateIsNullWhenThereIsNone() throws Exception {
        when(service.latestAbout("PRIVACY_POLICY")).thenReturn(Optional.of(
                new AgreementUpdateView(NOTICE, "隐私政策更新摘要", Instant.parse("2026-11-01T01:00:00Z"))));
        when(service.latestAbout("USER_AGREEMENT")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/system-notices/agreement-updates").param("agreement", "PRIVACY_POLICY"))
                .andExpect(jsonPath("$.latest.noticeId").value(NOTICE.toString()));
        mockMvc.perform(get("/api/v1/system-notices/agreement-updates").param("agreement", "USER_AGREEMENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latest").isEmpty());
    }

    @Test
    void onlyAPlatformOperatorPublishesListsAndWithdraws() throws Exception {
        TestingAuthenticationToken support = signedIn("PLATFORM_SUPPORT");

        mockMvc.perform(post("/api/v1/ops/notifications/system-notices").principal(support)
                        .header("Idempotency-Key", "k-1").contentType("application/json").content(BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/ops/notifications/system-notices").principal(signedIn("CUSTOMER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/ops/notifications/system-notices/{id}/withdraw", NOTICE).principal(support))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void publishingHandsTheDraftAndKeyToTheService() throws Exception {
        when(service.publish(eq(OPERATOR), eq("k-1"), any())).thenReturn(view(Status.PUBLISHED));
        when(service.withdraw(NOTICE)).thenReturn(view(Status.WITHDRAWN));

        mockMvc.perform(post("/api/v1/ops/notifications/system-notices").principal(signedIn("PLATFORM_OPERATOR"))
                        .header("Idempotency-Key", "k-1").contentType("application/json").content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(NOTICE.toString()));
        mockMvc.perform(post("/api/v1/ops/notifications/system-notices/{id}/withdraw", NOTICE)
                        .principal(signedIn("PLATFORM_OPERATOR")))
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));
        // 没有幂等键是参数错误。
        mockMvc.perform(post("/api/v1/ops/notifications/system-notices").principal(signedIn("PLATFORM_OPERATOR"))
                        .contentType("application/json").content(BODY))
                .andExpect(status().isBadRequest());

        ArgumentCaptor<Draft> draft = ArgumentCaptor.forClass(Draft.class);
        verify(service).publish(eq(OPERATOR), eq("k-1"), draft.capture());
        org.assertj.core.api.Assertions.assertThat(draft.getValue().audienceScope()).isEqualTo(AudienceScope.CITY);
        org.assertj.core.api.Assertions.assertThat(draft.getValue().cityCode()).isEqualTo("370500");
        org.assertj.core.api.Assertions.assertThat(draft.getValue().content().paragraphs())
                .containsExactly("第一段", "第二段");
        org.assertj.core.api.Assertions.assertThat(draft.getValue().content().action().link())
                .isEqualTo(new Link(LinkType.AGREEMENT, AgreementCode.PRIVACY_POLICY));
    }

    @Test
    void anUnknownLinkTypeIsABadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/ops/notifications/system-notices").principal(signedIn("PLATFORM_OPERATOR"))
                        .header("Idempotency-Key", "k-1").contentType("application/json")
                        .content(BODY.replace("\"AGREEMENT\"", "\"WEB_URL\"")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private static final String BODY = """
            {"title":"东营站隐私说明","summary":"摘要","paragraphs":["第一段","第二段"],
             "facts":[{"label":"版本","value":"v1.1"}],
             "items":[{"title":"权限用途","description":"说明","link":{"type":"PRIVACY_PERMISSIONS","agreement":null}}],
             "footnote":null,
             "action":{"label":"查看完整隐私政策","link":{"type":"AGREEMENT","agreement":"PRIVACY_POLICY"}},
             "aboutAgreement":"PRIVACY_POLICY","audience":{"scope":"CITY","cityCode":"370500"},"expiresAt":null}
            """;

    private static TestingAuthenticationToken signedIn(String role) {
        return new TestingAuthenticationToken(new CurrentSubject(OPERATOR, Set.of(role), ORGANIZATION, Set.of(), 0),
                null);
    }

    private static SystemNoticeView view(Status status) {
        return new SystemNoticeView(NOTICE, "隐私政策更新摘要", "查看主要变化与生效日期", List.of("我们更新了《隐私政策》。"),
                List.of(new Fact("版本", "v1.1")),
                List.of(new Item("权限用途", "逐项说明", new Link(LinkType.PRIVACY_PERMISSIONS, null))),
                "生效前可以阅读全文。",
                new Action("查看完整隐私政策", new Link(LinkType.AGREEMENT, AgreementCode.PRIVACY_POLICY)),
                AgreementCode.PRIVACY_POLICY, new Audience(AudienceScope.ALL, null),
                Instant.parse("2026-11-01T01:00:00Z"), null, status);
    }
}
