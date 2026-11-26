package com.lowaltitude.inspection.identity.application.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.bootstrap.api.ApiError;
import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.bootstrap.security.PersonalPasswordController;
import com.lowaltitude.inspection.bootstrap.security.PersonalPasswordService;
import com.lowaltitude.inspection.bootstrap.security.PhoneAuthenticationController;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * 逐项对照身份契约与实现的路由、认证范围、请求和响应字段；
 * 业务错误码与全局异常处理给出的一致，并且声明在处理器回答的状态码下。
 */
class IdentityAccountOpenApiContractTest {

    private static final Path CONTRACT = Path.of("..", "..", "contracts", "openapi-identity-account.yaml");
    private static final Set<String> PUBLIC = Set.of(
            "POST /api/v1/auth/sms-codes", "POST /api/v1/auth/phone-login",
            "POST /api/v1/auth/phone-password-login",
            "POST /api/v1/auth/password-reset-codes", "POST /api/v1/auth/password-reset",
            "GET /api/v1/account/data-exports/{exportId}/file");

    private static final Map<String, Class<? extends Record>> RESPONSES = Map.ofEntries(
            Map.entry("SmsCodeSent", AccountViews.SmsCodeSentView.class),
            Map.entry("PhoneLoginResponse", PhoneAuthenticationController.PhoneLoginResponse.class),
            Map.entry("PersonalPasswordState", PersonalPasswordService.PasswordState.class),
            Map.entry("PhoneChangeTicket", AccountViews.PhoneChangeTicketView.class),
            Map.entry("PhoneBinding", AccountViews.PhoneBindingView.class),
            Map.entry("Session", AccountViews.SessionView.class),
            Map.entry("SessionList", AccountViews.SessionListView.class),
            Map.entry("RevokedCount", AccountViews.RevokedCountView.class),
            Map.entry("ClosureCondition", AccountViews.ClosureConditionView.class),
            Map.entry("Closure", AccountViews.ClosureView.class),
            Map.entry("DataSection", AccountViews.DataSectionView.class),
            Map.entry("DataExport", AccountViews.DataExportView.class),
            Map.entry("DataExportOverview", AccountViews.DataExportOverviewView.class),
            Map.entry("DownloadIntent", AccountViews.DownloadIntentView.class),
            Map.entry("CurrentIdentity", AccountViews.CurrentIdentityView.class),
            Map.entry("IdentitySwitchResponse", EnterpriseController.IdentitySwitchResponse.class),
            Map.entry("Enterprise", AccountViews.EnterpriseView.class),
            Map.entry("EnterpriseList", AccountViews.EnterpriseListView.class),
            Map.entry("EnterpriseMember", AccountViews.EnterpriseMemberView.class),
            Map.entry("PendingInvitation", AccountViews.PendingInvitationView.class),
            Map.entry("EnterpriseMembers", AccountViews.EnterpriseMembersView.class),
            Map.entry("CreatedInvitation", AccountViews.CreatedInvitationView.class),
            Map.entry("ReceivedInvitation", AccountViews.ReceivedInvitationView.class));

    private static final Map<String, Class<? extends Record>> REQUESTS = Map.ofEntries(
            Map.entry("LoginCodeRequest", PhoneAuthenticationController.LoginCodeRequest.class),
            Map.entry("PhoneLoginRequest", PhoneAuthenticationController.PhoneLoginRequest.class),
            Map.entry("PhonePasswordLogin", PersonalPasswordController.PhonePasswordRequest.class),
            Map.entry("PasswordSetup", PersonalPasswordController.SetupRequest.class),
            Map.entry("PasswordChange", PersonalPasswordController.ChangeRequest.class),
            Map.entry("PasswordReset", PersonalPasswordController.ResetRequest.class),
            Map.entry("AccountCodeRequest", AccountController.AccountCodeRequest.class),
            Map.entry("CodeOnly", AccountController.CodeOnly.class),
            Map.entry("PhoneChangeRequest", AccountController.PhoneChangeRequest.class),
            Map.entry("DataExportRequest", AccountController.DataExportRequest.class),
            Map.entry("IdentitySwitchRequest", EnterpriseController.IdentitySwitchRequest.class),
            Map.entry("EnterpriseForm", EnterpriseController.EnterpriseForm.class),
            Map.entry("MemberRoleForm", EnterpriseController.MemberRoleForm.class),
            Map.entry("InvitationForm", EnterpriseController.InvitationForm.class),
            Map.entry("InvitationResponseForm", EnterpriseController.InvitationResponseForm.class),
            Map.entry("Device", com.lowaltitude.inspection.bootstrap.security.LoginDeviceRequest.class));

