package com.lowaltitude.inspection.bootstrap.security;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.AccountStatePort;
import com.lowaltitude.inspection.identity.domain.CurrentIdentity;
import com.lowaltitude.inspection.identity.domain.IdentityAccount;
import com.lowaltitude.inspection.identity.domain.IdentityAccountPort;
import com.lowaltitude.inspection.identity.domain.IdentityAccountStatus;
import com.lowaltitude.inspection.identity.domain.LoginDevice;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

/**
 * 签发访问令牌。账号密码登录、验证码登录（S5-8）和切换身份（S5-7）都走这里的同一套签发：
 * 声明、有效期和签名不变，只多两个声明——sid（登录会话，退出那台设备后令牌失效）和 identity（当前企业，个人时没有）。
 * 没有数据库时（测试）会话开不出来，令牌就不带 sid，与以前完全一样。
 */
@Service
public class AuthenticationService {

    private static final long TOKEN_TTL_SECONDS = 900;

    private final IdentityAccountPort identityAccountPort;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final AccountStatePort accountState;

    public AuthenticationService(
            IdentityAccountPort identityAccountPort,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder,
            AccountStatePort accountState
    ) {
        this.identityAccountPort = identityAccountPort;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.accountState = accountState;
    }

    public Optional<LoginToken> authenticate(String loginName, String password) {
        return authenticate(loginName, password, LoginDevice.unknown());
    }

    public Optional<LoginToken> authenticate(String loginName, String password, LoginDevice device) {
        return identityAccountPort.findByLoginName(loginName)
                .filter(account -> account.status() == IdentityAccountStatus.ACTIVE)
                .filter(account -> passwordEncoder.matches(password, account.passwordHash()))
                .map(account -> issueToken(account, openSession(account, device, "PASSWORD")));
    }

    /** 验证码登录（账号已由身份模块核对验证码后给出）：账号要是有效的，开一个新会话。 */
    public Optional<LoginToken> issueForVerifiedAccount(UUID subjectId, LoginDevice device) {
        return identityAccountPort.findBySubjectId(subjectId)
                .filter(account -> account.status() == IdentityAccountStatus.ACTIVE)
                .map(account -> issueToken(account, openSession(account, device, "SMS_CODE")));
    }

    /** 切换身份后重新签发：沿用同一个会话（同一台设备），会话已退出时不签发。 */
    public Optional<LoginToken> reissue(UUID subjectId, UUID sessionId) {
        if (sessionId != null && !accountState.isSessionActive(sessionId, subjectId)) {
            return Optional.empty();
        }
        return identityAccountPort.findBySubjectId(subjectId)
                .filter(account -> account.status() == IdentityAccountStatus.ACTIVE)
                .map(account -> issueToken(account, sessionId));
    }

    private UUID openSession(IdentityAccount account, LoginDevice device, String method) {
        return accountState.openSession(account.subjectId(), device, method).orElse(null);
    }

    private LoginToken issueToken(IdentityAccount account, UUID sessionId) {
        Instant issuedAt = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .subject(account.subjectId().toString())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(TOKEN_TTL_SECONDS))
                .claim("subjectId", account.subjectId().toString())
                .claim("roles", account.roles())
                .claim("organizationId", account.organizationId().toString())
                .claim("permissions", account.permissions())
                .claim("tokenVersion", account.tokenVersion());
        if (sessionId != null) {
            claims.claim("sid", sessionId.toString());
        }
        CurrentIdentity identity = accountState.currentIdentity(account.subjectId());
        if (identity.isEnterprise()) {
            claims.claim("identity", identity.enterpriseId().toString());
        }
        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()
        )).getTokenValue();
        return new LoginToken(accessToken, TOKEN_TTL_SECONDS);
    }

    public record LoginToken(String accessToken, long expiresInSeconds) {
    }
}
