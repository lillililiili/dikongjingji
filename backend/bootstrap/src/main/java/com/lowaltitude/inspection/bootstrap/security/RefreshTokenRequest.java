package com.lowaltitude.inspection.bootstrap.security;

/** Body of POST /api/v1/auth/refresh and /api/v1/auth/logout. */
public record RefreshTokenRequest(String refreshToken) {
}
