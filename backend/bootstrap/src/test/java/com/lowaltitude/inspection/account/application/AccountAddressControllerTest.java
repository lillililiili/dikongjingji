package com.lowaltitude.inspection.account.application;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 控制器只做三件事：从令牌里取客户账号、把请求转成服务的参数、把结果和业务拒绝转成契约里的状态码。
 * 没带令牌时的 401 由安全过滤器给出，在 AuthenticationSecurityIntegrationTest 里检查；这里的 standaloneSetup 不带过滤器。
 */
class AccountAddressControllerTest {

    private static final UUID SUBJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SOMEONE_ELSE = UUID.fromString("11111111-1111-1111-1111-222222222222");
    private static final UUID ADDRESS = UUID.fromString("d1000000-0000-4000-8000-000000000001");
    private static final String BODY = """
            {"recipientName":"王女士","phone":"13800138000","cityCode":"370500",
             "detail":"东营区示例路 18 号","isDefault":true}
            """;

    private final AccountAddressService service = mock(AccountAddressService.class);
    private final TestingAuthenticationToken customer = signedIn(SUBJECT, "CUSTOMER");
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AccountAddressController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ---- 谁能调 ----

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    void everyRouteRefusesAnAccountThatIsNotACustomer(String route, MockHttpServletRequestBuilder request)
            throws Exception {
        mockMvc.perform(request.principal(signedIn(SUBJECT, "PROVIDER_OPERATOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));

        verifyNoInteractions(service);
    }

    // 过滤器之后如果没有登录主体（正常不会发生），控制器自己也不放行。
    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    void noRouteWorksWithoutASignedInSubject(String route, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));

        verifyNoInteractions(service);
    }

    // 账号只来自令牌：请求里再写一个别人的账号也没有用。
    @Test
    void theOwnerComesFromTheTokenAndFromNowhereElse() throws Exception {
        when(service.list(SUBJECT)).thenReturn(new AddressListView(List.of()));
        when(service.create(SUBJECT, form(true))).thenReturn(address(true));

        mockMvc.perform(get("/api/v1/account/addresses")
                        .principal(customer)
                        .param("ownerId", SOMEONE_ELSE.toString()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/account/addresses")
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("}", ",\"ownerId\":\"" + SOMEONE_ELSE + "\"}")))
                .andExpect(status().isCreated());

        verify(service).list(SUBJECT);
        verify(service).create(SUBJECT, form(true));
    }

    // 生产里登录主体是 JWT：账号和角色从它的声明里取。
    @Test
    void aJwtPrincipalGivesTheSameAccountAndAJwtWithoutTheCustomerRoleIsRefused() throws Exception {
        when(service.list(SUBJECT)).thenReturn(new AddressListView(List.of()));

        mockMvc.perform(get("/api/v1/account/addresses").principal(jwt(SUBJECT.toString(), List.of("CUSTOMER"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/account/addresses").principal(jwt(SUBJECT.toString(), null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        mockMvc.perform(get("/api/v1/account/addresses")
                        .principal(jwt(SUBJECT.toString(), List.of("PROVIDER_ADMIN"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/account/addresses").principal(jwt("not-a-uuid", List.of("CUSTOMER"))))
                .andExpect(status().isForbidden());

        verify(service).list(SUBJECT);
    }

    // ---- 每个接口 ----

    @Test
    void listReturnsTheAddressesAsTheContractDescribes() throws Exception {
        when(service.list(SUBJECT)).thenReturn(new AddressListView(List.of(address(true))));

        mockMvc.perform(get("/api/v1/account/addresses").principal(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$.items[0].length()").value(6))
                .andExpect(jsonPath("$.items[0].addressId").value(ADDRESS.toString()))
                .andExpect(jsonPath("$.items[0].recipientName").value("王女士"))
                .andExpect(jsonPath("$.items[0].phone").value("13800138000"))
                .andExpect(jsonPath("$.items[0].region.provinceCode").value("370000"))
                .andExpect(jsonPath("$.items[0].region.provinceName").value("山东省"))
                .andExpect(jsonPath("$.items[0].region.cityCode").value("370500"))
                .andExpect(jsonPath("$.items[0].region.cityName").value("东营市"))
                .andExpect(jsonPath("$.items[0].detail").value("东营区示例路 18 号"))
                .andExpect(jsonPath("$.items[0].isDefault").value(true));
    }

    @Test
    void createAnswersCreatedWithTheNewAddress() throws Exception {
        when(service.create(SUBJECT, form(true))).thenReturn(address(true));

        mockMvc.perform(post("/api/v1/account/addresses")
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.addressId").value(ADDRESS.toString()))
                .andExpect(jsonPath("$.isDefault").value(true));
    }

    @Test
    void aMissingIsDefaultReachesTheServiceAsNull() throws Exception {
        AddressForm withoutDefault = new AddressForm("王女士", "13800138000", "370500", "东营区示例路 18 号", null);
        when(service.create(SUBJECT, withoutDefault)).thenReturn(address(false));

        mockMvc.perform(post("/api/v1/account/addresses")
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace(",\"isDefault\":true", "")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isDefault").value(false));

        verify(service).create(SUBJECT, withoutDefault);
    }

    @Test
    void updateUsesTheAddressOfThePath() throws Exception {
        when(service.update(SUBJECT, ADDRESS, form(true))).thenReturn(address(true));

        mockMvc.perform(put("/api/v1/account/addresses/{addressId}", ADDRESS)
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addressId").value(ADDRESS.toString()));

        verify(service).update(SUBJECT, ADDRESS, form(true));
    }

    @Test
    void deleteAnswersNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/account/addresses/{addressId}", ADDRESS).principal(customer))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(service).delete(SUBJECT, ADDRESS);
    }

    // ---- 拒绝与参数错误 ----

    @Test
    void anAddressThatIsNotTheAccountsIsNotFound() throws Exception {
        when(service.update(SUBJECT, ADDRESS, form(true))).thenThrow(new AddressNotFoundException());
        org.mockito.Mockito.doThrow(new AddressNotFoundException()).when(service).delete(SUBJECT, ADDRESS);

        mockMvc.perform(put("/api/v1/account/addresses/{addressId}", ADDRESS)
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
        mockMvc.perform(delete("/api/v1/account/addresses/{addressId}", ADDRESS)
                        .principal(customer)
                        .header("X-Request-Id", "trace-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").value("trace-1"));
    }

    @Test
    void theTwentyFirstAddressIsAConflictWithItsOwnCode() throws Exception {
        when(service.create(SUBJECT, form(true))).thenThrow(new AddressLimitException());

        mockMvc.perform(post("/api/v1/account/addresses")
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY)
                        .header("X-Request-Id", "trace-2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_ADDRESS_LIMIT"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.traceId").value("trace-2"))
                .andExpect(jsonPath("$.details").value(nullValue()));
    }

    @Test
    void invalidFieldsMalformedIdsAndBodiesAreBadRequests() throws Exception {
        when(service.create(SUBJECT, form(true))).thenThrow(new IllegalArgumentException("phone is invalid"));

        mockMvc.perform(post("/api/v1/account/addresses")
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(post("/api/v1/account/addresses")
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientName\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(post("/api/v1/account/addresses")
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(put("/api/v1/account/addresses/not-a-uuid")
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(delete("/api/v1/account/addresses/not-a-uuid").principal(customer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }

    // ---- 辅助 ----

    private static Stream<Arguments> routes() {
        String address = "/api/v1/account/addresses/" + ADDRESS;
        return Stream.of(
                Arguments.of("GET addresses", get("/api/v1/account/addresses")),
                Arguments.of("POST addresses", json(post("/api/v1/account/addresses"))),
                Arguments.of("PUT address", json(put(address))),
                Arguments.of("DELETE address", delete(address)));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request) {
        return request.contentType(MediaType.APPLICATION_JSON).content(BODY);
    }

    private static AddressForm form(Boolean isDefault) {
        return new AddressForm("王女士", "13800138000", "370500", "东营区示例路 18 号", isDefault);
    }

    private static AddressView address(boolean isDefault) {
        return new AddressView(
                ADDRESS,
                "王女士",
                "13800138000",
                new AddressRegionView("370000", "山东省", "370500", "东营市"),
                "东营区示例路 18 号",
                isDefault);
    }

    private static TestingAuthenticationToken signedIn(UUID subjectId, String role) {
        return new TestingAuthenticationToken(
                new CurrentSubject(subjectId, Set.of(role), null, Set.of(), 0), null);
    }

    private static TestingAuthenticationToken jwt(String subjectId, List<String> roles) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .claim("subjectId", subjectId);
        if (roles != null) {
            builder.claim("roles", roles);
        }
        return new TestingAuthenticationToken(builder.build(), null);
    }
}
