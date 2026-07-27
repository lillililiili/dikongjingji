package com.lowaltitude.inspection.bootstrap.security;

/** Response DTO for a successfully issued access token. */
public record LoginResponse(String accessToken, String tokenType, long expiresInSeconds) {
}
