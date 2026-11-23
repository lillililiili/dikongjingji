package com.lowaltitude.inspection.bootstrap.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * S3 客户端对着本机假 S3（{@link FakeS3Server}，每个请求都重新验签）读写删和预签名下载（S6）。
 * 测试账号和密钥是现编的，不对应任何真实存储。
 */
class S3ObjectClientTest {

    static final S3SigV4Signer.Credentials CREDENTIALS =
            new S3SigV4Signer.Credentials("test-access", "test-secret-0123456789");
    static final String BUCKET = "unit-platform-private";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-23T08:00:00Z"));
    private FakeS3Server server;
    private S3ObjectClient client;

    @BeforeEach
    void start() throws IOException {
        server = new FakeS3Server(CREDENTIALS, "us-east-1", clock);
        client = client(CREDENTIALS);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private S3ObjectClient client(S3SigV4Signer.Credentials credentials) {
        return new S3ObjectClient(new S3ObjectClient.Settings(server.endpoint(), "us-east-1", credentials, true,
                Duration.ofSeconds(5)), S3ObjectClient.httpClient(Duration.ofSeconds(2)), clock);
    }

    @Test
    void putsReadsChecksAndDeletesSignedObjects() {
        byte[] content = "资质附件".getBytes(StandardCharsets.UTF_8);

        client.put(BUCKET, "identity/organizations/a/qualification/b", content, "application/pdf");

        assertThat(server.object(BUCKET, "identity/organizations/a/qualification/b")).isEqualTo(content);
        assertThat(server.contentType(BUCKET, "identity/organizations/a/qualification/b")).isEqualTo("application/pdf");
        assertThat(client.get(BUCKET, "identity/organizations/a/qualification/b")).contains(content);
        assertThat(client.exists(BUCKET, "identity/organizations/a/qualification/b")).isTrue();

        client.delete(BUCKET, "identity/organizations/a/qualification/b");

        assertThat(client.exists(BUCKET, "identity/organizations/a/qualification/b")).isFalse();
        assertThat(client.get(BUCKET, "identity/organizations/a/qualification/b")).isEmpty();
        client.delete(BUCKET, "identity/organizations/a/qualification/b");
        assertThat(server.requests()).contains("PUT /" + BUCKET + "/identity/organizations/a/qualification/b",
                "HEAD /" + BUCKET + "/identity/organizations/a/qualification/b",
                "DELETE /" + BUCKET + "/identity/organizations/a/qualification/b");
    }

    @Test
    void encodesKeysWithReservedCharacters() {
        client.put(BUCKET, "mall/test$file 1.txt", new byte[] {1, 2}, null);

        assertThat(server.requests()).contains("PUT /" + BUCKET + "/mall/test%24file%201.txt");
        assertThat(server.object(BUCKET, "mall/test$file 1.txt")).containsExactly(1, 2);
        assertThat(server.contentType(BUCKET, "mall/test$file 1.txt")).isEqualTo("application/octet-stream");
    }

    @Test
    void reportsRejectedAndFailedRequestsWithoutTheSecret() {
        S3ObjectClient wrongSecret = client(new S3SigV4Signer.Credentials("test-access", "not-the-right-secret"));

        assertThatThrownBy(() -> wrongSecret.put(BUCKET, "trade/aftersale/x.jpg", new byte[] {1}, "image/jpeg"))
                .isInstanceOf(S3ObjectClient.ObjectStorageUnavailableException.class)
                .hasMessageContaining("HTTP 403")
                .hasMessageNotContaining("not-the-right-secret")
                .hasMessageNotContaining("127.0.0.1");

        server.failWith(500);
        assertThatThrownBy(() -> client.get(BUCKET, "trade/aftersale/x.jpg"))
                .isInstanceOf(S3ObjectClient.ObjectStorageUnavailableException.class)
                .hasMessageContaining("HTTP 500");
        server.failWith(0);

        server.close();
        assertThatThrownBy(() -> client.put(BUCKET, "trade/aftersale/x.jpg", new byte[] {1}, "image/jpeg"))
                .isInstanceOf(S3ObjectClient.ObjectStorageUnavailableException.class)
                .hasMessageNotContaining("test-secret");
    }

    @Test
    void presignedGetWorksUntilItExpires() throws Exception {
        server.putDirectly(BUCKET, "serviceorder/orders/a/files/b", "交付说明".getBytes(StandardCharsets.UTF_8));
        URI url = client.presignGet(server.endpoint(), BUCKET, "serviceorder/orders/a/files/b", Duration.ofMinutes(5));

        assertThat(url.getRawQuery()).contains("X-Amz-Expires=300").contains("X-Amz-Signature=")
                .doesNotContain("test-secret");
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpResponse<String> fresh = http.send(HttpRequest.newBuilder(url).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(fresh.statusCode()).isEqualTo(200);
        assertThat(fresh.body()).isEqualTo("交付说明");

        clock.advance(Duration.ofMinutes(6));
        HttpResponse<String> expired = http.send(HttpRequest.newBuilder(url).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(expired.statusCode()).isEqualTo(403);
    }

    @Test
    void refusesUnsafeBucketsAndKeys() {
        assertThatThrownBy(() -> client.put("Bad_Bucket", "a", new byte[0], null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.get(BUCKET, "../other/a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.get(BUCKET, "/abs")).isInstanceOf(IllegalArgumentException.class);
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void buildsVirtualHostedAddressesWhenPathStyleIsOff() {
        S3ObjectClient virtualHosted = new S3ObjectClient(new S3ObjectClient.Settings(
                URI.create("https://oss.example.test"), "us-east-1", CREDENTIALS, false, null),
                S3ObjectClient.httpClient(null), clock);

        assertThat(virtualHosted.objectUri(URI.create("https://oss.example.test"), "prod-public", "mall/a.jpg"))
                .isEqualTo(URI.create("https://prod-public.oss.example.test/mall/a.jpg"));
    }

    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
