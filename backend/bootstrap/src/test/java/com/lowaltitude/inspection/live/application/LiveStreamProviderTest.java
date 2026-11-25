package com.lowaltitude.inspection.live.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowaltitude.inspection.live.application.LiveException.Error;
import com.lowaltitude.inspection.live.application.LiveStreamProvider.StreamEvent;
import com.lowaltitude.inspection.live.application.LiveStreamProvider.StreamEventType;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.mock.env.MockEnvironment;

/**
 * 直播服务的两种实现：dev 下的本机模拟服务（建流、签名、验签）和其他环境的“不可用”；
 * 模拟服务只在 dev profile 并且 live.stream.provider=fake 时出现，开发控制接口只在 dev 下有。
 */
class LiveStreamProviderTest {

    private static final Instant NOW = Instant.parse("2026-11-20T11:00:00Z");
    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FakeLiveStreamProvider fake = new FakeLiveStreamProvider(objectMapper,
            Clock.fixed(NOW, ZoneOffset.UTC), SECRET);

    @Test
    void theFakeGivesAPushUrlAndOneOfTheBundledSampleVideosAsThePlayUrl() {
        UUID roomId = UUID.fromString("f4000000-0000-4000-8000-000000000009");
        LiveStreamProvider.StreamCredentials stream = fake.createStream(roomId);

        assertThat(stream.streamId()).isEqualTo("fake-f4000000000040008000000000000009");
        assertThat(stream.pushUrl()).startsWith("rtmp://127.0.0.1:1935/live/" + stream.streamId() + "?key=");
        assertThat(stream.playUrl()).isIn(FakeLiveStreamProvider.PLAY_URLS)
                .matches("/api/v1/dev-media/videos/preview/live-[a-z-]+\\.mp4");
        assertThat(fake.createStream(roomId)).isEqualTo(stream);
    }

    @Test
    void aSignedCallbackIsReadBackAndAnythingElseIsRejected() {
        String body = fake.eventBody("fake-1", StreamEventType.STARTED);
        Map<String, String> headers = fake.sign(body);

        StreamEvent event = fake.verifyCallback(headers, body);
        assertThat(event.streamId()).isEqualTo("fake-1");
        assertThat(event.type()).isEqualTo(StreamEventType.STARTED);
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.eventId()).startsWith("fake-evt-");
        assertThat(headers.get(FakeLiveStreamProvider.TIMESTAMP)).isEqualTo(Long.toString(NOW.getEpochSecond()));

        // 改了请求体、签名不对、少了头、时间戳差 5 分钟以上、另一个密钥签的，都不收。
        assertRejected(headers, body.replace("STARTED", "ENDED"));
        Map<String, String> wrong = new HashMap<>(headers);
        wrong.put(FakeLiveStreamProvider.SIGNATURE, "00" + headers.get(FakeLiveStreamProvider.SIGNATURE).substring(2));
        assertRejected(wrong, body);
        assertRejected(Map.of(FakeLiveStreamProvider.TIMESTAMP, headers.get(FakeLiveStreamProvider.TIMESTAMP)), body);
        FakeLiveStreamProvider later = new FakeLiveStreamProvider(objectMapper,
                Clock.fixed(NOW.plusSeconds(301), ZoneOffset.UTC), SECRET);
        assertThatThrownBy(() -> later.verifyCallback(headers, body)).isInstanceOfSatisfying(LiveException.class,
                rejected -> assertThat(rejected.error()).isEqualTo(Error.CALLBACK_REJECTED));
        FakeLiveStreamProvider other = new FakeLiveStreamProvider(objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> other.verifyCallback(headers, body)).isInstanceOf(LiveException.class);
        // 签名对但看不懂的请求体也不收。
        Map<String, String> signedGarbage = fake.sign("{\"type\":\"PAUSED\"}");
        assertRejected(signedGarbage, "{\"type\":\"PAUSED\"}");
    }

    @Test
    void onlyTheDevProfileWithTheFakeSettingGetsTheFake() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        MockEnvironment production = new MockEnvironment();

        assertThat(LiveStreamConfiguration.choose(dev, objectMapper, "fake")).isInstanceOf(FakeLiveStreamProvider.class);
        assertThat(LiveStreamConfiguration.choose(dev, objectMapper, "none"))
                .isInstanceOf(LiveStreamConfiguration.UnavailableLiveStreamProvider.class);
        assertThat(LiveStreamConfiguration.choose(production, objectMapper, "fake"))
                .isInstanceOf(LiveStreamConfiguration.UnavailableLiveStreamProvider.class);
        assertThat(LiveDevStreamController.class.getAnnotation(Profile.class).value()).containsExactly("dev");
    }

    @Test
    void withoutAStreamServiceNoRoomCanBeCreatedAndEveryCallbackIsRejected() {
        LiveStreamProvider unavailable = new LiveStreamConfiguration.UnavailableLiveStreamProvider();

        assertThatThrownBy(() -> unavailable.createStream(UUID.randomUUID()))
                .isInstanceOfSatisfying(LiveException.class,
                        rejected -> assertThat(rejected.error()).isEqualTo(Error.STREAM_UNAVAILABLE));
        String body = fake.eventBody("fake-1", StreamEventType.ENDED);
        assertThatThrownBy(() -> unavailable.verifyCallback(fake.sign(body), body))
                .isInstanceOfSatisfying(LiveException.class,
                        rejected -> assertThat(rejected.error()).isEqualTo(Error.CALLBACK_REJECTED));
        unavailable.stopStream("fake-1");
    }

    @Test
    void theDevControlIsNotPartOfTheContract() throws Exception {
        String contract = java.nio.file.Files.readString(java.nio.file.Path.of("..", "..", "contracts",
                "openapi-live.yaml"));
        Method simulate = LiveDevStreamController.class.getMethod("simulate",
                org.springframework.security.core.Authentication.class, UUID.class,
                LiveDevStreamController.DevStreamRequest.class);
        assertThat(simulate).isNotNull();
        assertThat(contract).doesNotContain("/api/v1/dev/");
    }

    private void assertRejected(Map<String, String> headers, String body) {
        assertThatThrownBy(() -> fake.verifyCallback(headers, body)).isInstanceOfSatisfying(LiveException.class,
                rejected -> assertThat(rejected.error()).isEqualTo(Error.CALLBACK_REJECTED));
    }
}
