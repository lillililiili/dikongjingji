package com.lowaltitude.inspection.identity.domain;

import java.util.Optional;
import java.util.UUID;

public interface IdentityAccountPort {

    Optional<IdentityAccount> findByLoginName(String loginName);

    /**
     * The account behind a subject, with its current roles, permissions and token version, for
     * issuing a fresh access token when a login is renewed. Fails closed unless an adapter knows it.
     * Also used by the S5-8 code login and by switching identity (S5-7).
     */
    default Optional<IdentityAccount> findBySubjectId(UUID subjectId) {
        return Optional.empty();
    }
}
