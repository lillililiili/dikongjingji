package com.lowaltitude.inspection.identity.domain;

/** Why a login (a refresh token family) was withdrawn. */
public enum RefreshTokenRevokeReason {
    /** The user signed out. */
    LOGOUT,
    /** A token already exchanged was presented again: someone else may hold a copy. */
    REUSE_DETECTED,
    /** The account was disabled, left its organization or had its token version raised. */
    ACCOUNT_CHANGED
}
