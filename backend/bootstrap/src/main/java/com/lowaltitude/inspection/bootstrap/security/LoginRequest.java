package com.lowaltitude.inspection.bootstrap.security;

/** Request DTO for the anonymous login endpoint. */
public record LoginRequest(String loginName, String password) {
}
