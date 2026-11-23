package com.lowaltitude.inspection.identity.infrastructure;

import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.AccountStatePort;
import com.lowaltitude.inspection.identity.domain.CurrentIdentity;
import com.lowaltitude.inspection.identity.domain.LoginDevice;

/** 没有数据库时的账号状态：不开会话，带会话 ID 的令牌一律不认，身份总是个人，没有手机号。 */
public final class FailClosedAccountStatePort implements AccountStatePort {

    @Override
    public Optional<UUID> openSession(UUID subjectId, LoginDevice device, String loginMethod) {
        return Optional.empty();
    }

    @Override
    public boolean isSessionActive(UUID sessionId, UUID subjectId) {
        return false;
    }

    @Override
    public void signOut(UUID sessionId, UUID subjectId) {
    }

    @Override
    public CurrentIdentity currentIdentity(UUID subjectId) {
        return CurrentIdentity.personal();
    }

    @Override
    public Optional<String> maskedPhone(UUID subjectId) {
        return Optional.empty();
    }
}
