package com.lowaltitude.inspection.identity.domain;

/** A refresh token is usable once: ACTIVE until it is exchanged (ROTATED) or withdrawn (REVOKED). */
public enum RefreshTokenStatus {
    ACTIVE,
    ROTATED,
    REVOKED
}
