package com.lowaltitude.inspection.identity.domain;

import java.util.OptionalInt;
import java.util.UUID;

public interface TokenVersionPort {

    OptionalInt findCurrentTokenVersion(UUID subjectId);
}
