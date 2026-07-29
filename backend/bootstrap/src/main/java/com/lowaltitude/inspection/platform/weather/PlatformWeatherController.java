package com.lowaltitude.inspection.platform.weather;

import java.math.BigDecimal;

import java.util.UUID;

import com.lowaltitude.inspection.bootstrap.api.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class PlatformWeatherController {

    private final PlatformWeatherService service;

    PlatformWeatherController(PlatformWeatherService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/platform/weather/current")
    CurrentWeatherView current(@RequestParam String cityCode) {
        return CurrentWeatherView.from(service.current(cityCode));
    }

    @ExceptionHandler(PlatformWeatherUnavailableException.class)
    ResponseEntity<ApiError> handleUnavailable(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError(
                        "PLATFORM_WEATHER_UNAVAILABLE",
                        "天气服务暂不可用",
                        traceId(request)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> handleInvalidCity(HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(new ApiError(
                        "COMMON_INVALID_ARGUMENT",
                        "请求参数无效",
                        traceId(request)));
    }

    private String traceId(HttpServletRequest request) {
        String requestId = request.getHeader("X-Request-Id");
        return requestId == null || requestId.isBlank()
                ? UUID.randomUUID().toString()
                : requestId;
    }

    record CurrentWeatherView(
            String cityCode,
            String cityName,
            WeatherConditionCode conditionCode,
            String conditionText,
            BigDecimal temperatureCelsius,
            String observedAt
    ) {
        static CurrentWeatherView from(CurrentWeather weather) {
            return new CurrentWeatherView(
                    weather.cityCode(),
                    weather.cityName(),
                    weather.conditionCode(),
                    weather.conditionText(),
                    weather.temperatureCelsius(),
                    weather.observedAt().toString());
        }
    }
}
