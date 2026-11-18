package com.lowaltitude.inspection.identity.infrastructure;

import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.IdentityProfile;
import com.lowaltitude.inspection.identity.domain.IdentityProfilePort;

public final class FailClosedIdentityProfilePort implements IdentityProfilePort {

    @Override
    public Optional<IdentityProfile> findProfile(UUID subjectId) {
        return Optional.empty();
    }
}
