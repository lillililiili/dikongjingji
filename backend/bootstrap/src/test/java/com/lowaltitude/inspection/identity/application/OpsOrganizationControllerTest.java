package com.lowaltitude.inspection.identity.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.ops.OpsTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/** 运营端“机构入驻与资质审核”的控制器（S3-1）。 */
class OpsOrganizationControllerTest {

    private static final UUID ORG = UUID.fromString("c1000000-0000-4000-8000-000000000099");
    private static final UUID ATTACHMENT = UUID.fromString("c1000000-0000-4000-8000-0000000000d1");
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-22T02:00:00Z");
    private static final OrganizationOnboardingService.Operator OPERATOR = new OrganizationOnboardingService.Operator(
            OpsTestSupport.OPERATOR_ID, OpsTestSupport.OPERATOR_NAME);

    private final OrganizationOnboardingService service = mock(OrganizationOnboardingService.class);
    private final MockMvc mockMvc = OpsTestSupport.mockMvc(new OpsOrganizationController(service,
            OpsTestSupport.actors()));

    private static OpsOrganizationDetailView detail() {
        return new OpsOrganizationDetailView(new OpsOrganizationView(ORG, "ORG-20261122-ABCDEF01", "黄河口教育装备（示例）",
                OpsOrganizationType.PROVIDER, "370500", "东营市", "ACTIVE", OpsQualificationStatus.SUBMITTED, null, AT),
                null, List.of(), List.of(), List.of(OpsGrantableRole.PROVIDER_OPERATOR, OpsGrantableRole.LIVE_HOST),
                List.of());
    }

