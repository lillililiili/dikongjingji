package com.lowaltitude.inspection.identity.domain;

import java.util.UUID;

public record ResourceOwnership(UUID ownerSubjectId, UUID organizationId) {
}
