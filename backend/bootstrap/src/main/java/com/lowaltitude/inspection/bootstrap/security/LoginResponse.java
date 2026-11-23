package com.lowaltitude.inspection.bootstrap.security;

/** Response DTO for a login or a renewal: the access token and the refresh token that renews it. */
public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        String refreshToken,
        long refreshExpiresInSeconds
) {
}
