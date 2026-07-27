package com.lowaltitude.inspection.bootstrap.security;

import java.time.Instant;
import java.util.Optional;

import com.lowaltitude.inspection.identity.domain.IdentityAccount;
import com.lowaltitude.inspection.identity.domain.IdentityAccountPort;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

@Service
public class AuthenticationService {

    private static final long TOKEN_TTL_SECONDS = 900;

    private final IdentityAccountPort identityAccountPort;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;

    public AuthenticationService(
            IdentityAccountPort identityAccountPort,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder
    ) {
        this.identityAccountPort = identityAccountPort;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
    }

    public Optional<LoginToken> authenticate(String loginName, String password) {
        return identityAccountPort.findByLoginName(loginName)
                .filter(account -> passwordEncoder.matches(password, account.passwordHash()))
                .map(this::issueToken);
    }

    private LoginToken issueToken(IdentityAccount account) {
        Instant issuedAt = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(account.subjectId().toString())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(TOKEN_TTL_SECONDS))
                .claim("subjectId", account.subjectId().toString())
                .claim("roles", account.roles())
                .claim("organizationId", account.organizationId().toString())
                .claim("permissions", account.permissions())
                .claim("tokenVersion", account.tokenVersion())
                .build();
        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims
        )).getTokenValue();
        return new LoginToken(accessToken, TOKEN_TTL_SECONDS);
    }

    public record LoginToken(String accessToken, long expiresInSeconds) {
    }
}
