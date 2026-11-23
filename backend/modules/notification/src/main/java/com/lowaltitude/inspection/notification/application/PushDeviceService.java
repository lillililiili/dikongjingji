package com.lowaltitude.inspection.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * App 上报的设备推送标识（S5-9）。推送服务商未定，标识只校验长度和字符（模拟服务接受任意标识）。
 * 同一台设备换账号登录，归到最后上报的账号；退出登录时 App 撤销，撤销只对自己名下的设备有效。
 */
public class PushDeviceService {

    static final String CLIENT_ID_PATTERN = "^[\\x21-\\x7E]{1,200}$";
    static final String CITY_CODE_PATTERN = "^[A-Z0-9]{4,16}$";

    private final PushStore store;
    private final Clock clock;

    PushDeviceService(PushStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public PushDeviceView register(UUID accountId, String platform, String pushClientId, String cityCode) {
        requireAccount(accountId);
        PushGateway.PushPlatform parsed = platform(platform);
        String clientId = clientId(pushClientId);
        if (cityCode != null && !cityCode.matches(CITY_CODE_PATTERN)) {
            throw new IllegalArgumentException("city code is invalid");
        }
        Instant now = clock.instant();
        UUID id = store.registerDevice(accountId, parsed.name(), clientId, cityCode, now);
        return new PushDeviceView(id, parsed, now);
    }

    /** 撤销这个账号名下的这台设备；不是自己的或已经撤过也当作成功（返回 false）。 */
    public boolean unregister(UUID accountId, String platform, String pushClientId) {
        requireAccount(accountId);
        return store.revokeDevice(accountId, platform(platform).name(), clientId(pushClientId), clock.instant()) > 0;
    }

    private static PushGateway.PushPlatform platform(String value) {
        try {
            return PushGateway.PushPlatform.valueOf(value == null ? "" : value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("platform is invalid", unknown);
        }
    }

    private static String clientId(String value) {
        if (value == null || !value.matches(CLIENT_ID_PATTERN)) {
            throw new IllegalArgumentException("push client id is invalid");
        }
        return value;
    }

    private static void requireAccount(UUID accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("account is required");
        }
    }

    public record PushDeviceView(UUID deviceId, PushGateway.PushPlatform platform, Instant registeredAt) {
    }
}
