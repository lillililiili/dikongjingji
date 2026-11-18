package com.lowaltitude.inspection.bootstrap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.IdentityAccount;
import com.lowaltitude.inspection.identity.domain.IdentityAccountPort;
import com.lowaltitude.inspection.identity.domain.IdentityProfile;
import com.lowaltitude.inspection.identity.domain.IdentityProfilePort;
import com.lowaltitude.inspection.identity.domain.IdentityAccountStatus;
import com.lowaltitude.inspection.identity.domain.PermissionCode;
import com.lowaltitude.inspection.identity.domain.TokenVersionPort;
import com.lowaltitude.inspection.activity.application.ActivityCatalogReadService;
import com.lowaltitude.inspection.activity.application.ActivityEnrollmentService;
import com.lowaltitude.inspection.experience.application.ExperienceCatalogReadService;
import com.lowaltitude.inspection.mall.application.MallCatalogReadService;
import com.lowaltitude.inspection.mall.application.MallCartService;
import com.lowaltitude.inspection.account.application.AccountAddressService;
import com.lowaltitude.inspection.notification.application.NotificationService;
import com.lowaltitude.inspection.application.city.CityDirectoryReadService;
import com.lowaltitude.inspection.application.reservation.InspectionServiceCatalogReadService;
import com.lowaltitude.inspection.application.reservation.ReservationApplicationService;
import com.lowaltitude.inspection.application.reservation.CustomerReservationQueryService;
import com.lowaltitude.inspection.application.serviceflow.DeliveryApplicationService;
import com.lowaltitude.inspection.application.serviceflow.OrderApplicationService;
import com.lowaltitude.inspection.application.serviceflow.PlanApplicationService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@MockBean({
        PlanApplicationService.class,
        OrderApplicationService.class,
        DeliveryApplicationService.class,
        CustomerReservationQueryService.class
})
@Import({
        AuthenticationSecurityIntegrationTest.SecurityProbeConfiguration.class,
        AuthenticationSecurityIntegrationTest.TestIdentityConfiguration.class
})
class AuthenticationSecurityIntegrationTest {

    private static final String HMAC_BASE64_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final UUID SUBJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORGANIZATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final java.util.concurrent.atomic.AtomicBoolean PROFILE_PRESENT =
            new java.util.concurrent.atomic.AtomicBoolean(true);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void loginUsesBcryptAndIssuesJwtWithIdentityClaims() throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"test-user\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String token = com.jayway.jsonpath.JsonPath.read(response, "$.accessToken");
        Map<String, Object> claims = SignedJWT.parse(token).getJWTClaimsSet().getClaims();

