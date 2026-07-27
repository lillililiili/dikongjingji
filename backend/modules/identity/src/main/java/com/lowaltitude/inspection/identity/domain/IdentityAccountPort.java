package com.lowaltitude.inspection.identity.domain;

import java.util.Optional;

public interface IdentityAccountPort {

    Optional<IdentityAccount> findByLoginName(String loginName);
}
