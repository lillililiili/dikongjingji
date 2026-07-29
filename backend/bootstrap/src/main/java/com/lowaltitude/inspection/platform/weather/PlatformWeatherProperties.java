package com.lowaltitude.inspection.platform.weather;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("platform.weather")
record PlatformWeatherProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("https://restapi.amap.com") URI baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("15m") Duration cacheTtl,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout
) {
}