        org.assertj.core.api.Assertions.assertThat(jwtDecoder.decode(token).getSubject()).isEqualTo(SUBJECT_ID.toString());
        org.assertj.core.api.Assertions.assertThat(claims)
                .containsEntry("sub", SUBJECT_ID.toString())
                .containsEntry("subjectId", SUBJECT_ID.toString())
                .containsEntry("organizationId", ORGANIZATION_ID.toString());
        org.assertj.core.api.Assertions.assertThat(((Number) claims.get("tokenVersion")).intValue()).isEqualTo(7);
        org.assertj.core.api.Assertions.assertThat(((List<?>) claims.get("roles")).contains("CUSTOMER"))
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(((List<?>) claims.get("permissions"))
                .contains("inspection.reservation.create")).isTrue();
    }

    @Test
    void signedInAccountReadsItsOwnProfileAndNothingElse() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + signedToken(List.of(), 7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("测试客户"))
                .andExpect(jsonPath("$.organizationName").value("测试客户单位"))
                .andExpect(jsonPath("$.organizationType").value("CUSTOMER"))
                .andExpect(jsonPath("$.roles").isArray())
                // 资料里不带登录名、手机号、口令、令牌版本这类字段。
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void profileNeedsALoginAndAnAccountThatStillExists() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
        // 令牌版本过期的令牌在读取资料前就被拒绝。
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + signedToken(List.of(), 6)))
                .andExpect(status().isUnauthorized());

        PROFILE_PRESENT.set(false);
        try {
            mockMvc.perform(get("/api/v1/auth/me")
                            .header("Authorization", "Bearer " + signedToken(List.of(), 7)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
        } finally {
            PROFILE_PRESENT.set(true);
        }
    }

    @Test
    void loginRejectsInvalidPasswordWithApiError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"test-user\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    void lockedAccountCannotLogIn() throws Exception {
        assertLoginIsUnauthorized("locked-user");
    }

    @Test
    void disabledAccountCannotLogIn() throws Exception {
        assertLoginIsUnauthorized("disabled-user");
    }

    @Test
    void blankLoginCredentialsUseTheInvalidArgumentApiError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"   \",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }

    @Test
    void loginNameLongerThanContractMaximumUsesTheInvalidArgumentApiError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + "a".repeat(81) + "\",\"password\":\"test-password\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }

    @Test
    void passwordLongerThanContractMaximumUsesTheInvalidArgumentApiError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"test-user\",\"password\":\"" + "a".repeat(257) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }

    @Test
    void malformedLoginJsonUsesTheInvalidArgumentApiError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }

    @Test
    void healthAndLoginArePublicButOtherRequestsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/test/security/authenticated"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    @Test
    void anonymousCatalogGetIsPublicButOtherCatalogMethodsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/inspection/services")
                        .param("cityCode", "370500"))
                .andExpect(status().isOk());

        mockMvc.perform(get(
                        "/api/v1/inspection/services/33333333-3333-3333-3333-333333333333")
                        .param("cityCode", "370500"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/inspection/services"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    @Test
    void anonymousActivityReadsArePublicButOtherActivityMethodsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/activities").param("cityCode", "370500"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/activities/session-dates")
                        .param("cityCode", "370500")
                        .param("from", "2026-11-16")
                        .param("to", "2026-11-30"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/activities/a2000000-0000-4000-8000-000000000001")
                        .param("cityCode", "370500"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/activities"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
        // 报名接口在 /api/v1/activity-enrollments 等路径下，都要登录；/activities 下更深的路径也不在公开白名单里。
        mockMvc.perform(get("/api/v1/activities/a2000000-0000-4000-8000-000000000001/enrollments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    // 参与人、报名、核销全部要登录：公开的只有 /api/v1/activities 下的三个读取接口。
    @Test
    void activityParticipantEnrollmentAndCheckinRoutesRequireALogin() throws Exception {
        String participant = "/api/v1/activity-participants/a4000000-0000-4000-8000-000000000002";
        String enrollment = "/api/v1/activity-enrollments/a5000000-0000-4000-8000-000000000001";

        for (var request : List.of(
                get("/api/v1/activity-participants"),
                post("/api/v1/activity-participants"),
                put(participant),
                put(participant + "/photo-use"),
                delete(participant),
                post("/api/v1/activity-enrollments"),
                get("/api/v1/activity-enrollments"),
                get(enrollment),
                post(enrollment + "/cancel"),
                post("/api/v1/activity-checkins"))) {
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
        }
        // 过期的令牌版本和没带令牌一样。
        mockMvc.perform(get("/api/v1/activity-enrollments")
                        .header("Authorization", "Bearer " + signedToken(List.of(), 6)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    // 登录后账号、角色、机构都取自令牌：客户读自己的参与人，服务商操作员核销，互相不能越界。
    @Test
    void activityEnrollmentUsesTheSubjectRolesAndOrganizationOfTheToken() throws Exception {
        ActivityEnrollmentService enrollments = applicationContext.getBean(ActivityEnrollmentService.class);
        String customerToken = signedToken(List.of(), 7);
        Map<String, Object> operatorClaims = validClaims(List.of(), 7);
        operatorClaims.put("roles", List.of("PROVIDER_OPERATOR"));
        String operatorToken = signedToken(operatorClaims);
        String checkin = "{\"ticketCode\":\"ACT-7K2M-9QXD-4HTP\"}";

        mockMvc.perform(get("/api/v1/activity-participants")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/activity-checkins")
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkin))
                .andExpect(status().isOk());

        // “本人”的称呼来自账号资料的显示名，不是登录名。
        org.mockito.Mockito.verify(enrollments).listParticipants(SUBJECT_ID, "测试客户", null);
        org.mockito.Mockito.verify(enrollments).checkIn(SUBJECT_ID, ORGANIZATION_ID, "ACT-7K2M-9QXD-4HTP");

        mockMvc.perform(post("/api/v1/activity-checkins")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        mockMvc.perform(get("/api/v1/activity-participants")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        org.mockito.Mockito.verifyNoMoreInteractions(enrollments);
    }

    @Test
    void anonymousExperienceAndVenueReadsArePublicButOtherMethodsRequireAuthentication() throws Exception {
        String venue = "/api/v1/venues/b2000000-0000-4000-8000-000000000001";
        mockMvc.perform(get("/api/v1/experiences")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/venues").param("cityCode", "370500")).andExpect(status().isOk());
        mockMvc.perform(get(venue).param("cityCode", "370500")).andExpect(status().isOk());
        mockMvc.perform(get(venue + "/sessions")
                        .param("cityCode", "370500")
                        .param("projectId", "b4000000-0000-4000-8000-000000000101")
                        .param("date", "2026-11-17"))
                .andExpect(status().isOk());
        mockMvc.perform(get(venue + "/devices").param("cityCode", "370500")).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/venues"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
        // 到馆预约还不存在；白名单之外的路径一律要登录。
        mockMvc.perform(get(venue + "/bookings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    // 站内信全部要登录；登录后收件人只来自令牌。
    @Test
    void notificationsRequireALoginOnEveryRoute() throws Exception {
        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
        mockMvc.perform(get("/api/v1/notifications/unread-count"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/notifications/read-all"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/notifications/70000000-0000-4000-8000-000000000001/read"))
                .andExpect(status().isUnauthorized());

        org.mockito.Mockito.when(applicationContext.getBean(NotificationService.class).unreadCount(SUBJECT_ID))
                .thenReturn(null);
        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header("Authorization", "Bearer " + signedToken(List.of(), 7)))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(applicationContext.getBean(NotificationService.class)).unreadCount(SUBJECT_ID);
    }

    @Test
    void anonymousHomeRecommendIsPublicAndReadOnly() throws Exception {
        mockMvc.perform(get("/api/v1/home/recommend").param("cityCode", "370500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activities").isArray());
        mockMvc.perform(post("/api/v1/home/recommend"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
        mockMvc.perform(get("/api/v1/home/other"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    @Test
    void anonymousMallReadsArePublicButCartAndOrderPathsRequireAuthentication() throws Exception {
        String product = "/api/v1/mall/products/c2000000-0000-4000-8000-000000000101";
        mockMvc.perform(get("/api/v1/mall/products").param("cityCode", "370500")).andExpect(status().isOk());
        mockMvc.perform(get(product).param("cityCode", "370500")).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/mall/products"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
        // 购物车要登录；订单还不存在，白名单之外的路径一律要登录。
        mockMvc.perform(get("/api/v1/mall/cart"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
        mockMvc.perform(get(product + "/offers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    // 收货地址、购物车、结算预览全部要登录：商城公开的只有商品列表和商品详情两个读取接口。
    @Test
    void addressCartAndCheckoutRoutesRequireALogin() throws Exception {
        String address = "/api/v1/account/addresses/d1000000-0000-4000-8000-000000000001";
        String cartItem = "/api/v1/mall/cart/items/c5000000-0000-4000-8000-000000000001";

        for (var request : List.of(
                get("/api/v1/account/addresses"),
                post("/api/v1/account/addresses"),
                put(address),
                delete(address),
                get("/api/v1/mall/cart").param("cityCode", "370500"),
                get("/api/v1/mall/cart/count").param("cityCode", "370500"),
                post("/api/v1/mall/cart/items"),
                put(cartItem),
                post("/api/v1/mall/cart/remove"),
                post("/api/v1/mall/cart/remove-invalid"),
                get("/api/v1/mall/checkout-preview").param("cityCode", "370500"))) {
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
        }
        // 过期的令牌版本和没带令牌一样。
        mockMvc.perform(get("/api/v1/mall/cart/count")
                        .param("cityCode", "370500")
                        .header("Authorization", "Bearer " + signedToken(List.of(), 6)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    // 登录后账号只取自令牌，请求里写别人的账号没有用；不是客户角色的账号一律 403，服务一次都不调。
    @Test
    void addressesAndCartUseTheSubjectOfTheToken() throws Exception {
        AccountAddressService addresses = applicationContext.getBean(AccountAddressService.class);
        MallCartService carts = applicationContext.getBean(MallCartService.class);
        org.mockito.Mockito.clearInvocations(addresses, carts);
        String customerToken = signedToken(List.of(), 7);
        Map<String, Object> operatorClaims = validClaims(List.of(), 7);
        operatorClaims.put("roles", List.of("PROVIDER_OPERATOR"));
        String operatorToken = signedToken(operatorClaims);
        String someoneElse = "11111111-1111-1111-1111-222222222222";
        String offerId = "c4000000-0000-4000-8000-000000000101";

        mockMvc.perform(get("/api/v1/account/addresses")
                        .param("ownerId", someoneElse)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/mall/cart/count")
                        .param("cityCode", "370500")
                        .param("accountId", someoneElse)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/mall/cart/items")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"offerId\":\"" + offerId + "\",\"quantity\":1,\"cityCode\":\"370500\","
                                + "\"accountId\":\"" + someoneElse + "\"}"))
                .andExpect(status().isCreated());

        org.mockito.Mockito.verify(addresses).list(SUBJECT_ID);
        org.mockito.Mockito.verify(carts).count(SUBJECT_ID, "370500");
        org.mockito.Mockito.verify(carts).add(SUBJECT_ID, UUID.fromString(offerId), 1, "370500");

        mockMvc.perform(get("/api/v1/account/addresses")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        mockMvc.perform(get("/api/v1/mall/cart")
                        .param("cityCode", "370500")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        mockMvc.perform(get("/api/v1/mall/checkout-preview")
                        .param("cityCode", "370500")
                        .param("offerId", offerId)
                        .param("quantity", "1")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        org.mockito.Mockito.verifyNoMoreInteractions(addresses, carts);
    }

    @Test
    void anonymousCityDirectoryOperationsArePublicButOtherCityOperationsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/platform/cities"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/platform/cities/370100/search-selections"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("COMMON_SERVICE_UNAVAILABLE"));

        mockMvc.perform(post("/api/v1/platform/cities"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    @Test
    void inspectionLanDiscoveryIsPublicAndIdentifiesThisService() throws Exception {
        mockMvc.perform(get("/api/v1/inspection/discovery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("low-altitude-inspection"))
                .andExpect(jsonPath("$.contract").value("inspection-lan-discovery-v1"));
    }

    @Test
    void platformWeatherGetIsPublicCityInformation() throws Exception {
        mockMvc.perform(get("/api/v1/platform/weather/current")
                        .param("cityName", "济南市"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PLATFORM_WEATHER_UNAVAILABLE"));
    }

    @Test
    void configuredBrowserOriginCanCompleteAuthenticatedApiPreflight() throws Exception {
        mockMvc.perform(options("/api/v1/inspection/reservations")
                        .header("Origin", "http://127.0.0.1:4173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers",
                                "authorization,content-type,idempotency-key,if-match,x-request-id"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Access-Control-Allow-Origin",
                        "http://127.0.0.1:4173"))
                .andExpect(header().string(
                        "Access-Control-Allow-Headers",
                        org.hamcrest.Matchers.containsString("if-match")));
    }

    @Test
    void malformedTokenIsRejectedWithApiError() throws Exception {
        mockMvc.perform(get("/test/security/authenticated")
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    @Test
    void tokenWithWrongSignatureIsRejected() throws Exception {
        assertUnauthorized(signedToken(validClaims(List.of(), 7),
                "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8),
                Instant.now().plusSeconds(300)));
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        assertUnauthorized(signedToken(validClaims(List.of(), 7), hmacKey(), Instant.now().minusSeconds(1)));
    }

    @Test
    void authenticatedSubjectWithoutPermissionIsForbidden() throws Exception {
        mockMvc.perform(get("/test/security/permission")
                        .header("Authorization", "Bearer " + signedToken(List.of(), 7)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
    }

    @Test
    void authenticatedSubjectWithPermissionIsAllowed() throws Exception {
        mockMvc.perform(get("/test/security/permission")
                        .header("Authorization", "Bearer " + signedToken(List.of(PermissionCode.INSPECTION_RESERVATION_CREATE), 7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectId").value(SUBJECT_ID.toString()));
    }

    @Test
    void tokenWithStaleTokenVersionIsRejected() throws Exception {
        mockMvc.perform(get("/test/security/authenticated")
                        .header("Authorization", "Bearer " + signedToken(List.of(), 6)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    @Test
    void mismatchedSubjectIdClaimIsRejected() throws Exception {
        Map<String, Object> claims = validClaims(List.of(), 7);
        claims.put("subjectId", "33333333-3333-3333-3333-333333333333");

        assertUnauthorized(signedToken(claims));
    }

    @Test
    void missingSubjectIdClaimIsRejected() throws Exception {
        Map<String, Object> claims = validClaims(List.of(), 7);
        claims.remove("subjectId");

        assertUnauthorized(signedToken(claims));
    }

    @Test
    void missingSubjectClaimIsRejected() throws Exception {
        Map<String, Object> claims = validClaims(List.of(), 7);
        claims.remove("sub");

        assertUnauthorized(signedToken(claims));
    }

    @Test
    void nonUuidSubjectClaimIsRejected() throws Exception {
        Map<String, Object> claims = validClaims(List.of(), 7);
        claims.put("sub", "not-a-uuid");

        assertUnauthorized(signedToken(claims));
    }

    @Test
    void invalidOrganizationIdClaimIsRejected() throws Exception {
        Map<String, Object> claims = validClaims(List.of(), 7);
        claims.put("organizationId", "not-a-uuid");

        assertUnauthorized(signedToken(claims));
    }

    @Test
    void nonStringRoleCollectionIsRejected() throws Exception {
        Map<String, Object> claims = validClaims(List.of(), 7);
        claims.put("roles", List.of("CUSTOMER", 1));

        assertUnauthorized(signedToken(claims));
    }

    @Test
    void missingRolesClaimIsRejected() throws Exception {
        Map<String, Object> claims = validClaims(List.of(), 7);
        claims.remove("roles");

        assertUnauthorized(signedToken(claims));
    }

    @Test
    void nonStringPermissionCollectionIsRejected() throws Exception {
        Map<String, Object> claims = validClaims(List.of(), 7);
        claims.put("permissions", List.of(PermissionCode.INSPECTION_RESERVATION_CREATE, 1));

        assertUnauthorized(signedToken(claims));
    }

    @Test
    void missingPermissionsClaimIsRejected() throws Exception {
        Map<String, Object> claims = validClaims(List.of(), 7);
        claims.remove("permissions");

        assertUnauthorized(signedToken(claims));
    }

    @Test
    void fractionalTokenVersionIsRejected() throws Exception {
        Map<String, Object> claims = validClaims(List.of(), 7);
        claims.put("tokenVersion", 7.5D);

        assertUnauthorized(signedToken(claims));
    }

    @Test
    void negativeTokenVersionIsRejected() throws Exception {
        assertUnauthorized(signedToken(validClaims(List.of(), -1)));
    }

    @Test
    void testIdentityPortsReplaceTheProductionFallbackBeans() {
        org.assertj.core.api.Assertions.assertThat(applicationContext.getBeansOfType(IdentityAccountPort.class))
                .hasSize(1)
                .containsKey("testIdentityAccountPort");
        org.assertj.core.api.Assertions.assertThat(applicationContext.getBeansOfType(TokenVersionPort.class))
                .hasSize(1)
                .containsKey("testTokenVersionPort");
    }

    private String signedToken(List<String> permissions, int tokenVersion) throws Exception {
        return signedToken(validClaims(permissions, tokenVersion));
    }

    private void assertUnauthorized(String token) throws Exception {
        mockMvc.perform(get("/test/security/authenticated")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    private void assertLoginIsUnauthorized(String loginName) throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + loginName + "\",\"password\":\"test-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    private Map<String, Object> validClaims(List<String> permissions, int tokenVersion) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", SUBJECT_ID.toString());
        claims.put("subjectId", SUBJECT_ID.toString());
        claims.put("roles", List.of("CUSTOMER"));
        claims.put("organizationId", ORGANIZATION_ID.toString());
        claims.put("permissions", permissions);
        claims.put("tokenVersion", tokenVersion);
        return claims;
    }

    private String signedToken(Map<String, Object> claims) throws Exception {
        return signedToken(claims, hmacKey(), Instant.now().plusSeconds(300));
    }

    private String signedToken(
            Map<String, Object> claims,
            byte[] signingKey,
            Instant expiresAt
    ) throws Exception {
        JWTClaimsSet.Builder claimBuilder = new JWTClaimsSet.Builder()
                .issueTime(java.util.Date.from(Instant.now()))
                .expirationTime(java.util.Date.from(expiresAt));
        Object subject = claims.get("sub");
        if (subject instanceof String subjectValue) {
            claimBuilder.subject(subjectValue);
        }
        claims.forEach((name, value) -> {
            if (!"sub".equals(name)) {
                claimBuilder.claim(name, value);
            }
        });
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimBuilder.build());
        jwt.sign(new MACSigner(signingKey));
        return jwt.serialize();
    }

    private byte[] hmacKey() {
        return Base64.getDecoder().decode(HMAC_BASE64_SECRET);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(SecurityProbeController.class)
    static class SecurityProbeConfiguration {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestIdentityConfiguration {

        @Bean
        @Primary
        IdentityAccountPort testIdentityAccountPort(PasswordEncoder passwordEncoder) {
            return loginName -> switch (loginName) {
                case "test-user" -> java.util.Optional.of(account(passwordEncoder, loginName, IdentityAccountStatus.ACTIVE));
                case "locked-user" -> java.util.Optional.of(account(passwordEncoder, loginName, IdentityAccountStatus.LOCKED));
                case "disabled-user" -> java.util.Optional.of(account(passwordEncoder, loginName, IdentityAccountStatus.DISABLED));
                default -> java.util.Optional.empty();
            };
        }

        // Only the signed-in subject has a profile; the name deliberately differs from the login name.
        @Bean
        @Primary
        IdentityProfilePort testIdentityProfilePort() {
            return subjectId -> PROFILE_PRESENT.get() && SUBJECT_ID.equals(subjectId)
                    ? java.util.Optional.of(new IdentityProfile(SUBJECT_ID, "测试客户", "测试客户单位", "CUSTOMER"))
                    : java.util.Optional.empty();
        }

        @Bean
        @Primary
        TokenVersionPort testTokenVersionPort() {
            return subjectId -> SUBJECT_ID.equals(subjectId)
                    ? java.util.OptionalInt.of(7)
                    : java.util.OptionalInt.empty();
        }

        @Bean
        ReservationApplicationService testReservationApplicationService() {
            return org.mockito.Mockito.mock(ReservationApplicationService.class);
        }

        @Bean
        InspectionServiceCatalogReadService testInspectionServiceCatalogReadService() {
            return org.mockito.Mockito.mock(InspectionServiceCatalogReadService.class);
        }

        @Bean
        ActivityCatalogReadService testActivityCatalogReadService() {
            return org.mockito.Mockito.mock(ActivityCatalogReadService.class);
        }

        @Bean
        ActivityEnrollmentService testActivityEnrollmentService() {
            return org.mockito.Mockito.mock(ActivityEnrollmentService.class);
        }

        @Bean
        ExperienceCatalogReadService testExperienceCatalogReadService() {
            return org.mockito.Mockito.mock(ExperienceCatalogReadService.class);
        }

        @Bean
        MallCatalogReadService testMallCatalogReadService() {
            return org.mockito.Mockito.mock(MallCatalogReadService.class);
        }

        @Bean
        NotificationService testNotificationService() {
            return org.mockito.Mockito.mock(NotificationService.class);
        }

        @Bean
        AccountAddressService testAccountAddressService() {
            return org.mockito.Mockito.mock(AccountAddressService.class);
        }

        @Bean
        MallCartService testMallCartService() {
            return org.mockito.Mockito.mock(MallCartService.class);
        }

        @Bean
        CityDirectoryReadService testCityDirectoryReadService() {
            return new CityDirectoryReadService(new CityDirectoryReadService.CityDirectoryStore() {
                @Override
                public List<CityDirectoryReadService.StoredCity> findActiveCities() {
                    return List.of(new CityDirectoryReadService.StoredCity(
                            "370100", "济南", "jinan", "J", "370000", "山东", "shandong", "S", 1));
                }

                @Override
                public int incrementSearchCount(String cityCode) {
                    return "370100".equals(cityCode) ? 1 : 0;
                }
            });
        }

        private IdentityAccount account(
                PasswordEncoder passwordEncoder,
                String loginName,
                IdentityAccountStatus status
        ) {
            return new IdentityAccount(
                    SUBJECT_ID,
                    loginName,
                    passwordEncoder.encode("test-password"),
                    status,
                    java.util.Set.of("CUSTOMER"),
                    ORGANIZATION_ID,
                    java.util.Set.of(PermissionCode.INSPECTION_RESERVATION_CREATE),
                    7
            );
        }
    }

    @RestController
    static class SecurityProbeController {

        @GetMapping("/test/security/authenticated")
        Map<String, String> authenticated(java.security.Principal principal) {
            return Map.of("subjectId", principal == null ? "anonymous" : principal.getName());
        }

        @GetMapping("/test/security/permission")
        @PreAuthorize("hasAuthority('inspection.reservation.create')")
        Map<String, String> permission(java.security.Principal principal) {
            return Map.of("subjectId", principal == null ? "anonymous" : principal.getName());
        }
    }
}
