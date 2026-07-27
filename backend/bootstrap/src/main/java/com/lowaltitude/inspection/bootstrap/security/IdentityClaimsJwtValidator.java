package com.lowaltitude.inspection.bootstrap.security;

import java.util.Collection;
import java.util.OptionalInt;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.TokenVersionPort;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

final class IdentityClaimsJwtValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID_TOKEN = new OAuth2Error(
            "invalid_token", "Identity claims are invalid", null
    );

    private final TokenVersionPort tokenVersionPort;

    IdentityClaimsJwtValidator(TokenVersionPort tokenVersionPort) {
        this.tokenVersionPort = tokenVersionPort;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        try {
            UUID subjectId = requiredUuid(token.getSubject());
            if (!subjectId.equals(requiredUuid(requiredStringClaim(token, "subjectId")))) {
                return failure();
            }
            requiredUuid(requiredStringClaim(token, "organizationId"));
            requireStringCollection(token, "roles");
            requireStringCollection(token, "permissions");

            int tokenVersion = requiredNonNegativeVersion(token.getClaims().get("tokenVersion"));
            OptionalInt currentVersion = tokenVersionPort.findCurrentTokenVersion(subjectId);
            if (currentVersion.isPresent() && currentVersion.getAsInt() == tokenVersion) {
                return OAuth2TokenValidatorResult.success();
            }
        } catch (RuntimeException exception) {
            // Authentication failures must remain indistinguishable to callers.
        }
        return failure();
    }

    private String requiredStringClaim(Jwt token, String claimName) {
        Object value = token.getClaims().get(claimName);
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return stringValue;
        }
        throw new IllegalArgumentException("Missing or invalid " + claimName);
    }

    private UUID requiredUuid(String value) {
        return UUID.fromString(value);
    }

    private void requireStringCollection(Jwt token, String claimName) {
        Object value = token.getClaims().get(claimName);
        if (!(value instanceof Collection<?> values) || values.stream().anyMatch(item -> !(item instanceof String))) {
            throw new IllegalArgumentException("Missing or invalid " + claimName);
        }
    }

    private int requiredNonNegativeVersion(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Missing or invalid tokenVersion");
        }
        double numericValue = number.doubleValue();
        if (!Double.isFinite(numericValue)
                || numericValue < 0
                || numericValue != Math.rint(numericValue)
                || numericValue > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Missing or invalid tokenVersion");
        }
        return (int) numericValue;
    }

    private OAuth2TokenValidatorResult failure() {
        return OAuth2TokenValidatorResult.failure(INVALID_TOKEN);
    }
}
