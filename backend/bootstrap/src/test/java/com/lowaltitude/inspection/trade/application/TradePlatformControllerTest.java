package com.lowaltitude.inspection.trade.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.SerializationFeature;

import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import com.lowaltitude.inspection.trade.application.TradeException.Error;
import com.lowaltitude.inspection.trade.application.TradeViews.ChannelCheckItemView;
import com.lowaltitude.inspection.trade.application.TradeViews.ChannelCheckView;
import com.lowaltitude.inspection.trade.application.TradeViews.MerchantAccountView;
import com.lowaltitude.inspection.trade.application.TradeViews.ReconciliationDifferenceView;
import com.lowaltitude.inspection.trade.application.TradeViews.ReconciliationRunView;
import com.lowaltitude.inspection.trade.application.TradeViews.ReconciliationView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 平台登记商家收款账户：只给 PLATFORM_OPERATOR；路径里的机构和请求体原样交给服务，格式和机构是否存在由服务判断。
 * 对账（E06）：读某一天的结果（JSON 或 CSV 下载）、立刻跑一次，同样只给 PLATFORM_OPERATOR。
 * 没带令牌时的 401 由安全过滤器给出，在 AuthenticationSecurityIntegrationTest 里检查。
 */
class TradePlatformControllerTest {

    private static final UUID OPERATOR = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ORGANIZATION = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final String BODY = "{\"subMchid\":\"1900000109\",\"active\":true}";

    private static final LocalDate DAY = LocalDate.parse("2026-11-17");

    private final TradeOrderService service = mock(TradeOrderService.class);
    private final TradeReconciliationService reconciliation = mock(TradeReconciliationService.class);
    private final TradeChannelCheckService channelCheck = mock(TradeChannelCheckService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 与应用一致：字符串按 UTF-8 写，JSON 的时间写成 ISO 文本。
        mockMvc = MockMvcBuilders.standaloneSetup(new TradePlatformController(service, reconciliation, channelCheck))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
    }

