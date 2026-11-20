package com.lowaltitude.inspection.live.application;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowaltitude.inspection.live.application.LiveException.Error;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * 直播服务（LiveStreamProvider）：服务商还没定（概要设计 §14 D20），所以只有两种实现。
 * dev profile 并且 live.stream.provider=fake 时是本机模拟服务（{@link FakeLiveStreamProvider}）；
 * 其他情况一律“不可用”：建不了流（LIVE_STREAM_UNAVAILABLE），回调全部拒绝。模拟服务不会出现在 dev 以外的环境。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class LiveStreamConfiguration {

    @Bean
    LiveStreamProvider liveStreamProvider(
            Environment environment,
            ObjectMapper objectMapper,
            @Value("${live.stream.provider:none}") String provider
    ) {
        return choose(environment, objectMapper, provider);
    }

    static LiveStreamProvider choose(Environment environment, ObjectMapper objectMapper, String provider) {
        if ("fake".equals(provider) && environment.acceptsProfiles(Profiles.of("dev"))) {
            return new FakeLiveStreamProvider(objectMapper, Clock.systemUTC());
        }
        return new UnavailableLiveStreamProvider();
    }

    /** 没有可用的直播服务。 */
    static final class UnavailableLiveStreamProvider implements LiveStreamProvider {

        @Override
        public StreamCredentials createStream(UUID roomId) {
            throw new LiveException(Error.STREAM_UNAVAILABLE);
        }

        @Override
        public StreamEvent verifyCallback(Map<String, String> headers, String body) {
            throw new LiveException(Error.CALLBACK_REJECTED);
        }

        @Override
        public void stopStream(String streamId) {
            // 没有流可断。
        }
    }
}
