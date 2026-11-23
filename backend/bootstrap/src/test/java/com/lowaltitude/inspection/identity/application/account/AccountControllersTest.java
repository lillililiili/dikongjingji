package com.lowaltitude.inspection.identity.application.account;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.bootstrap.security.AuthenticationService;
import com.lowaltitude.inspection.bootstrap.security.PhoneAuthenticationController;
import com.lowaltitude.inspection.identity.domain.AccountStatePort;
import com.lowaltitude.inspection.identity.domain.CurrentIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 账号、企业和验证码登录的控制器：账号和会话只来自令牌；企业接口只给客户；切换身份沿用本机会话重新签发；
 * 业务拒绝按契约的状态码回答。没带令牌的 401 由安全过滤器给出（AuthenticationSecurityIntegrationTest）。
 */
class AccountControllersTest {

    private static final UUID ME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SID = UUID.fromString("51111111-1111-1111-1111-111111111111");
    private static final UUID ENT = UUID.fromString("e5000000-0000-4000-8000-000000000001");

    private final PhoneAccountService phones = mock(PhoneAccountService.class);
    private final LoginSessionService sessions = mock(LoginSessionService.class);
    private final AccountClosureService closures = mock(AccountClosureService.class);
    private final PersonalDataExportService exports = mock(PersonalDataExportService.class);
    private final EnterpriseService enterprises = mock(EnterpriseService.class);
    private final AuthenticationService tokens = mock(AuthenticationService.class);
    private final AccountStatePort state = mock(AccountStatePort.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                        new AccountController(phones, sessions, closures, exports),
                        new EnterpriseController(enterprises, tokens),
                        new PhoneAuthenticationController(phones, tokens))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static TestingAuthenticationToken jwt(String role) {
        return new TestingAuthenticationToken(Jwt.withTokenValue("t").header("alg", "HS256")
                .claim("subjectId", ME.toString()).claim("sid", SID.toString()).claim("roles", List.of(role))
                .build(), null);
    }

    @Test
    void sessionsUseTheSubjectAndSessionOfTheToken() throws Exception {
        when(sessions.list(ME, SID)).thenReturn(new AccountViews.SessionListView(List.of()));
        mvc.perform(get("/api/v1/account/sessions").principal(jwt("PROVIDER_OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());

        UUID other = UUID.randomUUID();
        mvc.perform(delete("/api/v1/account/sessions/" + other).principal(jwt("CUSTOMER")))
                .andExpect(status().isNoContent());
        verify(sessions).revoke(ME, SID, other);
    }

    @Test
    void enterprisesAreForCustomersOnly() throws Exception {
        mvc.perform(get("/api/v1/account/enterprises").principal(jwt("PROVIDER_OPERATOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        mvc.perform(put("/api/v1/account/identity").principal(jwt("PLATFORM_SUPPORT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enterpriseId\":null}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(enterprises, tokens);
    }

    @Test
    void creatingNeedsAnIdempotencyKey() throws Exception {
        mvc.perform(post("/api/v1/account/enterprises").principal(jwt("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"东营港务\",\"creditCode\":\"91370500EXAMPLE26X\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(enterprises);
    }

    @Test
    void businessRefusalsUseTheContractStatuses() throws Exception {
        org.mockito.Mockito.doThrow(new AccountException(AccountException.Error.ENTERPRISE_ADMIN_REQUIRED))
                .when(enterprises).remove(ME, ENT, ME);
        mvc.perform(delete("/api/v1/account/enterprises/" + ENT + "/members/" + ME).principal(jwt("CUSTOMER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ENTERPRISE_ADMIN_REQUIRED"));

        when(phones.sendLoginCode("13900001111")).thenThrow(new AccountException(AccountException.Error.SMS_TOO_FREQUENT));
        mvc.perform(post("/api/v1/auth/sms-codes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13900001111\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("SMS_TOO_FREQUENT"));
    }

    @Test
    void switchingReissuesOnTheSameSession() throws Exception {
        when(enterprises.switchIdentity(ME, ENT)).thenReturn(
                new CurrentIdentity(CurrentIdentity.ENTERPRISE, ENT, "东营港务物流", "MEMBER"));
        when(tokens.reissue(ME, SID)).thenReturn(Optional.of(new AuthenticationService.LoginToken("new-token", 900, null, 0)));

        mvc.perform(put("/api/v1/account/identity").principal(jwt("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enterpriseId\":\"" + ENT + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-token"))
                .andExpect(jsonPath("$.identity.enterpriseName").value("东营港务物流"));

        when(tokens.reissue(ME, SID)).thenReturn(Optional.empty());
        mvc.perform(put("/api/v1/account/identity").principal(jwt("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enterpriseId\":null}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void phoneLoginIssuesATokenOnlyForAnActiveAccount() throws Exception {
        when(phones.login("13900001111", "123456")).thenReturn(new PhoneAccountService.PhoneLogin(ME, true));
        when(tokens.issueForVerifiedAccount(any(), any())).thenReturn(
                Optional.of(new AuthenticationService.LoginToken("token", 900, "refresh", 2_592_000)));
        mvc.perform(post("/api/v1/auth/phone-login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13900001111\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registered").value(true))
                .andExpect(jsonPath("$.refreshToken").value("refresh"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"));

        when(tokens.issueForVerifiedAccount(any(), any())).thenReturn(Optional.empty());
        mvc.perform(post("/api/v1/auth/phone-login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13900001111\",\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    @Test
    void theExportFileIsAZipThatIsNotCached() throws Exception {
        UUID export = UUID.randomUUID();
        when(exports.download(export, "t".repeat(43))).thenReturn(new AccountViews.ExportFile("personal-data.zip", new byte[] {'P', 'K'}));
        mvc.perform(get("/api/v1/account/data-exports/" + export + "/file").param("token", "t".repeat(43)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Cache-Control", "no-store"));

        when(exports.download(export, "x".repeat(43))).thenThrow(new AccountException(AccountException.Error.NOT_FOUND));
        mvc.perform(get("/api/v1/account/data-exports/" + export + "/file").param("token", "x".repeat(43)))
                .andExpect(status().isNotFound());
    }
}
