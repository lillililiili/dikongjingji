package com.lowaltitude.inspection.platform.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class PlatformWeatherServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-29T03:00:00Z");

    @Test
    void cachesSuccessfulWeatherByCityForTheConfiguredTtl() {
        AtomicInteger calls = new AtomicInteger();
        CurrentWeatherGateway gateway = cityCode -> {
            calls.incrementAndGet();
            return new CurrentWeather(
                    cityCode,
                    "济南市",
                    WeatherConditionCode.CLEAR,
                    "晴",
                    new BigDecimal("29"),
                    NOW);
        };
        PlatformWeatherService service = new PlatformWeatherService(
                gateway,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(15));

        assertThat(service.current("370100")).isEqualTo(service.current("370100"));
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsAnythingOtherThanASixDigitAdcode() {
        PlatformWeatherService service = new PlatformWeatherService(
                cityCode -> {
                    throw new AssertionError("provider must not be called");
                },
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(15));

        assertThatThrownBy(() -> service.current("../370100"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mapsAmapDescriptionsToStableFrontendCodes() {
        assertThat(WeatherConditionCode.fromDescription("晴")).isEqualTo(WeatherConditionCode.CLEAR);
        assertThat(WeatherConditionCode.fromDescription("多云")).isEqualTo(WeatherConditionCode.PARTLY_CLOUDY);
        assertThat(WeatherConditionCode.fromDescription("阴")).isEqualTo(WeatherConditionCode.OVERCAST);
        assertThat(WeatherConditionCode.fromDescription("雷阵雨")).isEqualTo(WeatherConditionCode.THUNDERSTORM);
        assertThat(WeatherConditionCode.fromDescription("小雨")).isEqualTo(WeatherConditionCode.RAIN);
        assertThat(WeatherConditionCode.fromDescription("雨夹雪")).isEqualTo(WeatherConditionCode.SNOW);
        assertThat(WeatherConditionCode.fromDescription("霾")).isEqualTo(WeatherConditionCode.FOG);
        assertThat(WeatherConditionCode.fromDescription("")).isEqualTo(WeatherConditionCode.UNKNOWN);
    }
}
