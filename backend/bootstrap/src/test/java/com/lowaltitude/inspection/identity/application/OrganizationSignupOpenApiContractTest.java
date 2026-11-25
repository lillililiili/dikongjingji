package com.lowaltitude.inspection.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.lowaltitude.inspection.ops.OpsContractChecker;
import org.junit.jupiter.api.Test;

/**
 * 契约 contracts/openapi-organization-signup.yaml 和两个控制器逐项对照。机构、资质、附件的形状 $ref 到
 * openapi-ops-organization.yaml（OpsOrganizationOpenApiContractTest 核对）；这里核对路由、方法、公开与否和请求体。
 */
class OrganizationSignupOpenApiContractTest {

    private final OpsContractChecker checker = new OpsContractChecker("openapi-organization-signup.yaml",
            OrganizationSignupController.class, ProviderOrganizationController.class)
            .request("OrganizationSignupRequest", OrganizationSignupController.SignupRequest.class,
                    "organizationName", "cityCode", "subjectName", "licenseNumber", "contactName", "phone", "smsCode",
                    "loginName", "password")
            .request("SignupSmsCodeRequest", OrganizationSignupController.SmsCodeRequest.class, "phone")
            .request("MyQualificationRequest", ProviderOrganizationController.QualificationRequest.class,
                    "subjectName", "licenseNumber");

    @Test
    void routesMatchAndOnlyTheTwoSignupRoutesArePublic() {
        assertThat(OpsContractChecker.map(checker.root().get("info")).get("version")).isEqualTo("1.0.1");
        checker.verifyRequestObjects();
        Map<String, Object> paths = OpsContractChecker.map(checker.root().get("paths"));
        Set<String> declared = new TreeSet<>();
        paths.forEach((path, item) -> OpsContractChecker.map(item).forEach((method, operation) -> {
            declared.add(method.toUpperCase() + " " + path);
            Object security = OpsContractChecker.map(operation).get("security");
            if (path.startsWith("/api/v1/organization-signups")) {
                assertThat(security).as(path).isEqualTo(List.of());
            } else {
                assertThat(path).startsWith("/api/v1/provider/organization");
                assertThat(security).as(path).isEqualTo(List.of(Map.of("bearerAuth", List.of())));
            }
        }));
        assertThat(declared).containsExactlyInAnyOrderElementsOf(
                checker.operations().stream().map(OpsContractChecker.Operation::key).toList());
        assertThat(checker.text()).contains("maxLength: " + OrganizationSignupService.MAX_CONTACT_NAME)
                .contains("minLength: " + OrganizationSignupService.MIN_PASSWORD)
                .contains("maxLength: " + OrganizationSignupService.MAX_PASSWORD);
    }
}
