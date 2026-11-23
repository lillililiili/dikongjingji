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
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper.SessionRow;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录会话（登录设备，S5-8）。每次登录记一行，令牌带它的 ID（sid）；同一台设备再登录时上一行作废。
 * 退出某台设备即撤销那一行，令牌校验时发现会话已撤销就当作未登录。本机的退出走“退出登录”，不在设备列表里退。
 */
public class LoginSessionService {

    public static final int MAX_LISTED = 20;
    public static final String PASSWORD = "PASSWORD";
    public static final String SMS_CODE = "SMS_CODE";

    private final AccountMapper mapper;
    private final AccountPorts.CityNames cities;
    private final Clock clock;

    public LoginSessionService(AccountMapper mapper, AccountPorts.CityNames cities, Clock clock) {
        this.mapper = mapper;
        this.cities = cities;
        this.clock = clock;
    }

    @Transactional
    public UUID open(UUID userId, LoginDevice device, String loginMethod) {
        LoginDevice normalized = (device == null ? LoginDevice.unknown() : device).normalized();
        OffsetDateTime now = now();
        if (normalized.deviceKey() != null) {
            mapper.replaceDeviceSessions(userId, normalized.deviceKey(), now);
        }
        return mapper.insertSession(userId, normalized.deviceKey(), normalized.deviceName(), normalized.platform(),
                normalized.cityCode(), SMS_CODE.equals(loginMethod) ? SMS_CODE : PASSWORD, now);
    }

    @Transactional(readOnly = true)
    public boolean isActive(UUID sessionId, UUID userId) {
        return sessionId != null && userId != null && mapper.isSessionActive(sessionId, userId);
    }

    @Transactional(readOnly = true)
    public SessionListView list(UUID userId, UUID currentSessionId) {
        List<SessionRow> rows = mapper.findActiveSessions(userId, MAX_LISTED);
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
        mapper.revokeSession(sessionId, userId, "REMOTE", now());
    }

    @Transactional
    public RevokedCountView revokeOthers(UUID userId, UUID currentSessionId) {
        return new RevokedCountView(mapper.revokeOtherSessions(userId, currentSessionId, "REMOTE", now()));
    }

    @Transactional
    public void signOut(UUID sessionId, UUID userId) {
        if (sessionId != null && userId != null) {
            mapper.revokeSession(sessionId, userId, "SIGNED_OUT", now());
        }
    }

    /** 换号（PHONE_CHANGED，保留本机）或注销（CLOSED，全部）时调用；在调用方的事务里。 */
    public int revokeAllExcept(UUID userId, UUID keepSessionId, String reason) {
        return mapper.revokeOtherSessions(userId, keepSessionId, reason, now());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }
}
