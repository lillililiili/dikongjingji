package com.lowaltitude.inspection.bootstrap.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("security.jwt")
public record JwtProperties(String hmacBase64Secret) {
}
