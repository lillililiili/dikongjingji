package com.lowaltitude.inspection.platform.weather;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PlatformWeatherControllerTest {

    private static final Instant NOW = Instant.parse("2026-07-29T03:00:00Z");

    @Test
    void exposesStableCurrentWeatherWithoutLeakingTheProviderKey() throws Exception {
        MockMvc mvc = controller(cityCode -> new CurrentWeather(
                cityCode,
                "济南市",
                WeatherConditionCode.CLEAR,
                "晴",
                new BigDecimal("29"),
                NOW));

        mvc.perform(get("/api/v1/platform/weather/current")
                        .queryParam("cityCode", "370100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cityCode").value("370100"))
                .andExpect(jsonPath("$.cityName").value("济南市"))
                .andExpect(jsonPath("$.conditionCode").value("CLEAR"))
                .andExpect(jsonPath("$.conditionText").value("晴"))
                .andExpect(jsonPath("$.temperatureCelsius").value(29))
                .andExpect(jsonPath("$.observedAt").value("2026-07-29T03:00:00Z"))
                .andExpect(jsonPath("$.apiKey").doesNotExist());
    }

    @Test
    void resolvesLocatedCityNameToAdcodeAndLiveWeather() throws Exception {
        MockMvc mvc = controller(cityQuery -> new CurrentWeather(
                "370500",
                "\u4e1c\u8425\u5e02",
                WeatherConditionCode.CLEAR,
                "\u6674",
                new BigDecimal("28"),
                NOW));

        mvc.perform(get("/api/v1/platform/weather/current")
                        .queryParam("cityName", "\u4e1c\u8425\u5e02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cityCode").value("370500"))
                .andExpect(jsonPath("$.cityName").value("\u4e1c\u8425\u5e02"))
                .andExpect(jsonPath("$.conditionText").value("\u6674"));
    }

    @Test
    void returnsAStableUnavailableErrorWhenTheProviderFails() throws Exception {
        MockMvc mvc = controller(cityCode -> {
            throw new PlatformWeatherUnavailableException("provider failed");
        });

        mvc.perform(get("/api/v1/platform/weather/current")
                        .header("X-Request-Id", "weather-test")
                        .queryParam("cityCode", "370100"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PLATFORM_WEATHER_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("天气服务暂不可用"))
                .andExpect(jsonPath("$.traceId").value("weather-test"));
    }

    private MockMvc controller(CurrentWeatherGateway gateway) {
        PlatformWeatherService service = new PlatformWeatherService(
                gateway,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(15));
        return MockMvcBuilders
                .standaloneSetup(new PlatformWeatherController(service))
                .build();
    }
}
