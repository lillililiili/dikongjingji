package com.lowaltitude.inspection.identity.application.account;

import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.AccountStatePort;
import com.lowaltitude.inspection.identity.domain.CurrentIdentity;
import com.lowaltitude.inspection.identity.domain.LoginDevice;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper.UserRow;

/** 登录和令牌校验用的账号状态，接到会话和企业服务上。 */
public class IdentityAccountState implements AccountStatePort {

    private final LoginSessionService sessions;
    private final EnterpriseService enterprises;
    private final AccountMapper mapper;

    public IdentityAccountState(LoginSessionService sessions, EnterpriseService enterprises, AccountMapper mapper) {
        this.sessions = sessions;
        this.enterprises = enterprises;
        this.mapper = mapper;
    }

    @Override
    public Optional<UUID> openSession(UUID subjectId, LoginDevice device, String loginMethod) {
        return Optional.of(sessions.open(subjectId, device, loginMethod));
    }

    @Override
    public boolean isSessionActive(UUID sessionId, UUID subjectId) {
        return sessions.isActive(sessionId, subjectId);
    }

    @Override
    public void signOut(UUID sessionId, UUID subjectId) {
        sessions.signOut(sessionId, subjectId);
    }

    @Override
    public CurrentIdentity currentIdentity(UUID subjectId) {
        return enterprises.currentIdentity(subjectId);
    }

    @Override
    public Optional<String> maskedPhone(UUID subjectId) {
        UserRow user = subjectId == null ? null : mapper.findUser(subjectId);
        return Optional.ofNullable(user == null ? null : PhoneNumbers.mask(user.mobile()));
    }
}
