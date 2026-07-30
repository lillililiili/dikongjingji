package com.lowaltitude.inspection.application.city;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class PlatformCityOpenApiContractTest {

    @Test
    void searchSelectionDocumentsValidationRateLimitAndInfrastructureFailures() throws IOException {
        String contract = Files.readString(
                Path.of("..", "..", "contracts", "openapi-platform-city.yaml"));

        assertThat(contract)
                .contains("/api/v1/platform/cities/{cityCode}/search-selections:")
                .contains("'400':")
                .contains("'429':")
                .contains("'503':")
                .contains("Retry-After")
                .contains("COMMON_INVALID_ARGUMENT")
                .contains("COMMON_RATE_LIMITED")
                .contains("COMMON_SERVICE_UNAVAILABLE");
    }
}
