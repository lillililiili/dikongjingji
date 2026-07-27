package com.lowaltitude.inspection.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.lowaltitude.inspection.domain.idempotency.IdempotencyRecordResult;
import org.junit.jupiter.api.Test;

class RedisIdempotencyRecordAdapterTest {

    private static final UUID ACTOR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String DIGEST = "a".repeat(64);

    @Test
    void isolatesKeysByApplicationBusinessOperationAndActor() {
        RecordingRedisIdempotencyGateway gateway = new RecordingRedisIdempotencyGateway();
        RedisIdempotencyProperties properties =
                new RedisIdempotencyProperties("low-altitude", "inspection", Duration.ofHours(12));
        RedisIdempotencyRecordAdapter adapter = new RedisIdempotencyRecordAdapter(gateway, properties);

        IdempotencyRecordResult result =
                adapter.record("reservation-create", ACTOR_ID, IDEMPOTENCY_KEY, DIGEST);

        assertThat(result).isEqualTo(IdempotencyRecordResult.ACQUIRED);
        assertThat(gateway.lastKey)
                .isEqualTo("low-altitude:inspection:idempotency:reservation-create:"
                        + ACTOR_ID + ":" + IDEMPOTENCY_KEY);
        assertThat(gateway.values.get(gateway.lastKey)).isEqualTo(DIGEST);
        assertThat(gateway.lastTtl).isEqualTo(Duration.ofHours(12));
    }

    @Test
    void distinguishesReplayFromReusedKeyWithDifferentDigest() {
        RecordingRedisIdempotencyGateway gateway = new RecordingRedisIdempotencyGateway();
        RedisIdempotencyRecordAdapter adapter = new RedisIdempotencyRecordAdapter(
                gateway, new RedisIdempotencyProperties("app", "inspection", Duration.ofMinutes(30)));

        assertThat(adapter.record("publish", ACTOR_ID, IDEMPOTENCY_KEY, DIGEST))
                .isEqualTo(IdempotencyRecordResult.ACQUIRED);
        assertThat(adapter.record("publish", ACTOR_ID, IDEMPOTENCY_KEY, DIGEST))
                .isEqualTo(IdempotencyRecordResult.REPLAY);
        assertThat(adapter.record("publish", ACTOR_ID, IDEMPOTENCY_KEY, "b".repeat(64)))
                .isEqualTo(IdempotencyRecordResult.CONFLICT);
    }

    @Test
    void acceptsStableDotAndUnderscoreOperationCodesWithoutBreakingKeyIsolation() {
        RecordingRedisIdempotencyGateway gateway = new RecordingRedisIdempotencyGateway();
        RedisIdempotencyRecordAdapter adapter = new RedisIdempotencyRecordAdapter(
                gateway, new RedisIdempotencyProperties("app", "inspection", Duration.ofMinutes(30)));

        assertThat(adapter.record("reservation.create_v1", ACTOR_ID, IDEMPOTENCY_KEY, DIGEST))
                .isEqualTo(IdempotencyRecordResult.ACQUIRED);
        assertThat(gateway.lastKey).contains(":reservation.create_v1:");
    }

    private static final class RecordingRedisIdempotencyGateway implements RedisIdempotencyGateway {

        private final Map<String, String> values = new HashMap<>();
        private String lastKey;
        private Duration lastTtl;

        @Override
        public boolean putIfAbsent(String key, String requestDigest, Duration ttl) {
            lastKey = key;
            lastTtl = ttl;
            return values.putIfAbsent(key, requestDigest) == null;
        }

        @Override
        public String get(String key) {
            return values.get(key);
        }
    }
}
