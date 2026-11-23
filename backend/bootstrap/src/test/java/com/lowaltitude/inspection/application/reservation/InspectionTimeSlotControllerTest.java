package com.lowaltitude.inspection.application.reservation;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

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
import com.lowaltitude.inspection.bootstrap.BootstrapApplication;
import com.lowaltitude.inspection.application.city.CityDirectoryReadService;
import com.lowaltitude.inspection.application.serviceflow.DeliveryApplicationService;
import com.lowaltitude.inspection.application.serviceflow.OrderApplicationService;
import com.lowaltitude.inspection.application.serviceflow.PlanApplicationService;
import com.lowaltitude.inspection.identity.domain.TokenVersionPort;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = BootstrapApplication.class)
@AutoConfigureMockMvc
@MockBean({
        CityDirectoryReadService.class,
        ReservationApplicationService.class,
        CustomerReservationQueryService.class,
        InspectionServiceCatalogReadService.class,
        ActivityCatalogReadService.class,
        ActivityEnrollmentService.class,
        VenueBookingService.class,
        com.lowaltitude.inspection.experience.application.ExperienceRecordService.class,
        com.lowaltitude.inspection.demand.application.DemandService.class,
        com.lowaltitude.inspection.proservice.application.ProServiceReadService.class,
        com.lowaltitude.inspection.rental.application.RentalService.class,
        com.lowaltitude.inspection.mall.application.MallInquiryService.class,
        com.lowaltitude.inspection.support.application.SupportService.class,
        // S3-1 运营端
        com.lowaltitude.inspection.application.city.CityOperationsService.class,
        com.lowaltitude.inspection.identity.application.OrganizationOnboardingService.class,
        // S3-1 第二部分：商品与供给、收款账户、批量导入
        com.lowaltitude.inspection.mall.application.MallCatalogOpsService.class,
        com.lowaltitude.inspection.mall.application.MallMerchantService.class,
        com.lowaltitude.inspection.trade.application.TradeAftersaleService.class,
        com.lowaltitude.inspection.trade.application.OpsMerchantAccountService.class,
        com.lowaltitude.inspection.opsimport.application.OpsImportService.class,
        com.lowaltitude.inspection.activity.application.ops.ActivityOpsService.class,
        com.lowaltitude.inspection.experience.application.ops.VenueOpsService.class,
        com.lowaltitude.inspection.content.application.ops.ContentOpsService.class,
        // S3-1 第三部分：巡检和专业服务目录
        com.lowaltitude.inspection.application.catalog.InspectionCatalogOpsService.class,
        com.lowaltitude.inspection.proservice.application.ProServiceOpsService.class,
        com.lowaltitude.inspection.live.application.LiveOpsService.class,
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
        com.lowaltitude.inspection.application.serviceflow.InspectionPaymentService.class,
        com.lowaltitude.inspection.application.serviceflow.VisitChangeService.class,
        DeliveryApplicationService.class
})
@Import(InspectionTimeSlotControllerTest.TokenVersionTestConfiguration.class)
class InspectionTimeSlotControllerTest {

    private static final String HMAC_BASE64_SECRET =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final UUID CUSTOMER =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORGANIZATION =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SERVICE =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final LocalDate START_DATE = LocalDate.of(2026, 8, 1);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ReservationApplicationService reservationService;

    @Test
    void anonymousCustomerCannotReadAvailableTimeSlots() throws Exception {
        mvc.perform(get(
                        "/api/v1/inspection/services/{serviceId}/available-time-slots",
                        SERVICE)
                        .param("cityCode", "370500")
                        .param("startDate", START_DATE.toString())
                        .param("days", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    @Test
    void authenticatedCustomerReadsAvailableTimeSlotsWithRequestedParameters() throws Exception {
        given(reservationService.availableTimeSlots(SERVICE, "370500", START_DATE, 1))
                .willReturn(availability());

        mvc.perform(get(
                        "/api/v1/inspection/services/{serviceId}/available-time-slots",
                        SERVICE)
                        .param("cityCode", "370500")
                        .param("startDate", START_DATE.toString())
                        .param("days", "1")
                        .header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].serviceDate").value("2026-08-01"))
                .andExpect(jsonPath("$[0].slots[0].slotCode").value("MORNING"))
                .andExpect(jsonPath("$[0].slots[0].remainingCount").value(9))
                .andExpect(jsonPath("$[0].slots[0].availability").value("TOO_SOON"));

        then(reservationService).should().availableTimeSlots(
                SERVICE,
                "370500",
                START_DATE,
                1);
    }

    // 缺少必填参数是请求写错了，应当 400，不是 500（验收清单已知问题 1）。
    @Test
    void missingStartDateOrCityIsABadRequest() throws Exception {
        mvc.perform(get(
                        "/api/v1/inspection/services/{serviceId}/available-time-slots",
                        SERVICE)
                        .param("cityCode", "370500")
                        .header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mvc.perform(get(
                        "/api/v1/inspection/services/{serviceId}/available-time-slots",
                        SERVICE)
                        .param("startDate", START_DATE.toString())
                        .header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        then(reservationService).shouldHaveNoInteractions();
    }

    private static List<InspectionServiceDateAvailabilityView> availability() {
        return List.of(new InspectionServiceDateAvailabilityView(
                START_DATE,
                List.of(
                        new InspectionTimeSlotView(
                                "MORNING", LocalTime.of(9, 0), LocalTime.of(12, 0), 9, "TOO_SOON"),
                        new InspectionTimeSlotView(
                                "AFTERNOON", LocalTime.of(14, 0), LocalTime.of(17, 0), 9, "AVAILABLE"),
                        new InspectionTimeSlotView(
                                "EVENING", LocalTime.of(18, 0), LocalTime.of(21, 0), 9, "AVAILABLE"))));
    }

    private String validToken() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(CUSTOMER.toString())
                .claim("subjectId", CUSTOMER.toString())
                .claim("roles", List.of("CUSTOMER"))
                .claim("organizationId", ORGANIZATION.toString())
                .claim("permissions", List.of())
                .claim("tokenVersion", 1)
                .issueTime(java.util.Date.from(Instant.now()))
                .expirationTime(java.util.Date.from(Instant.now().plusSeconds(300)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(Base64.getDecoder().decode(HMAC_BASE64_SECRET)));
        return jwt.serialize();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TokenVersionTestConfiguration {

        @Bean
        @Primary
        TokenVersionPort testTokenVersionPort() {
            return subjectId -> CUSTOMER.equals(subjectId)
                    ? java.util.OptionalInt.of(1)
                    : java.util.OptionalInt.empty();
        }
    }
}
