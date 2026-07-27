package com.lowaltitude.inspection.bootstrap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.IdentityAccount;
import com.lowaltitude.inspection.identity.domain.IdentityAccountPort;
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
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import({
        AuthenticationSecurityIntegrationTest.SecurityProbeConfiguration.class,
        AuthenticationSecurityIntegrationTest.TestIdentityConfiguration.class
})
class AuthenticationSecurityIntegrationTest {

    private static final String HMAC_SECRET = "test-only-hmac-secret-that-is-at-least-thirty-two-bytes";
    private static final UUID SUBJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORGANIZATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

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
    void loginRejectsInvalidPasswordWithApiError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"test-user\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
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
    void malformedTokenIsRejectedWithApiError() throws Exception {
        mockMvc.perform(get("/test/security/authenticated")
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
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
                        .header("Authorization", "Bearer " + signedToken(List.of("inspection.reservation.create"), 7)))
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

    private String signedToken(List<String> permissions, int tokenVersion) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(SUBJECT_ID.toString())
                .claim("subjectId", SUBJECT_ID.toString())
                .claim("roles", List.of("CUSTOMER"))
                .claim("organizationId", ORGANIZATION_ID.toString())
                .claim("permissions", permissions)
                .claim("tokenVersion", tokenVersion)
                .issueTime(java.util.Date.from(Instant.now()))
                .expirationTime(java.util.Date.from(Instant.now().plusSeconds(300)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(HMAC_SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
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
            IdentityAccount account = new IdentityAccount(
                    SUBJECT_ID,
                    "test-user",
                    passwordEncoder.encode("test-password"),
                    java.util.Set.of("CUSTOMER"),
                    ORGANIZATION_ID,
                    java.util.Set.of("inspection.reservation.create"),
                    7
            );
            return loginName -> "test-user".equals(loginName)
                    ? java.util.Optional.of(account)
                    : java.util.Optional.empty();
        }

        @Bean
        @Primary
        TokenVersionPort testTokenVersionPort() {
            return subjectId -> SUBJECT_ID.equals(subjectId)
                    ? java.util.OptionalInt.of(7)
                    : java.util.OptionalInt.empty();
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
