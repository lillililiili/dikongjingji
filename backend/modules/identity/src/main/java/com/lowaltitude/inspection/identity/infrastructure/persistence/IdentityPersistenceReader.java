package com.lowaltitude.inspection.identity.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IdentityPersistenceReader {

    Optional<PersistedIdentityUser> findUserByLoginName(String loginName);

    Optional<PersistedIdentityUser> findUserById(UUID subjectId);

    List<PersistedRolePermission> findRolePermissions(UUID subjectId);
}
