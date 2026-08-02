package com.lowaltitude.inspection.platform.weather;

import java.net.http.HttpClient;
import java.time.Clock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlatformWeatherProperties.class)
class PlatformWeatherConfiguration {

    @Bean
    CurrentWeatherGateway currentWeatherGateway(PlatformWeatherProperties properties) {
        if (!properties.enabled()) {
            return cityCode -> {
                throw new PlatformWeatherUnavailableException(
                        "weather provider is disabled");
            };
        }
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "platform.weather.api-key must be configured when weather is enabled");
        }

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        RestClient restClient = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
        return new AmapWeatherGateway(restClient, properties.apiKey().trim());
    }

    @Bean
    PlatformWeatherCache platformWeatherCache(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectMapper objectMapper,
            PlatformWeatherProperties properties
    ) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            return PlatformWeatherCache.noOp();
        }
        return new RedisPlatformWeatherCache(
                redisTemplate,
                objectMapper,
                properties.cacheKeyPrefix(),
                properties.lastSuccessTtl());
    }

    @Bean
    PlatformWeatherService platformWeatherService(
            CurrentWeatherGateway gateway,
            PlatformWeatherProperties properties,
            PlatformWeatherCache cache
    ) {
        return new PlatformWeatherService(
                gateway,
                Clock.systemUTC(),
                properties.cacheTtl(),
                cache);
    }
}
