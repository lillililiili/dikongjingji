package com.lowaltitude.inspection.identity.domain;

import java.util.Optional;
import java.util.UUID;

public interface IdentityProfilePort {

    /** Empty when the account does not exist or is not active. */
    Optional<IdentityProfile> findProfile(UUID subjectId);
}
