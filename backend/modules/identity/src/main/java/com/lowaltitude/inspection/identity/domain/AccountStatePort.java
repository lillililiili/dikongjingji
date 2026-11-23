package com.lowaltitude.inspection.identity.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * 登录和令牌校验要用的账号状态（S5-7、S5-8）：登录会话（令牌里的 sid）、当前身份（令牌里的 identity）、打码手机号。
 * 登录会话的 ID 就是这次登录的刷新令牌 family ID（身份 V008）：一台设备一次登录，续期沿用同一个会话；
 * 退出这台设备即撤销这个会话和它的 family。
 * 没有数据库时由 FailClosedAccountStatePort 顶上：不开会话（令牌不带 sid），带 sid 的令牌一律不认。
 */
public interface AccountStatePort {

    /** 记一次登录，返回会话 ID；记不了（没有数据库）时为空，令牌就不带 sid。 */
    Optional<UUID> openSession(UUID subjectId, LoginDevice device, String loginMethod);

    /** 这个会话还有效（属于这个账号、没有退出）。 */
    boolean isSessionActive(UUID sessionId, UUID subjectId);

    /** 退出这一个会话（本机退出登录，或这次登录的刷新令牌被撤销）。已经退出的不变。 */
    void signOut(UUID sessionId, UUID subjectId);

    /** 续期时问：这次登录（family）背后的会话是否还在。 */
    LoginSessionStatus sessionStatus(UUID sessionId, UUID subjectId);

    /** 续期成功：记下这台设备最近活动的时间（登录设备按它排序、30 天没活动的不再列出）。 */
    void touchSession(UUID sessionId, UUID subjectId);

    /** 当前身份；企业已解散或本人已不在其中时回到个人。 */
    CurrentIdentity currentIdentity(UUID subjectId);

    /** 打码手机号（138****2026）；没有绑定时为空。 */
    Optional<String> maskedPhone(UUID subjectId);
}
