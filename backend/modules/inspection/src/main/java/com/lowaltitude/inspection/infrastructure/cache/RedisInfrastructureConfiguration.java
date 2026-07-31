package com.lowaltitude.inspection.infrastructure.cache;

import com.lowaltitude.inspection.domain.idempotency.IdempotencyRecordPort;
import com.lowaltitude.inspection.application.city.CitySearchSelectionRateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.data.redis", name = "host")
@EnableConfigurationProperties({
        RedisIdempotencyProperties.class,
        CitySearchSelectionRateLimitProperties.class
})
public class RedisInfrastructureConfiguration {

    @Bean
    @ConditionalOnMissingBean
    RedisIdempotencyGateway redisIdempotencyGateway(StringRedisTemplate redisTemplate) {
        return new StringRedisIdempotencyGateway(redisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(IdempotencyRecordPort.class)
    IdempotencyRecordPort redisIdempotencyRecordPort(
            RedisIdempotencyGateway gateway,
            RedisIdempotencyProperties properties
    ) {
        return new RedisIdempotencyRecordAdapter(gateway, properties);
    }

    @Bean
    @ConditionalOnMissingBean(CitySearchSelectionRateLimiter.class)
    CitySearchSelectionRateLimiter citySearchSelectionRateLimiter(
            StringRedisTemplate redisTemplate,
            RedisIdempotencyProperties namespaces,
            CitySearchSelectionRateLimitProperties properties
    ) {
        return new StringRedisCitySearchSelectionRateLimiter(
                redisTemplate, namespaces, properties);
    }
}
