package com.lowaltitude.inspection.bootstrap.security;

import java.util.OptionalInt;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.TokenVersionPort;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

final class TokenVersionJwtValidator implements OAuth2TokenValidator<Jwt> {

    private final TokenVersionPort tokenVersionPort;

    TokenVersionJwtValidator(TokenVersionPort tokenVersionPort) {
        this.tokenVersionPort = tokenVersionPort;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        try {
            UUID subjectId = UUID.fromString(token.getSubject());
            Number tokenVersion = token.getClaim("tokenVersion");
            OptionalInt currentVersion = tokenVersionPort.findCurrentTokenVersion(subjectId);
            if (tokenVersion != null && currentVersion.isPresent()
                    && currentVersion.getAsInt() == tokenVersion.intValue()) {
                return OAuth2TokenValidatorResult.success();
            }
        } catch (IllegalArgumentException exception) {
            // Invalid identity claims must be treated as an invalid bearer token.
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Token version is invalid", null));
    }
}