    @Test
    void aPlatformOperatorRegistersTheAccountOfThePathsOrganization() throws Exception {
        when(service.saveMerchantAccount(ORGANIZATION, "1900000109", true))
                .thenReturn(new MerchantAccountView(ORGANIZATION, "1900000109", true));

        mockMvc.perform(register().principal(signedIn("PLATFORM_OPERATOR")).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.organizationId").value(ORGANIZATION.toString()))
                .andExpect(jsonPath("$.subMchid").value("1900000109"))
                .andExpect(jsonPath("$.active").value(true));

        verify(service).saveMerchantAccount(ORGANIZATION, "1900000109", true);
    }

    @Test
    void aJwtWithThePlatformOperatorRoleWorksToo() throws Exception {
        when(service.saveMerchantAccount(ORGANIZATION, "1900000109", false))
                .thenReturn(new MerchantAccountView(ORGANIZATION, "1900000109", false));

        mockMvc.perform(register()
                        .principal(jwt(List.of("CUSTOMER", "PLATFORM_OPERATOR")))
                        .content("{\"subMchid\":\"1900000109\",\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "PROVIDER_OPERATOR", "PROVIDER_ADMIN"})
    void everyOtherRoleIsForbidden(String role) throws Exception {
        mockMvc.perform(register().principal(signedIn(role)).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        mockMvc.perform(register().principal(jwt(List.of(role))).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));

        verifyNoInteractions(service);
    }

    @Test
    void noSubjectOrAJwtWithoutRolesIsForbidden() throws Exception {
        mockMvc.perform(register().content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        mockMvc.perform(register().principal(jwt(null)).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        mockMvc.perform(register().principal(new TestingAuthenticationToken("someone", null)).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));

        verifyNoInteractions(service);
    }

    @Test
    void missingFieldsReachTheServiceAsNullAndAreRefusedThere() throws Exception {
        when(service.saveMerchantAccount(ORGANIZATION, null, null))
                .thenThrow(new IllegalArgumentException("merchant account is invalid"));

        mockMvc.perform(register().principal(signedIn("PLATFORM_OPERATOR")).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));

        verify(service).saveMerchantAccount(ORGANIZATION, null, null);
    }

    @Test
    void anUnknownOrganizationIsNotFound() throws Exception {
        when(service.saveMerchantAccount(ORGANIZATION, "1900000109", true))
                .thenThrow(new TradeException(Error.NOT_FOUND));

        mockMvc.perform(register().principal(signedIn("PLATFORM_OPERATOR")).content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
    }

    @Test
    void aMalformedPathOrBodyIsABadRequest() throws Exception {
        TestingAuthenticationToken operator = signedIn("PLATFORM_OPERATOR");

        mockMvc.perform(put("/api/v1/platform/trade/merchant-accounts/not-a-uuid")
                        .principal(operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(register().principal(operator))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(register().principal(operator).content("{\"subMchid\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(register().principal(operator).content("{\"subMchid\":\"1900000109\",\"active\":\"yes\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));

        verifyNoInteractions(service);
    }

    // ---- 对账（E06） ----

    private static ReconciliationView view() {
        return new ReconciliationView(DAY, Instant.parse("2026-11-18T02:35:10Z"), 12, List.of(
                new ReconciliationDifferenceView("AMOUNT_MISMATCH", "1900000101", "P0000000001", null, 276_000L, 275_000L),
                new ReconciliationDifferenceView("ONLY_IN_PLATFORM", "1900000102", null, "R0000000002", 100L, null)));
    }

    @Test
    void aPlatformOperatorReadsADayAsJson() throws Exception {
        when(reconciliation.read(DAY)).thenReturn(view());

        mockMvc.perform(get("/api/v1/platform/trade/reconciliation").param("date", "2026-11-17")
                        .principal(signedIn("PLATFORM_OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/json"))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.date").value("2026-11-17"))
                .andExpect(jsonPath("$.importedAt").value("2026-11-18T02:35:10Z"))
                .andExpect(jsonPath("$.matchedCount").value(12))
                .andExpect(jsonPath("$.differences.length()").value(2))
                .andExpect(jsonPath("$.differences[0].length()").value(6))
                .andExpect(jsonPath("$.differences[0].kind").value("AMOUNT_MISMATCH"))
                .andExpect(jsonPath("$.differences[0].subMchid").value("1900000101"))
                .andExpect(jsonPath("$.differences[0].outTradeNo").value("P0000000001"))
                .andExpect(jsonPath("$.differences[0].outRefundNo").value(nullValue()))
                .andExpect(jsonPath("$.differences[0].platformAmountMinor").value(276000))
                .andExpect(jsonPath("$.differences[0].channelAmountMinor").value(275000))
                .andExpect(jsonPath("$.differences[1].outTradeNo").value(nullValue()))
                .andExpect(jsonPath("$.differences[1].channelAmountMinor").value(nullValue()));

        verify(reconciliation).read(DAY);
    }

    // format=JSON 写明了也一样；JWT 里带平台运营角色也行。
    @Test
    void anExplicitJsonFormatAndAJwtWork() throws Exception {
        when(reconciliation.read(DAY)).thenReturn(view());

        mockMvc.perform(get("/api/v1/platform/trade/reconciliation").param("date", "2026-11-17").param("format", "JSON")
                        .principal(jwt(List.of("PLATFORM_OPERATOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedCount").value(12));
    }

    @Test
    void aPlatformOperatorDownloadsADayAsCsv() throws Exception {
        when(reconciliation.read(DAY)).thenReturn(view());

        byte[] body = mockMvc.perform(get("/api/v1/platform/trade/reconciliation")
                        .param("date", "2026-11-17").param("format", "CSV")
                        .principal(signedIn("PLATFORM_OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"reconciliation-2026-11-17.csv\""))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        // 字节就是服务给的 CSV 按 UTF-8 编码（开头是 BOM 的三个字节）。
        assertThat(body).isEqualTo(TradeReconciliationService.csv(view()).getBytes(StandardCharsets.UTF_8));
        assertThat(body).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(new String(body, StandardCharsets.UTF_8))
                .contains("2026-11-17,AMOUNT_MISMATCH,金额不一致,1900000101,P0000000001,,2760.00,2750.00\r\n");
    }

    // 那天的账单还没全部导入：409 TRADE_RECONCILIATION_NOT_READY（JSON 和 CSV 都一样）。
    @ParameterizedTest
    @ValueSource(strings = {"JSON", "CSV"})
    void aDayThatIsNotReadyIsAConflict(String format) throws Exception {
        when(reconciliation.read(DAY)).thenThrow(new TradeException(Error.RECONCILIATION_NOT_READY));

        mockMvc.perform(get("/api/v1/platform/trade/reconciliation").param("date", "2026-11-17").param("format", format)
                        .principal(signedIn("PLATFORM_OPERATOR")))
                .andExpect(status().isConflict())
                .andExpect(header().string("Content-Type", "application/json"))
                .andExpect(jsonPath("$.code").value("TRADE_RECONCILIATION_NOT_READY"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // format 只认大写的 JSON、CSV；日期必须给、必须是 yyyy-MM-dd。都在调服务之前拒绝。
    @Test
    void aBadFormatOrDateIsABadRequest() throws Exception {
        TestingAuthenticationToken operator = signedIn("PLATFORM_OPERATOR");
        for (String format : List.of("XML", "csv", "json", " JSON")) {
            mockMvc.perform(get("/api/v1/platform/trade/reconciliation").param("date", "2026-11-17").param("format", format)
                            .principal(operator))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        }
        mockMvc.perform(get("/api/v1/platform/trade/reconciliation").principal(operator))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        for (String date : List.of("", "20261117", "2026-13-01", "2026-11-31", "yesterday")) {
            mockMvc.perform(get("/api/v1/platform/trade/reconciliation").param("date", date).principal(operator))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
            mockMvc.perform(post("/api/v1/platform/trade/reconciliation/runs").param("date", date).principal(operator))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        }
        mockMvc.perform(post("/api/v1/platform/trade/reconciliation/runs").principal(operator))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(post("/api/v1/platform/trade/reconciliation/runs").param("date", "2026-11-17").param("redo", "maybe")
                        .principal(operator))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));

        verifyNoInteractions(reconciliation);
    }

    // 日期只认 ISO 的 yyyy-MM-dd：斜杠写法、按区域设置的短日期（11/17/26）都不当成日期。
    @ParameterizedTest
    @ValueSource(strings = {"2026/11/17", "11/17/26", "17/11/2026", "2026-11-17T00:00:00", "2026-1-7"})
    void aNonIsoDateIsABadRequest(String date) throws Exception {
        TestingAuthenticationToken operator = signedIn("PLATFORM_OPERATOR");

        mockMvc.perform(get("/api/v1/platform/trade/reconciliation").param("date", date).principal(operator))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(get("/api/v1/platform/trade/reconciliation").param("date", date).param("format", "CSV")
                        .principal(operator))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(post("/api/v1/platform/trade/reconciliation/runs").param("date", date).principal(operator))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));

        verifyNoInteractions(reconciliation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "PROVIDER_OPERATOR", "PROVIDER_ADMIN"})
    void reconciliationIsForPlatformOperatorsOnly(String role) throws Exception {
        for (TestingAuthenticationToken principal : List.of(signedIn(role), jwt(List.of(role)))) {
            for (String format : List.of("JSON", "CSV")) {
                mockMvc.perform(get("/api/v1/platform/trade/reconciliation").param("date", "2026-11-17")
                                .param("format", format).principal(principal))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
            }
            mockMvc.perform(post("/api/v1/platform/trade/reconciliation/runs").param("date", "2026-11-17")
                            .principal(principal))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        }
        mockMvc.perform(get("/api/v1/platform/trade/reconciliation").param("date", "2026-11-17"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/platform/trade/reconciliation/runs").param("date", "2026-11-17"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(reconciliation);
    }

    // 跑对账：redo 默认 false；结果原样返回。
    @Test
    void aPlatformOperatorRunsADay() throws Exception {
        when(reconciliation.reconcile(DAY, false)).thenReturn(new ReconciliationRunView(DAY, 3, 1, 1, 1, 4));
        when(reconciliation.reconcile(DAY, true)).thenReturn(new ReconciliationRunView(DAY, 3, 3, 0, 0, 0));

        mockMvc.perform(post("/api/v1/platform/trade/reconciliation/runs").param("date", "2026-11-17")
                        .principal(signedIn("PLATFORM_OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$.date").value("2026-11-17"))
                .andExpect(jsonPath("$.merchants").value(3))
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.differences").value(4));
        mockMvc.perform(post("/api/v1/platform/trade/reconciliation/runs").param("date", "2026-11-17").param("redo", "true")
                        .principal(jwt(List.of("PLATFORM_OPERATOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(3));

        verify(reconciliation).reconcile(DAY, false);
        verify(reconciliation).reconcile(DAY, true);
    }

    // 今天及以后的日子服务拒绝（400）；没配置渠道 503。
    @Test
    void theServicesRefusalsOfARunAreMapped() throws Exception {
        when(reconciliation.reconcile(LocalDate.parse("2026-11-18"), false))
                .thenThrow(new IllegalArgumentException("bill date must be before today"));
        when(reconciliation.reconcile(DAY, false)).thenThrow(new TradeException(Error.CHANNEL_UNAVAILABLE));

        mockMvc.perform(post("/api/v1/platform/trade/reconciliation/runs").param("date", "2026-11-18")
                        .principal(signedIn("PLATFORM_OPERATOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(post("/api/v1/platform/trade/reconciliation/runs").param("date", "2026-11-17")
                        .principal(signedIn("PLATFORM_OPERATOR")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TRADE_CHANNEL_UNAVAILABLE"));
    }

    // S2-1 支付通道自检：子商户号原样交给服务（不给就是 null），结果原样返回；格式不对由服务拒绝（400）。
    @Test
    void aPlatformOperatorChecksThePaymentChannel() throws Exception {
        Instant at = Instant.parse("2026-11-22T02:00:00Z");
        when(channelCheck.run("1900000109")).thenReturn(new ChannelCheckView("WECHAT", false, at, List.of(
                new ChannelCheckItemView("SP_MCHID", "PASS", "服务商户号格式对"),
                new ChannelCheckItemView("QUERY_PAYMENT", "FAIL", "查单失败"))));
        when(channelCheck.run(null)).thenReturn(new ChannelCheckView("DISABLED", false, at, List.of()));
        when(channelCheck.run("12")).thenThrow(new IllegalArgumentException("subMchid must be 8-32 digits"));

        mockMvc.perform(post("/api/v1/platform/trade/channel-checks").param("subMchid", "1900000109")
                        .principal(signedIn("PLATFORM_OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.mode").value("WECHAT"))
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.checkedAt").value("2026-11-22T02:00:00Z"))
                .andExpect(jsonPath("$.items[1].code").value("QUERY_PAYMENT"))
                .andExpect(jsonPath("$.items[1].status").value("FAIL"));
        mockMvc.perform(post("/api/v1/platform/trade/channel-checks").principal(jwt(List.of("PLATFORM_OPERATOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("DISABLED"));
        mockMvc.perform(post("/api/v1/platform/trade/channel-checks").param("subMchid", "12")
                        .principal(signedIn("PLATFORM_OPERATOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "PROVIDER_OPERATOR", "PROVIDER_ADMIN", "PLATFORM_SUPPORT"})
    void theChannelCheckIsForPlatformOperatorsOnly(String role) throws Exception {
        for (TestingAuthenticationToken principal : List.of(signedIn(role), jwt(List.of(role)))) {
            mockMvc.perform(post("/api/v1/platform/trade/channel-checks").param("subMchid", "1900000109")
                            .principal(principal))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        }
        verifyNoInteractions(channelCheck);
    }

    private static MockHttpServletRequestBuilder register() {
        return put("/api/v1/platform/trade/merchant-accounts/{organizationId}", ORGANIZATION)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private static TestingAuthenticationToken signedIn(String role) {
        return new TestingAuthenticationToken(new CurrentSubject(OPERATOR, Set.of(role), null, Set.of(), 0), null);
    }

    private static TestingAuthenticationToken jwt(List<String> roles) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .claim("subjectId", OPERATOR.toString());
        if (roles != null) {
            builder.claim("roles", roles);
        }
        return new TestingAuthenticationToken(builder.build(), null);
    }
}
