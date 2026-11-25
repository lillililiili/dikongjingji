package com.lowaltitude.inspection.live.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowaltitude.inspection.live.application.LiveException.Error;

/**
 * 开发环境（dev profile，live.stream.provider=fake）的本机模拟直播服务，不连任何外部服务。
 *
 * <ul>
 *   <li>建流：流 ID 由直播间 ID 算出；推流地址是一个假的 rtmp 地址；播放地址是 App 内两段示例视频之一
 *       （/api/v1/dev-media/videos/preview/live-*.mp4，App 只在预览构建认这种地址）。</li>
 *   <li>回调：请求头 X-Live-Timestamp（秒）、X-Live-Signature（HMAC-SHA256(timestamp + "\n" + body) 的十六进制），
 *       请求体 {eventId, streamId, type, occurredAt}；时间戳和现在差 5 分钟以上不收。密钥每次启动随机生成，不进仓库，
 *       开发控制接口（LiveDevStreamController）用 {@link #sign} 替“服务商”签名后走同一条验签路径。</li>
 * </ul>
 */
public final class FakeLiveStreamProvider implements LiveStreamProvider {

    static final String TIMESTAMP = "x-live-timestamp";
    static final String SIGNATURE = "x-live-signature";
    static final Duration TOLERANCE = Duration.ofMinutes(5);
    static final List<String> PLAY_URLS = List.of(
            "/api/v1/dev-media/videos/preview/live-night-patrol.mp4",
            "/api/v1/dev-media/videos/preview/live-drone-unboxing.mp4");

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final byte[] secret;

    public FakeLiveStreamProvider(ObjectMapper objectMapper, Clock clock) {
        this(objectMapper, clock, randomSecret());
    }

    FakeLiveStreamProvider(ObjectMapper objectMapper, Clock clock, byte[] secret) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.secret = secret.clone();
    }

    @Override
    public StreamCredentials createStream(UUID roomId) {
        String streamId = "fake-" + roomId.toString().replace("-", "");
        return new StreamCredentials(streamId,
                "rtmp://127.0.0.1:1935/live/" + streamId + "?key=" + hmac(streamId).substring(0, 16),
                PLAY_URLS.get(Math.floorMod(roomId.hashCode(), PLAY_URLS.size())));
    }

    @Override
    public StreamEvent verifyCallback(Map<String, String> headers, String body) {
        String timestamp = headers.get(TIMESTAMP);
        String signature = headers.get(SIGNATURE);
        if (timestamp == null || signature == null || body == null) {
            throw new LiveException(Error.CALLBACK_REJECTED);
        }
        long seconds;
        try {
            seconds = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException invalid) {
            throw new LiveException(Error.CALLBACK_REJECTED);
        }
        Instant sent = Instant.ofEpochSecond(seconds);
        if (Duration.between(sent, clock.instant()).abs().compareTo(TOLERANCE) > 0) {
            throw new LiveException(Error.CALLBACK_REJECTED);
        }
        byte[] expected = hmac(timestamp.trim() + "\n" + body).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, signature.trim().toLowerCase(Locale.ROOT)
                .getBytes(StandardCharsets.US_ASCII))) {
            throw new LiveException(Error.CALLBACK_REJECTED);
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            return new StreamEvent(
                    node.path("eventId").asText(null),
                    node.path("streamId").asText(null),
                    StreamEventType.valueOf(node.path("type").asText("")),
                    Instant.parse(node.path("occurredAt").asText("")));
        } catch (Exception unreadable) {
            throw new LiveException(Error.CALLBACK_REJECTED);
        }
    }

    @Override
    public void stopStream(String streamId) {
        // 模拟服务没有真的流，断流什么都不用做。
    }

    /** 替“服务商”签一条回调：返回请求头（名字小写）。 */
    Map<String, String> sign(String body) {
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(TIMESTAMP, timestamp);
        headers.put(SIGNATURE, hmac(timestamp + "\n" + body));
        return headers;
    }

    /** 一条回调的请求体。 */
    String eventBody(String streamId, StreamEventType type) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("eventId", "fake-evt-" + UUID.randomUUID());
        body.put("streamId", streamId);
        body.put("type", type.name());
        body.put("occurredAt", clock.instant().toString());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String hmac(String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
