package com.lowaltitude.inspection.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.lowaltitude.inspection.bootstrap.security.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class SecurityConfigurationHmacIntegrationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withUserConfiguration(HmacTestApplication.class)
            .withPropertyValues("security.jwt.hmac-secret=test-only-legacy-hmac-secret-that-is-at-least-thirty-two-bytes");

    @Test
    void emptyBase64HmacKeyPreventsApplicationStartup() {
        assertApplicationDoesNotStart("");
    }

    @Test
    void invalidBase64HmacKeyPreventsApplicationStartup() {
        assertApplicationDoesNotStart("not-base64");
    }

    @Test
    void shortBase64HmacKeyPreventsApplicationStartup() {
        assertApplicationDoesNotStart("c2hvcnQ=");
    }

    private void assertApplicationDoesNotStart(String hmacBase64Secret) {
        contextRunner
                .withPropertyValues("security.jwt.hmac-base64-secret=" + hmacBase64Secret)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("security.jwt.hmac-base64-secret");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(SecurityConfiguration.class)
    static class HmacTestApplication {
    }
}
