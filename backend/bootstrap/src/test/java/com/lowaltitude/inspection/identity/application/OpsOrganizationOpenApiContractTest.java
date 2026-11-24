package com.lowaltitude.inspection.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.lowaltitude.inspection.bootstrap.api.ApiError;
import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.ops.OpsContractChecker;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** 契约 contracts/openapi-ops-organization.yaml 和 OpsOrganizationController 逐项对照（S3-1；1.1.0 加了开通服务商资料）。 */
class OpsOrganizationOpenApiContractTest {

    private final OpsContractChecker checker = new OpsContractChecker("openapi-ops-organization.yaml",
            OpsOrganizationController.class)
            .response("OpsOrganization", OpsOrganizationView.class)
            .response("OpsOrganizationPage", OpsOrganizationPageView.class)
            .response("OpsQualification", OpsQualificationView.class)
            .response("OpsAttachment", OpsAttachmentView.class)
            .response("OpsAccount", OpsAccountView.class)
            .response("OpsOperation", OpsOperationView.class)
            .response("OpsOrganizationDetail", OpsOrganizationDetailView.class)
            .response("OpsAccountCreated", OpsAccountCreatedView.class)
            .request("OpsOrganizationCreateRequest", OpsOrganizationController.CreateRequest.class,
                    "type", "name", "cityCode")
            .request("OpsQualificationRequest", OpsOrganizationController.QualificationRequest.class,
                    "subjectName", "licenseNumber")
            .request("OpsReviewRequest", OpsOrganizationController.ReviewRequest.class, "decision", "version")
            .request("OpsAccountRequest", OpsOrganizationController.AccountRequest.class,
                    "loginName", "displayName", "roles")
            .request("OpsRolesRequest", OpsOrganizationController.RolesRequest.class, "roles")
            .requestEnumField("OpsOrganizationCreateRequest.type", "OpsOrganizationType")
            .requestEnumField("OpsReviewRequest.decision", "OpsReviewDecision")
            .enumeration("OpsOrganizationType", OpsOrganizationType.class)
            .enumeration("OpsQualificationStatus", OpsQualificationStatus.class)
            .enumeration("OpsReviewDecision", OpsReviewDecision.class)
            .enumeration("OpsOrganizationAction", OpsOrganizationAction.class)
            .enumeration("OpsGrantableRole", OpsGrantableRole.class)
            .enumeration("OpsProviderProfileStatus", OnboardingPorts.ProviderProfileStatus.class)
            .other("ApiError", "OpsOrganizationErrorCode");

    @Test
    void contractMatchesTheController() {
        checker.verifyAll("1.2.0");
    }

    @Test
    void limitsArePatternsAreTheOnesTheServiceEnforces() {
        Map<String, Object> schemas = checker.schemas();
        assertThat(property(schemas, "OpsOrganizationCreateRequest", "name").get("maxLength"))
                .isEqualTo(OrganizationOnboardingService.MAX_NAME);
        assertThat(property(schemas, "OpsQualificationRequest", "subjectName").get("maxLength"))
                .isEqualTo(OrganizationOnboardingService.MAX_SUBJECT_NAME);
        assertThat(property(schemas, "OpsQualificationRequest", "licenseNumber").get("pattern"))
                .isEqualTo(OrganizationOnboardingService.LICENSE_NUMBER.pattern());
        assertThat(property(schemas, "OpsQualificationRequest", "businessScope").get("maxLength"))
                .isEqualTo(OrganizationOnboardingService.MAX_SCOPE);
        assertThat(property(schemas, "OpsReviewRequest", "reason").get("maxLength"))
                .isEqualTo(OrganizationOnboardingService.MAX_REJECT_REASON);
        assertThat(property(schemas, "OpsAccountRequest", "loginName").get("pattern"))
                .isEqualTo(OrganizationOnboardingService.LOGIN_NAME.pattern());
        assertThat(property(schemas, "OpsAccountRequest", "displayName").get("maxLength"))
                .isEqualTo(OrganizationOnboardingService.MAX_DISPLAY_NAME);
        assertThat(property(schemas, "OpsAttachment", "sizeBytes").get("maximum"))
                .isEqualTo((int) OrganizationOnboardingService.MAX_FILE_BYTES);
        assertThat(property(schemas, "OpsAccountCreated", "initialPassword").get("maxLength"))
                .isEqualTo(OrganizationOnboardingService.INITIAL_PASSWORD_LENGTH);
        assertThat(property(schemas, "OpsOrganizationDetail", "attachments").get("maxItems"))
                .isEqualTo(OrganizationOnboardingService.MAX_ATTACHMENTS);
        assertThat(property(schemas, "OpsOrganizationDetail", "operations").get("maxItems"))
                .isEqualTo(OrganizationOnboardingService.OPERATION_HISTORY_LIMIT);
    }

    @Test
    void errorCodesAreTheOnesTheHandlerAnswers() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Set<String> produced = new TreeSet<>();
        for (OnboardingException.Error error : OnboardingException.Error.values()) {
            ResponseEntity<ApiError> response = handler.handleOnboarding(new OnboardingException(error),
                    new MockHttpServletRequest());
            String code = response.getBody().code();
            if (code.startsWith("OPS_")) {
                produced.add(code);
                String status = String.valueOf(response.getStatusCode().value());
                assertThat(checker.text()).as(code + " under " + status)
                        .containsPattern("\"" + status + "\":\\s+description: \"[^\"]*" + code);
            } else {
                assertThat(code).isEqualTo("COMMON_NOT_FOUND");
            }
        }
        checker.verifyErrorCodes("OpsOrganizationErrorCode", produced);
    }

    private static Map<String, Object> property(Map<String, Object> schemas, String schema, String field) {
        return OpsContractChecker.map(OpsContractChecker.map(OpsContractChecker.map(schemas.get(schema))
                .get("properties")).get(field));
    }
}
