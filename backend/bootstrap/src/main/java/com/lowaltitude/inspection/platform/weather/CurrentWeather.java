package com.lowaltitude.inspection.platform.weather;

import java.math.BigDecimal;
import java.time.Instant;

record CurrentWeather(
        String cityCode,
        String cityName,
        WeatherConditionCode conditionCode,
        String conditionText,
        BigDecimal temperatureCelsius,
        Instant observedAt
) {
}
