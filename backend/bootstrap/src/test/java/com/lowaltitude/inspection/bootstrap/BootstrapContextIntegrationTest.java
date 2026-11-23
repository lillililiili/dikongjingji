package com.lowaltitude.inspection.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.TokenVersionPort;
import com.lowaltitude.inspection.activity.application.ActivityCatalogReadService;
import com.lowaltitude.inspection.activity.application.ActivityEnrollmentService;
import com.lowaltitude.inspection.experience.application.VenueBookingService;
import com.lowaltitude.inspection.experience.application.ExperienceCatalogReadService;
import com.lowaltitude.inspection.mall.application.MallCatalogReadService;
import com.lowaltitude.inspection.mall.application.MallCartService;
import com.lowaltitude.inspection.account.application.AccountAddressService;
import com.lowaltitude.inspection.trade.application.TradeOrderService;
import com.lowaltitude.inspection.trade.application.TradePaymentService;
import com.lowaltitude.inspection.trade.application.TradeChannelCheckService;
import com.lowaltitude.inspection.trade.application.TradeReconciliationService;
import com.lowaltitude.inspection.trade.application.TradeRefundService;
import com.lowaltitude.inspection.notification.application.NotificationService;
import com.lowaltitude.inspection.application.city.CityDirectoryReadService;
import com.lowaltitude.inspection.application.reservation.CustomerReservationQueryService;
import com.lowaltitude.inspection.application.reservation.InspectionServiceCatalogReadService;
import com.lowaltitude.inspection.application.reservation.ReservationApplicationService;
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
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
@MockBean({
        CityDirectoryReadService.class,
        ReservationApplicationService.class,
        CustomerReservationQueryService.class,
        InspectionServiceCatalogReadService.class,
        ActivityCatalogReadService.class,
        ActivityEnrollmentService.class,
        VenueBookingService.class,
        com.lowaltitude.inspection.demand.application.DemandService.class,
        com.lowaltitude.inspection.proservice.application.ProServiceReadService.class,
        com.lowaltitude.inspection.rental.application.RentalService.class,
        com.lowaltitude.inspection.mall.application.MallInquiryService.class,
        com.lowaltitude.inspection.support.application.SupportService.class,
        // S3-1 运营端
        com.lowaltitude.inspection.application.city.CityOperationsService.class,
        com.lowaltitude.inspection.identity.application.OrganizationOnboardingService.class,
        com.lowaltitude.inspection.activity.application.ops.ActivityOpsService.class,
        com.lowaltitude.inspection.experience.application.ops.VenueOpsService.class,
        com.lowaltitude.inspection.content.application.ops.ContentOpsService.class,
        com.lowaltitude.inspection.live.application.LiveService.class,
        com.lowaltitude.inspection.content.application.ContentReadService.class,
        com.lowaltitude.inspection.content.application.ContentVideoService.class,
        ExperienceCatalogReadService.class,
        MallCatalogReadService.class,
        MallCartService.class,
        AccountAddressService.class,
        TradeOrderService.class,
        TradePaymentService.class,
        TradeRefundService.class,
        TradeReconciliationService.class,
        TradeChannelCheckService.class,
        NotificationService.class,
        PlanApplicationService.class,
        OrderApplicationService.class,
        DeliveryApplicationService.class
})
@Import({
        BootstrapContextIntegrationTest.ErrorControllerTestConfiguration.class,
        BootstrapContextIntegrationTest.TokenVersionTestConfiguration.class
})
class BootstrapContextIntegrationTest {

    private static final String HMAC_BASE64_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final UUID SUBJECT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Environment environment;

    @Test
    void contextIncludesTheProductionBootstrapApplication() {
        assertThat(applicationContext.containsBean("bootstrapApplication")).isTrue();
    }

    @Test
    void healthEndpointReportsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("INSPECTION_MINIO_SECRET_KEY"))));
    }

    @Test
    void testProfileProvidesAnExplicitStorageEnvironment() {
        assertThat(environment.getRequiredProperty(
                "inspection.infrastructure.storage.environment")).isEqualTo("test");
    }

    @Test
    void invalidArgumentUsesTheApiErrorContract() throws Exception {
        mockMvc.perform(get("/test/invalid-request")
                        .header("Authorization", "Bearer " + validToken())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("请求参数无效"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void unexpectedExceptionsUseTheStableApiErrorContract() throws Exception {
        mockMvc.perform(get("/test/unexpected-request")
                        .header("Authorization", "Bearer " + validToken())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("COMMON_INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("系统繁忙，请稍后重试"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(TestErrorController.class)
    static class ErrorControllerTestConfiguration {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TokenVersionTestConfiguration {

        @Bean
        @Primary
        TokenVersionPort testTokenVersionPort() {
            return subjectId -> SUBJECT_ID.equals(subjectId)
                    ? java.util.OptionalInt.of(1)
                    : java.util.OptionalInt.empty();
        }
    }

    @RestController
    static class TestErrorController {

        @GetMapping("/test/invalid-request")
        String invalidRequest() {
            throw new IllegalArgumentException("invalid test request");
        }

        @GetMapping("/test/unexpected-request")
        String unexpectedRequest() {
            throw new IllegalStateException("internal implementation detail");
        }
    }

    private String validToken() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(SUBJECT_ID.toString())
                .claim("subjectId", SUBJECT_ID.toString())
                .claim("roles", List.of())
                .claim("organizationId", "44444444-4444-4444-4444-444444444444")
                .claim("permissions", List.of())
                .claim("tokenVersion", 1)
                .issueTime(java.util.Date.from(Instant.now()))
                .expirationTime(java.util.Date.from(Instant.now().plusSeconds(300)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(Base64.getDecoder().decode(HMAC_BASE64_SECRET)));
        return jwt.serialize();
    }
}
