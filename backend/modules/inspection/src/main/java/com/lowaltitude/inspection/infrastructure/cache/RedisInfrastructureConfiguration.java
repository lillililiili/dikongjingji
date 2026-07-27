package com.lowaltitude.inspection.infrastructure.cache;

import com.lowaltitude.inspection.domain.idempotency.IdempotencyRecordPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.data.redis", name = "host")
@EnableConfigurationProperties(RedisIdempotencyProperties.class)
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
}
