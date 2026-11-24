package com.lowaltitude.inspection.identity.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.identity.application.account.AccountException;
import com.lowaltitude.inspection.identity.domain.IdentityProfile;
import com.lowaltitude.inspection.ops.OpsTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 商家自助入驻的公开接口和工作台“入驻资料”（契约 openapi-organization-signup.yaml 1.0.0）。 */
class OrganizationSignupControllersTest {

    private static final UUID ORG = OpsTestSupport.PLATFORM_ORG;
    private static final UUID MEMBER = UUID.fromString("c1000000-0000-4000-8000-0000000000d1");
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-24T02:00:00Z");

    private final OrganizationSignupService service = mock(OrganizationSignupService.class);
    private final MockMvc mockMvc = OpsTestSupport.mockMvc(new OrganizationSignupController(service),
            new ProviderOrganizationController(service, subjectId -> MEMBER.equals(subjectId)
                    ? Optional.of(new IdentityProfile(subjectId, "王芳", "东营翼飞科技", "PROVIDER")) : Optional.empty()));

    private static OwnOrganizationView own() {
        return new OwnOrganizationView(new OpsOrganizationView(ORG, "ORG-20261124-ABCD1234", "东营翼飞科技",
                OpsOrganizationType.PROVIDER, "370500", "东营市", "ACTIVE", OpsQualificationStatus.SUBMITTED, null, AT),
                null, List.of(), OnboardingPorts.ProviderProfileStatus.NOT_OPENED);
    }

    private static final String SIGNUP = "{\"organizationName\":\"东营翼飞科技\",\"cityCode\":\"370500\","
            + "\"subjectName\":\"东营翼飞科技有限公司\",\"licenseNumber\":\"00000000000000000X\",\"contactName\":\"王芳\","
            + "\"phone\":\"13800001234\",\"smsCode\":\"123456\",\"loginName\":\"yifei-shop\",\"password\":\"Fly2026ok\"}";

    @Test
    void signUpAnswers201ThenReplays200WithoutCaching() throws Exception {
        UUID key = UUID.randomUUID();
        OrganizationSignupResult result = new OrganizationSignupResult(ORG, "东营翼飞科技", "yifei-shop",
                OpsQualificationStatus.SUBMITTED);
        when(service.signUp(eq(key), any())).thenReturn(new OrganizationSignupCreated(result, true),
                new OrganizationSignupCreated(result, false));
        mockMvc.perform(post("/api/v1/organization-signups").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(SIGNUP))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.loginName").value("yifei-shop"))
                .andExpect(jsonPath("$.qualificationStatus").value("SUBMITTED"));
        mockMvc.perform(post("/api/v1/organization-signups").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(SIGNUP))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/organization-signups").contentType(MediaType.APPLICATION_JSON).content(SIGNUP))
                .andExpect(status().isBadRequest());
        verify(service, org.mockito.Mockito.times(2)).signUp(key, new OrganizationSignupService.SignupDraft("东营翼飞科技", "370500",
                "东营翼飞科技有限公司", "00000000000000000X", null, "王芳", "13800001234", "123456", "yifei-shop",
                "Fly2026ok"));
    }

    @Test
    void sendCodeAnswers202AndAccountErrorsKeepTheirCodes() throws Exception {
        mockMvc.perform(post("/api/v1/organization-signups/sms-codes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800001234\"}"))
                .andExpect(status().isAccepted());
        verify(service).sendCode("13800001234");
        when(service.signUp(any(), any())).thenThrow(new AccountException(AccountException.Error.PHONE_TAKEN));
        mockMvc.perform(post("/api/v1/organization-signups").header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(SIGNUP))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHONE_TAKEN"));
    }

    @Test
    void theWorkbenchReadsAndEditsOnlyTheTokenOrganization() throws Exception {
        when(service.own(ORG)).thenReturn(own());
        mockMvc.perform(get("/api/v1/provider/organization")
                        .principal(OpsTestSupport.signedIn(MEMBER, "PROVIDER_OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organization.qualificationStatus").value("SUBMITTED"))
                .andExpect(jsonPath("$.providerProfileStatus").value("NOT_OPENED"));

        when(service.submitQualification(eq(ORG), any(), eq(new OrganizationOnboardingService.Operator(MEMBER, "王芳"))))
                .thenReturn(own());
        mockMvc.perform(put("/api/v1/provider/organization/qualification")
                        .principal(OpsTestSupport.signedIn(MEMBER, "PROVIDER_OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectName\":\"东营翼飞科技有限公司\",\"licenseNumber\":\"00000000000000000X\"}"))
                .andExpect(status().isOk());
        verify(service).submitQualification(ORG, new OrganizationOnboardingService.QualificationDraft(
                "东营翼飞科技有限公司", "00000000000000000X", null, null, null),
                new OrganizationOnboardingService.Operator(MEMBER, "王芳"));
    }

    @Test
    void platformRolesAndCustomersStayOut() throws Exception {
        mockMvc.perform(get("/api/v1/provider/organization").principal(OpsTestSupport.operator()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/provider/organization")
                        .principal(OpsTestSupport.signedIn(UUID.randomUUID(), "CUSTOMER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