    @Test
    void versionOneDeclaresBearerJwtAndNoGlobalSecurity() {
        Map<String, Object> root = root();
        assertThat(map(root.get("info")).get("version")).isEqualTo("1.1.0");
        assertThat(map(map(root.get("components")).get("securitySchemes")).get("bearerAuth"))
                .isEqualTo(Map.of("type", "http", "scheme", "bearer", "bearerFormat", "JWT"));
        assertThat(root).doesNotContainKey("security");
    }

    @Test
    void everyRouteIsDeclaredAndOnlyThePublicOnesNeedNoLogin() {
        Map<String, Map<String, Object>> declared = declaredOperations();
        Set<String> implemented = new TreeSet<>(routes());

        assertThat(declared.keySet()).containsExactlyInAnyOrderElementsOf(implemented);
        declared.forEach((key, operation) -> {
            if (PUBLIC.contains(key)) {
                assertThat(operation.get("security")).as(key).isEqualTo(List.of());
            } else {
                assertThat(operation.get("security")).as(key).isEqualTo(List.of(Map.of("bearerAuth", List.of())));
                assertThat(map(operation.get("responses"))).as(key).containsKey("401");
            }
        });
    }

    @Test
    void responseFieldsMatchTheRecordsAndAreAllRequired() {
        Map<String, Object> schemas = schemas();
        RESPONSES.forEach((name, type) -> {
            Map<String, Object> schema = map(schemas.get(name));
            assertThat(schema).as(name).isNotNull();
            List<String> fields = components(type);
            assertThat(map(schema.get("properties")).keySet()).as(name).containsExactlyInAnyOrderElementsOf(fields);
            assertThat(strings(schema.get("required"))).as(name).containsExactlyInAnyOrderElementsOf(fields);
            assertThat(schema.get("additionalProperties")).as(name).isEqualTo(false);
        });
    }

    @Test
    void requestFieldsMatchWhatTheControllersRead() {
        Map<String, Object> schemas = schemas();
        assertThat(components(PersonalPasswordController.ResetCodeRequest.class))
                .containsExactlyElementsOf(components(PhoneAuthenticationController.LoginCodeRequest.class));
        REQUESTS.forEach((name, type) -> {
            Map<String, Object> schema = map(schemas.get(name));
            assertThat(schema).as(name).isNotNull();
            assertThat(map(schema.get("properties")).keySet()).as(name)
                    .containsExactlyInAnyOrderElementsOf(components(type));
        });
    }

    @Test
    void everyBusinessRefusalIsAnEnumValueUnderItsStatus() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        List<String> codes = strings(map(schemas().get("IdentityAccountErrorCode")).get("enum"));
        Map<String, String> statusDescriptions = new LinkedHashMap<>();
        Map<String, Object> responses = map(map(root().get("components")).get("responses"));
        Map<String, String> byStatus = Map.of("400", "BadRequest", "401", "Unauthorized", "403", "Forbidden",
                "404", "NotFound", "409", "Conflict", "429", "TooMany", "503", "Unavailable");
        byStatus.forEach((status, response) -> statusDescriptions.put(status,
                (String) map(responses.get(response)).get("description")));

        List<String> businessCodes = new ArrayList<>();
        for (AccountException.Error error : AccountException.Error.values()) {
            ResponseEntity<ApiError> answer = handler.handleAccount(new AccountException(error),
                    new MockHttpServletRequest());
            assertThat(answer.getStatusCode().value()).as(error.name()).isEqualTo(error.status());
            String code = answer.getBody().code();
            assertThat(answer.getBody().message()).as(error.name()).isNotBlank();
            if (code.startsWith("COMMON_")) {
                continue;
            }
            businessCodes.add(code);
            assertThat(codes).as(code).contains(code);
            String status = Integer.toString(error.status());
            if (!"409".equals(status)) {
                assertThat(statusDescriptions.get(status)).as(code).contains(code);
            }
        }
        assertThat(codes).containsExactlyInAnyOrderElementsOf(businessCodes);
    }

    private static List<String> routes() {
        List<String> routes = new ArrayList<>();
        for (Class<?> controller : List.of(AccountController.class, EnterpriseController.class,
                PhoneAuthenticationController.class, PersonalPasswordController.class)) {
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping != null) {
                    routes.add(mapping.method()[0].name() + " " + mapping.path()[0]);
                }
            }
        }
        return routes;
    }

    private static List<String> components(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).collect(Collectors.toList());
    }

    private static Map<String, Map<String, Object>> declaredOperations() {
        Map<String, Map<String, Object>> operations = new LinkedHashMap<>();
        map(root().get("paths")).forEach((path, item) -> map(item).forEach((method, operation) ->
                operations.put(method.toUpperCase(java.util.Locale.ROOT) + " " + path, map(operation))));
        return operations;
    }

    private static Map<String, Object> schemas() {
        return map(map(root().get("components")).get("schemas"));
    }

    private static Map<String, Object> root() {
        try {
            return new Yaml().load(Files.readString(CONTRACT));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return (List<String>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
