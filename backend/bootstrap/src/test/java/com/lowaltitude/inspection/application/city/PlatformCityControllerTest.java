package com.lowaltitude.inspection.application.city;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PlatformCityControllerTest {

    private MockMvc mockMvc;
    private RecordingStore store;
    private StubRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        store = new RecordingStore();
        rateLimiter = new StubRateLimiter(CitySearchSelectionRateLimiter.Decision.allowed());
        CityDirectoryReadService service = new CityDirectoryReadService(store);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new PlatformCityController(service, Optional.of(rateLimiter))).build();
    }

    @Test
    void returnsTheNationwideDirectory() throws Exception {
        mockMvc.perform(get("/api/v1/platform/cities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hotCities[0].code").value("370100"))
                .andExpect(jsonPath("$.provinces[0].cities").isArray());
    }

    @Test
    void recordsAnActiveCitySearchSelection() throws Exception {
        mockMvc.perform(post("/api/v1/platform/cities/370100/search-selections")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.7");
                            return request;
                        }))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(rateLimiter.lastClientAddress)
                .isEqualTo("203.0.113.7");
        org.assertj.core.api.Assertions.assertThat(store.incrementCalls).isEqualTo(1);
    }

    @Test
    void rateLimitsBeforeIncrementingTheSearchCount() throws Exception {
        rateLimiter.decision = CitySearchSelectionRateLimiter.Decision.rateLimited(
                Duration.ofMillis(1501));

        mockMvc.perform(post("/api/v1/platform/cities/370100/search-selections")
                        .header("X-Request-Id", "city-rate-limited-test"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(jsonPath("$.code").value("COMMON_RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("请求过于频繁，请稍后重试"))
                .andExpect(jsonPath("$.traceId").value("city-rate-limited-test"));

        org.assertj.core.api.Assertions.assertThat(store.incrementCalls).isZero();
    }

    @Test
    void failsClosedBeforeIncrementingWhenTheLimiterIsUnavailable() throws Exception {
        rateLimiter.decision = CitySearchSelectionRateLimiter.Decision.unavailable();

        mockMvc.perform(post("/api/v1/platform/cities/370100/search-selections")
                        .header("X-Request-Id", "city-rate-limit-unavailable-test"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("COMMON_SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("服务暂不可用，请稍后重试"))
                .andExpect(jsonPath("$.traceId").value("city-rate-limit-unavailable-test"));

        org.assertj.core.api.Assertions.assertThat(store.incrementCalls).isZero();
    }

    @Test
    void failsClosedWithoutARateLimiterWhileDirectoryReadsRemainAvailable() throws Exception {
        mockMvc = MockMvcBuilders.standaloneSetup(new PlatformCityController(
                new CityDirectoryReadService(store), Optional.empty())).build();

        mockMvc.perform(get("/api/v1/platform/cities"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/platform/cities/370100/search-selections"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("COMMON_SERVICE_UNAVAILABLE"));

        org.assertj.core.api.Assertions.assertThat(store.incrementCalls).isZero();
    }

    @Test
    void ignoresRawForwardingHeadersWhenSelectingTheLimiterClientKey() throws Exception {
        mockMvc.perform(post("/api/v1/platform/cities/370100/search-selections")
                        .header("Forwarded", "for=198.51.100.10")
                        .header("X-Forwarded-For", "198.51.100.11")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.8");
                            return request;
                        }))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(rateLimiter.lastClientAddress)
                .isEqualTo("203.0.113.8");
    }

    @Test
    void rejectsAnUnknownCitySearchSelection() throws Exception {
        mockMvc.perform(post("/api/v1/platform/cities/999999/search-selections")
                        .header("X-Request-Id", "city-not-found-test"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("资源不存在"))
                .andExpect(jsonPath("$.traceId").value("city-not-found-test"));
    }

    @Test
    void rejectsAnInvalidCityCodeBeforeRateLimitingOrIncrementing() throws Exception {
        mockMvc.perform(post("/api/v1/platform/cities/not-a-city/search-selections")
                        .header("X-Request-Id", "invalid-city-code-test"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("请求参数无效"))
                .andExpect(jsonPath("$.traceId").value("invalid-city-code-test"));

        org.assertj.core.api.Assertions.assertThat(rateLimiter.invocations).isZero();
        org.assertj.core.api.Assertions.assertThat(store.incrementCalls).isZero();
    }

    private static final class StubRateLimiter implements CitySearchSelectionRateLimiter {

        private Decision decision;
        private int invocations;
        private String lastClientAddress;

        private StubRateLimiter(Decision decision) {
            this.decision = decision;
        }

        @Override
        public Decision check(String clientAddress) {
            invocations++;
            lastClientAddress = clientAddress;
            return decision;
        }
    }

    private static final class RecordingStore implements CityDirectoryReadService.CityDirectoryStore {

        private int incrementCalls;

        @Override
        public List<CityDirectoryReadService.StoredCity> findActiveCities() {
            return List.of(new CityDirectoryReadService.StoredCity(
                    "370100", "济南", "jinan", "J", "370000", "山东", "shandong", "S", 1));
        }

        @Override
        public int incrementSearchCount(String cityCode) {
            incrementCalls++;
            return "370100".equals(cityCode) ? 1 : 0;
        }
    }
}
