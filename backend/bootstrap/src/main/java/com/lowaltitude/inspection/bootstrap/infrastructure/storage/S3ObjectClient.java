package com.lowaltitude.inspection.bootstrap.infrastructure.storage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * S3 兼容对象存储（MinIO 可用）的最小客户端（S6）：PUT、GET、DELETE、HEAD 和预签名 GET，用 JDK HttpClient 和
 * {@link S3SigV4Signer} 自己签名，不引第三方 SDK。
 *
 * <p>失败一律抛 {@link ObjectStorageUnavailableException}，消息只有操作、桶和对象键、HTTP 状态，不带签名、密钥和存储地址；
 * 对象不存在时 GET 返回空、HEAD 返回 false、DELETE 当作成功。
 */
public final class S3ObjectClient {

    static final String SERVICE = "s3";
    /** 桶名：S3 规则的子集（小写字母、数字、短横线，3–63 位）。 */
    private static final Pattern BUCKET = Pattern.compile("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$");

    /** 连接设置。endpoint 是后端访问存储的内部地址（只写到主机和端口）。toString 不输出 secret。 */
    public record Settings(URI endpoint, String region, S3SigV4Signer.Credentials credentials, boolean pathStyle,
                           Duration requestTimeout) {

        public Settings {
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(region, "region");
            Objects.requireNonNull(credentials, "credentials");
            requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
        }
    }

    private final Settings settings;
    private final HttpClient http;
    private final Clock clock;

    public S3ObjectClient(Settings settings, HttpClient http, Clock clock) {
        this.settings = settings;
        this.http = http;
        this.clock = clock;
    }

    public static HttpClient httpClient(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public void put(String bucket, String key, byte[] content, String contentType) {
        Objects.requireNonNull(content, "content");
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("content-type", contentType == null || contentType.isBlank()
                ? "application/octet-stream" : contentType);
        HttpResponse<byte[]> response = send("PUT", bucket, key, content, extra);
        if (!success(response.statusCode())) {
            throw failure("PUT", bucket, key, response.statusCode());
        }
    }

    public Optional<byte[]> get(String bucket, String key) {
        HttpResponse<byte[]> response = send("GET", bucket, key, null, Map.of());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (!success(response.statusCode())) {
            throw failure("GET", bucket, key, response.statusCode());
        }
        return Optional.of(response.body());
    }

    public boolean exists(String bucket, String key) {
        HttpResponse<byte[]> response = send("HEAD", bucket, key, null, Map.of());
        if (response.statusCode() == 404) {
            return false;
        }
        if (!success(response.statusCode())) {
            throw failure("HEAD", bucket, key, response.statusCode());
        }
        return true;
    }

    public void delete(String bucket, String key) {
        HttpResponse<byte[]> response = send("DELETE", bucket, key, null, Map.of());
        if (response.statusCode() != 404 && !success(response.statusCode())) {
            throw failure("DELETE", bucket, key, response.statusCode());
        }
    }

    /**
     * 短时效下载地址：signingEndpoint 是客户端能访问的地址（例如对外的 https 文件域名，反向代理原样转发 Host），
     * 签名按它的主机算。ttl 不超过 7 天，业务上一般几分钟。
     */
    public URI presignGet(URI signingEndpoint, String bucket, String key, Duration ttl) {
        return S3SigV4Signer.presign("GET", objectUri(signingEndpoint, bucket, key), clock.instant(), ttl,
                settings.region(), SERVICE, settings.credentials());
    }

    URI objectUri(URI endpoint, String bucket, String key) {
        if (bucket == null || !BUCKET.matcher(bucket).matches()) {
            throw new IllegalArgumentException("bucket name is invalid");
        }
        if (key == null || key.isEmpty() || key.startsWith("/") || key.contains("..") || key.length() > 1024) {
            throw new IllegalArgumentException("object key is invalid");
        }
        String base = endpoint.getScheme() + "://";
        String authority = endpoint.getRawAuthority();
        String path = endpoint.getRawPath() == null ? "" : endpoint.getRawPath();
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String encodedKey = S3SigV4Signer.encodePath(key);
        return settings.pathStyle()
                ? URI.create(base + authority + path + "/" + bucket + "/" + encodedKey)
                : URI.create(base + bucket + "." + authority + path + "/" + encodedKey);
    }

    private HttpResponse<byte[]> send(String method, String bucket, String key, byte[] body,
                                      Map<String, String> extraHeaders) {
        URI uri = objectUri(settings.endpoint(), bucket, key);
        Instant at = clock.instant();
        String payloadHash = S3SigV4Signer.sha256Hex(body == null ? new byte[0] : body);
        Map<String, String> signed = new LinkedHashMap<>(extraHeaders);
        signed.put("x-amz-content-sha256", payloadHash);
        signed.put("x-amz-date", S3SigV4Signer.amzDate(at));
        Map<String, String> toSign = new LinkedHashMap<>(signed);
        toSign.put("host", S3SigV4Signer.hostHeader(uri));
        String authorization = S3SigV4Signer.authorization(method, uri, toSign, payloadHash, at,
                settings.region(), SERVICE, settings.credentials());

        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(settings.requestTimeout());
        signed.forEach(request::header);
        request.header("Authorization", authorization);
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
        request.method(method, publisher);
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException exception) {
            throw new ObjectStorageUnavailableException(method + " " + bucket + "/" + key + " failed: "
                    + exception.getClass().getSimpleName(), exception);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ObjectStorageUnavailableException(method + " " + bucket + "/" + key + " was interrupted",
                    interrupted);
        }
    }

    private static boolean success(int status) {
        return status >= 200 && status < 300;
    }

    private static ObjectStorageUnavailableException failure(String method, String bucket, String key, int status) {
        return new ObjectStorageUnavailableException(method + " " + bucket + "/" + key + " returned HTTP " + status);
    }

    /** 对象存储暂不可用（连不上、超时、拒绝、返回错误）。 */
    public static final class ObjectStorageUnavailableException extends RuntimeException {

        public ObjectStorageUnavailableException(String message) {
            super(message);
        }

        public ObjectStorageUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
