package com.lowaltitude.inspection.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.lowaltitude.inspection.application.city.CitySearchSelectionRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisInfrastructureConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
            .withUserConfiguration(RedisInfrastructureConfiguration.class)
            .withPropertyValues(
                    "spring.data.redis.host=127.0.0.1",
                    "inspection.infrastructure.redis.application-namespace=low-altitude",
                    "inspection.infrastructure.redis.business-namespace=inspection",
                    "inspection.infrastructure.redis.idempotency-ttl=24h",
                    "inspection.infrastructure.redis.city-search-selection-rate-limit.limit=20",
                    "inspection.infrastructure.redis.city-search-selection-rate-limit.window=60s");

    @Test
    void registersCitySearchRateLimiterWithAutoConfiguredRedisTemplate() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(StringRedisTemplate.class);
            assertThat(context).hasSingleBean(CitySearchSelectionRateLimiter.class);
        });
    }
}