    @Test
    void createsWithTheIdempotencyKeyAndAnswers201ThenReplays200() throws Exception {
        UUID key = UUID.randomUUID();
        when(service.create(key, "PROVIDER", "黄河口教育装备（示例）", "370500", OPERATOR))
                .thenReturn(new OpsOrganizationCreated(detail(), true))
                .thenReturn(new OpsOrganizationCreated(detail(), false));
        String body = "{\"type\":\"PROVIDER\",\"name\":\"黄河口教育装备（示例）\",\"cityCode\":\"370500\"}";

        mockMvc.perform(post("/api/v1/ops/organizations").principal(OpsTestSupport.operator())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organization.cityName").value("东营市"))
                .andExpect(jsonPath("$.qualification").isEmpty())
                .andExpect(jsonPath("$.grantableRoles[1]").value("LIVE_HOST"));
        mockMvc.perform(post("/api/v1/ops/organizations").principal(OpsTestSupport.operator())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/ops/organizations").principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }

    @Test
    void qualificationReviewAccountsAndRolesPassTheOperator() throws Exception {
        when(service.review(ORG, "REJECT", "照片不清", 2, OPERATOR)).thenReturn(detail());
        when(service.createAccount(ORG, "hhk-ops", "黄河口操作员", List.of("PROVIDER_OPERATOR"), OPERATOR))
                .thenReturn(new OpsAccountCreatedView(new OpsAccountView(UUID.randomUUID(), "hhk-ops", "黄河口操作员",
                        "ACTIVE", List.of("PROVIDER_OPERATOR"), AT), "Ab3defGh5jKm"));
        when(service.replaceRoles(eq(ORG), any(), eq(List.of()), eq(OPERATOR))).thenReturn(detail());
        when(service.submitQualification(eq(ORG), eq(new OrganizationOnboardingService.QualificationDraft(
                "主体", "00000000000000000X", null, null, null)), eq(OPERATOR))).thenReturn(detail());

        mockMvc.perform(put("/api/v1/ops/organizations/" + ORG + "/qualification").principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectName\":\"主体\",\"licenseNumber\":\"00000000000000000X\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/ops/organizations/" + ORG + "/qualification/review")
                        .principal(OpsTestSupport.operator()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"reason\":\"照片不清\",\"version\":2}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/ops/organizations/" + ORG + "/accounts").principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"hhk-ops\",\"displayName\":\"黄河口操作员\",\"roles\":[\"PROVIDER_OPERATOR\"]}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.initialPassword").value("Ab3defGh5jKm"))
                .andExpect(jsonPath("$.account.loginName").value("hhk-ops"));
        mockMvc.perform(put("/api/v1/ops/organizations/" + ORG + "/accounts/" + UUID.randomUUID() + "/roles")
                        .principal(OpsTestSupport.operator()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[]}"))
                .andExpect(status().isOk());
    }

    @Test
    void attachmentsAreUploadedAsMultipartAndDownloadedAsAttachments() throws Exception {
        byte[] pdf = "%PDF-1.7".getBytes(StandardCharsets.UTF_8);
        when(service.addAttachment(ORG, "营业执照.pdf", pdf, OPERATOR)).thenReturn(detail());
        when(service.attachmentContent(ORG, ATTACHMENT))
                .thenReturn(new OpsAttachmentContent("营业执照 副本.pdf", "application/pdf", pdf));

        mockMvc.perform(multipart("/api/v1/ops/organizations/" + ORG + "/attachments")
                        .file(new MockMultipartFile("file", "营业执照.pdf", "application/pdf", pdf))
                        .principal(OpsTestSupport.operator()))
                .andExpect(status().isCreated());
        mockMvc.perform(multipart("/api/v1/ops/organizations/" + ORG + "/attachments")
                        .file(new MockMultipartFile("file", "空.pdf", "application/pdf", new byte[0]))
                        .principal(OpsTestSupport.operator()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/ops/organizations/" + ORG + "/attachments/" + ATTACHMENT + "/content")
                        .principal(OpsTestSupport.operator()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(content().bytes(pdf))
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename*=UTF-8''%E8%90%A5%E4%B8%9A%E6%89%A7%E7%85%A7%20%E5%89%AF%E6%9C%AC.pdf"));
    }

    @Test
    void rejectionsFollowTheContract() throws Exception {
        when(service.organization(ORG)).thenThrow(new OnboardingException(OnboardingException.Error.NOT_FOUND));
        when(service.review(ORG, "APPROVE", null, 0, OPERATOR))
                .thenThrow(new OnboardingException(OnboardingException.Error.QUALIFICATION_INCOMPLETE));
        when(service.createAccount(ORG, "taken", "某人", List.of("CUSTOMER"), OPERATOR))
                .thenThrow(new OnboardingException(OnboardingException.Error.ROLE_NOT_GRANTABLE));

        mockMvc.perform(get("/api/v1/ops/organizations/" + ORG).principal(OpsTestSupport.operator()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/ops/organizations/" + ORG + "/qualification/review")
                        .principal(OpsTestSupport.operator()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPS_QUALIFICATION_INCOMPLETE"));
        mockMvc.perform(post("/api/v1/ops/organizations/" + ORG + "/accounts").principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"taken\",\"displayName\":\"某人\",\"roles\":[\"CUSTOMER\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OPS_ROLE_NOT_GRANTABLE"));
    }

    @Test
    void onlyPlatformOperatorsGetIn() throws Exception {
        for (var who : List.of(OpsTestSupport.support(),
                OpsTestSupport.signedIn(OpsTestSupport.OPERATOR_ID, "PROVIDER_OPERATOR"))) {
            mockMvc.perform(get("/api/v1/ops/organizations").principal(who)).andExpect(status().isForbidden());
            mockMvc.perform(get("/api/v1/ops/organizations/" + ORG + "/attachments/" + ATTACHMENT + "/content")
                    .principal(who)).andExpect(status().isForbidden());
            mockMvc.perform(post("/api/v1/ops/organizations/" + ORG + "/accounts").principal(who)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"loginName\":\"x-ops\",\"displayName\":\"x\",\"roles\":[\"PROVIDER_OPERATOR\"]}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
        mockMvc.perform(get("/api/v1/ops/organizations").principal(OpsTestSupport.operator()))
                .andExpect(status().isOk());
        verify(service).organizations(null, null, null, 1, 20);
    }
}
