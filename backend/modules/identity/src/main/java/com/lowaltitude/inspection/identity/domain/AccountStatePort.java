package com.lowaltitude.inspection.identity.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * 登录和令牌校验要用的账号状态（S5-7、S5-8）：登录会话（令牌里的 sid）、当前身份（令牌里的 identity）、打码手机号。
 * 没有数据库时由 FailClosedAccountStatePort 顶上：不开会话（令牌不带 sid），带 sid 的令牌一律不认。
 */
public interface AccountStatePort {

    /** 记一次登录，返回会话 ID；记不了（没有数据库）时为空，令牌就不带 sid。 */
    Optional<UUID> openSession(UUID subjectId, LoginDevice device, String loginMethod);

    /** 这个会话还有效（属于这个账号、没有退出）。 */
    boolean isSessionActive(UUID sessionId, UUID subjectId);

    /** 退出这一个会话（本机退出登录）。已经退出的不变。 */
    void signOut(UUID sessionId, UUID subjectId);

    /** 当前身份；企业已解散或本人已不在其中时回到个人。 */
    CurrentIdentity currentIdentity(UUID subjectId);

    /** 打码手机号（138****2026）；没有绑定时为空。 */
    Optional<String> maskedPhone(UUID subjectId);
}
