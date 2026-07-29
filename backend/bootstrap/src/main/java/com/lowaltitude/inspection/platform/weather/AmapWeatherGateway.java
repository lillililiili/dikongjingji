package com.lowaltitude.inspection.platform.weather;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

final class AmapWeatherGateway implements CurrentWeatherGateway {

    private static final DateTimeFormatter REPORT_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId PROVIDER_ZONE = ZoneId.of("Asia/Shanghai");

    private final RestClient restClient;
    private final String apiKey;

    AmapWeatherGateway(RestClient restClient, String apiKey) {
        this.restClient = restClient;
        this.apiKey = apiKey;
    }

    @Override
    public CurrentWeather load(String cityCode) {
        try {
            AmapWeatherResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v3/weather/weatherInfo")
                            .queryParam("city", cityCode)
                            .queryParam("extensions", "base")
                            .queryParam("output", "JSON")
                            .queryParam("key", apiKey)
                            .build())
                    .retrieve()
                    .body(AmapWeatherResponse.class);
            return mapResponse(response);
        } catch (RestClientException exception) {
            throw new PlatformWeatherUnavailableException(
                    "weather provider request failed",
                    exception);
        }
    }

    private CurrentWeather mapResponse(AmapWeatherResponse response) {
        if (response == null
                || !"1".equals(response.status())
                || !"10000".equals(response.infocode())
                || response.lives() == null
                || response.lives().isEmpty()) {
            throw new PlatformWeatherUnavailableException(
                    "weather provider returned no live weather");
        }
        AmapLiveWeather live = response.lives().getFirst();
        try {
            return new CurrentWeather(
                    live.adcode(),
                    live.city(),
                    WeatherConditionCode.fromDescription(live.weather()),
                    live.weather(),
                    new BigDecimal(live.temperature()),
                    LocalDateTime.parse(live.reporttime(), REPORT_TIME)
                            .atZone(PROVIDER_ZONE)
                            .toInstant());
        } catch (NumberFormatException | DateTimeParseException | NullPointerException exception) {
            throw new PlatformWeatherUnavailableException(
                    "weather provider returned invalid live weather",
                    exception);
        }
    }

    private record AmapWeatherResponse(
            String status,
            String infocode,
            List<AmapLiveWeather> lives
    ) {
    }

    private record AmapLiveWeather(
            String city,
            String adcode,
            String weather,
            String temperature,
            String reporttime
    ) {
    }
}
