package com.lowaltitude.inspection.identity.application.account;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lowaltitude.inspection.identity.application.account.AccountException.Error;
import com.lowaltitude.inspection.identity.application.account.AccountViews.RevokedCountView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.SessionListView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.SessionView;
import com.lowaltitude.inspection.identity.domain.LoginDevice;
import com.lowaltitude.inspection.identity.domain.LoginSessionStatus;
import com.lowaltitude.inspection.identity.domain.RefreshTokenPort;
import com.lowaltitude.inspection.identity.domain.RefreshTokenRevokeReason;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper.SessionRow;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录会话（登录设备，S5-8）。每次登录记一行，它的 ID 就是这次登录的刷新令牌 family ID（身份 V008），访问令牌带它（sid）；
 * 续期沿用同一行并更新最近活动时间。同一台设备再登录时上一行作废。退出某台设备、换号、注销都撤销会话并撤销它的
 * 刷新令牌 family：访问令牌立刻失效（令牌校验看会话），也不能再续期。本机的退出走“退出登录”（/api/v1/auth/logout）。
 */
public class LoginSessionService {

    public static final int MAX_LISTED = 20;
    /** 登录设备只列这段时间里活动过（登录或续期）的会话；刷新令牌 30 天有效，过了就不算还登录着。 */
    public static final java.time.Duration LISTED_WINDOW = java.time.Duration.ofDays(30);
    public static final String PASSWORD = "PASSWORD";
    public static final String SMS_CODE = "SMS_CODE";

    private final AccountMapper mapper;
    private final AccountPorts.CityNames cities;
    private final RefreshTokenPort refreshTokens;
    private final Clock clock;

    public LoginSessionService(AccountMapper mapper, AccountPorts.CityNames cities, RefreshTokenPort refreshTokens,
                               Clock clock) {
        this.mapper = mapper;
        this.cities = cities;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    @Transactional
    public UUID open(UUID userId, LoginDevice device, String loginMethod) {
        LoginDevice normalized = (device == null ? LoginDevice.unknown() : device).normalized();
        OffsetDateTime now = now();
        if (normalized.deviceKey() != null) {
            List<UUID> replaced = mapper.findDeviceSessionIds(userId, normalized.deviceKey());
            mapper.replaceDeviceSessions(userId, normalized.deviceKey(), now);
            replaced.forEach(id -> refreshTokens.revokeFamily(id, RefreshTokenRevokeReason.LOGOUT, now.toInstant()));
        }
        return mapper.insertSession(userId, normalized.deviceKey(), normalized.deviceName(), normalized.platform(),
                normalized.cityCode(), SMS_CODE.equals(loginMethod) ? SMS_CODE : PASSWORD, now);
    }

    @Transactional(readOnly = true)
    public boolean isActive(UUID sessionId, UUID userId) {
        return sessionId != null && userId != null && mapper.isSessionActive(sessionId, userId);
    }

    /** 续期时：这次登录的会话还在（ACTIVE）、已退出（ENDED），或没有会话记录（UNKNOWN，会话记录之前的登录）。 */
    @Transactional(readOnly = true)
    public LoginSessionStatus status(UUID sessionId, UUID userId) {
        Boolean revoked = sessionId == null || userId == null ? null : mapper.findSessionRevoked(sessionId, userId);
        if (revoked == null) {
            return LoginSessionStatus.UNKNOWN;
        }
        return revoked ? LoginSessionStatus.ENDED : LoginSessionStatus.ACTIVE;
    }

    @Transactional
    public void touch(UUID sessionId, UUID userId) {
        mapper.touchSession(sessionId, userId, now());
    }

    @Transactional(readOnly = true)
    public SessionListView list(UUID userId, UUID currentSessionId) {
        List<SessionRow> rows = mapper.findActiveSessions(userId, now().minus(LISTED_WINDOW), MAX_LISTED);
        List<String> codes = rows.stream().map(SessionRow::cityCode).filter(java.util.Objects::nonNull)
                .distinct().toList();
        Map<String, String> names = codes.isEmpty() ? Map.of() : cities.names(codes);
        List<SessionView> items = new ArrayList<>();
        for (SessionRow row : rows) {
            items.add(new SessionView(row.id(), row.deviceName(), row.platform(),
                    row.cityCode() == null ? null : names.get(row.cityCode()),
                    row.signedInAt(), row.lastActiveAt(), row.id().equals(currentSessionId)));
        }
        // 本机在最前，其余按最近活动。
        items.sort(Comparator.comparing((SessionView view) -> !view.current()));
        return new SessionListView(List.copyOf(items));
    }

    /** 让另一台设备退出；本机不在这里退（SESSION_IS_CURRENT），不是自己的会话按不存在回答，已退出的直接成功。 */
    @Transactional
    public void revoke(UUID userId, UUID currentSessionId, UUID sessionId) {
        if (sessionId.equals(currentSessionId)) {
            throw new AccountException(Error.SESSION_IS_CURRENT);
        }
        if (mapper.findSession(sessionId, userId) == null) {
            throw new AccountException(Error.NOT_FOUND);
        }
        OffsetDateTime now = now();
        if (mapper.revokeSession(sessionId, userId, "REMOTE", now) > 0) {
            refreshTokens.revokeFamily(sessionId, RefreshTokenRevokeReason.LOGOUT, now.toInstant());
        }
    }

    @Transactional
    public RevokedCountView revokeOthers(UUID userId, UUID currentSessionId) {
        return new RevokedCountView(revokeAllExcept(userId, currentSessionId, "REMOTE"));
    }

    /** 本机退出登录，或这次登录的刷新令牌已被撤销（退出、盗用、账号变了）：会话跟着结束。 */
    @Transactional
    public void signOut(UUID sessionId, UUID userId) {
        if (sessionId != null && userId != null) {
            mapper.revokeSession(sessionId, userId, "SIGNED_OUT", now());
        }
    }

    /**
     * 退出这个账号除 keepSessionId 外的全部会话（为空时全部），并撤销它们的刷新令牌 family。
     * 远程退出（REMOTE）、换号（PHONE_CHANGED，保留本机）、注销（CLOSED，全部）；在调用方的事务里。
     */
    public int revokeAllExcept(UUID userId, UUID keepSessionId, String reason) {
        OffsetDateTime now = now();
        List<UUID> ending = mapper.findActiveSessionIds(userId, keepSessionId);
        int revoked = mapper.revokeOtherSessions(userId, keepSessionId, reason, now);
        RefreshTokenRevokeReason why = ("CLOSED".equals(reason) || "PASSWORD_CHANGED".equals(reason))
                ? RefreshTokenRevokeReason.ACCOUNT_CHANGED : RefreshTokenRevokeReason.LOGOUT;
        ending.forEach(id -> refreshTokens.revokeFamily(id, why, now.toInstant()));
        return revoked;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }
}
