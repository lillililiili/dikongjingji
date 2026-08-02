package com.lowaltitude.inspection.platform.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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
    void returnsTheLastSuccessfulWeatherAfterAProcessRestartWhenTheProviderIsUnavailable() {
        CurrentWeather weather = new CurrentWeather(
                "370500",
                "东营市",
                WeatherConditionCode.CLEAR,
                "晴",
                new BigDecimal("28"),
                NOW);
        SharedWeatherCache cache = new SharedWeatherCache();
        PlatformWeatherService firstProcess = new PlatformWeatherService(
                cityQuery -> weather,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(15),
                cache);
        PlatformWeatherService restartedProcess = new PlatformWeatherService(
                cityQuery -> {
                    throw new PlatformWeatherUnavailableException("provider failed");
                },
                Clock.fixed(NOW.plus(Duration.ofHours(1)), ZoneOffset.UTC),
                Duration.ofMinutes(15),
                cache);

        assertThat(firstProcess.currentByCityName("东营市")).isEqualTo(weather);
        assertThat(restartedProcess.currentByCityName("东营市")).isEqualTo(weather);
    }

    @Test
    void doesNotCallTheProviderForAFreshCacheRestoredAfterAProcessRestart() {
        CurrentWeather weather = new CurrentWeather(
                "370500",
                "东营市",
                WeatherConditionCode.PARTLY_CLOUDY,
                "多云",
                new BigDecimal("26"),
                NOW);
        SharedWeatherCache cache = new SharedWeatherCache();
        cache.save("code:370500", weather, NOW);
        PlatformWeatherService service = new PlatformWeatherService(
                cityQuery -> {
                    throw new AssertionError("fresh durable cache must avoid the provider");
                },
                Clock.fixed(NOW.plus(Duration.ofMinutes(5)), ZoneOffset.UTC),
                Duration.ofMinutes(15),
                cache);

        assertThat(service.current("370500")).isEqualTo(weather);
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
    void resolvesAndCachesWeatherByLocatedCityName() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CurrentWeatherGateway gateway = cityQuery -> {
            calls.incrementAndGet();
            return new CurrentWeather(
                    "370500",
                    "\u4e1c\u8425\u5e02",
                    WeatherConditionCode.CLEAR,
                    "\u6674",
                    new BigDecimal("28"),
                    NOW);
        };
        PlatformWeatherService service = new PlatformWeatherService(
                gateway,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(15));
        Optional<Method> method = Arrays.stream(PlatformWeatherService.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("currentByCityName"))
                .findFirst();

        assertThat(method).isPresent();
        if (method.isEmpty()) {
            return;
        }

        CurrentWeather first = (CurrentWeather) method.orElseThrow()
                .invoke(service, "  \u4e1c\u8425\u5e02  ");
        CurrentWeather second = (CurrentWeather) method.orElseThrow()
                .invoke(service, "\u4e1c\u8425\u5e02");

        assertThat(first.cityCode()).isEqualTo("370500");
        assertThat(second).isEqualTo(first);
        assertThat(calls).hasValue(1);
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

    private static final class SharedWeatherCache implements PlatformWeatherCache {
        private final Map<String, Snapshot> entries = new HashMap<>();

        @Override
        public Optional<Snapshot> find(String cacheKey) {
            return Optional.ofNullable(entries.get(cacheKey));
        }

        @Override
        public void save(String cacheKey, CurrentWeather weather, Instant cachedAt) {
            entries.put(cacheKey, new Snapshot(weather, cachedAt));
        }
    }
}
