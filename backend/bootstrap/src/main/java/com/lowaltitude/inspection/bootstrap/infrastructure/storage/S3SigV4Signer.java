package com.lowaltitude.inspection.bootstrap.infrastructure.storage;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Signature Version 4（S6 对象存储）：给 S3 兼容存储（MinIO、云对象存储）的请求签名，以及生成预签名 GET 地址。
 * 只用 JDK，不引第三方 SDK（AGENTS.md §14）。算法照 AWS 公开文档；单测用文档里的标准测试向量校验。
 *
 * <p>调用方给的 URI 路径必须已经按 {@link #encodePath(String)} 编码过（S3 的规范 URI 不做二次编码、不做路径归一）。
 * 这里只算签名，不发请求；签名用的密钥不出现在任何返回值和异常消息里。
 */
public final class S3SigV4Signer {

    public static final String ALGORITHM = "AWS4-HMAC-SHA256";
    public static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
    public static final String EMPTY_PAYLOAD_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    /** S3 预签名地址最长有效期是 7 天（AWS 限制）。 */
    public static final Duration MAX_PRESIGN_TTL = Duration.ofDays(7);

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter SCOPE_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private S3SigV4Signer() {
    }

    /** 访问密钥。toString 不输出 secret。 */
    public record Credentials(String accessKey, String secretKey) {

        public Credentials {
            if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
                throw new IllegalArgumentException("access key and secret key are required");
            }
        }

        @Override
        public String toString() {
            return "Credentials[accessKey=" + accessKey + ", secretKey=<redacted>]";
        }
    }

    public static String amzDate(Instant at) {
        return AMZ_DATE.format(at);
    }

    /**
     * 算 Authorization 头。headers 是要签的头（名字不分大小写，必须含 host 和 x-amz-date，值和真正发出去的一致）；
     * payloadHash 是请求体的 SHA-256 十六进制（S3 请求同时放进 x-amz-content-sha256 头）。
     */
    public static String authorization(String method, URI uri, Map<String, String> headers, String payloadHash,
                                       Instant at, String region, String service, Credentials credentials) {
        TreeMap<String, String> canonicalHeaders = canonicalHeaders(headers);
        if (!canonicalHeaders.containsKey("host") || !canonicalHeaders.containsKey("x-amz-date")) {
            throw new IllegalArgumentException("host and x-amz-date must be signed");
        }
        String signedHeaders = String.join(";", canonicalHeaders.keySet());
        String canonicalRequest = canonicalRequest(method, uri.getRawPath(), canonicalQuery(uri.getRawQuery()),
                canonicalHeaders, signedHeaders, payloadHash);
        String scope = scope(at, region, service);
        String signature = signature(canonicalRequest, at, scope, region, service, credentials);
        return ALGORITHM + " Credential=" + credentials.accessKey() + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
    }

    /**
     * 预签名 GET（查询串签名，只签 host，请求体不签）。uri 是对象地址（可以已有查询参数），host 取自 uri。
     * 返回的地址在 ttl 之后失效。
     */
    public static URI presign(String method, URI uri, Instant at, Duration ttl, String region, String service,
                              Credentials credentials) {
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(MAX_PRESIGN_TTL) > 0) {
            throw new IllegalArgumentException("presign ttl must be between 1 second and 7 days");
        }
        String scope = scope(at, region, service);
        TreeMap<String, String> query = new TreeMap<>(parseQuery(uri.getRawQuery()));
        query.put("X-Amz-Algorithm", ALGORITHM);
        query.put("X-Amz-Credential", credentials.accessKey() + "/" + scope);
        query.put("X-Amz-Date", amzDate(at));
        query.put("X-Amz-Expires", Long.toString(ttl.toSeconds()));
        query.put("X-Amz-SignedHeaders", "host");
        String canonicalQuery = joinQuery(query);
        TreeMap<String, String> headers = new TreeMap<>();
        headers.put("host", hostHeader(uri));
        String canonicalRequest = canonicalRequest(method, uri.getRawPath(), canonicalQuery, headers, "host",
                UNSIGNED_PAYLOAD);
        String signature = signature(canonicalRequest, at, scope, region, service, credentials);
        return URI.create(uri.getScheme() + "://" + uri.getRawAuthority() + uri.getRawPath() + "?" + canonicalQuery
                + "&X-Amz-Signature=" + signature);
    }

    /** 发请求时 Host 头的值：主机名，端口写明时带端口（JDK HttpClient 也是这样发的）。 */
    public static String hostHeader(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("uri has no host");
        }
        return uri.getPort() == -1 ? host.toLowerCase(Locale.ROOT) : host.toLowerCase(Locale.ROOT) + ":" + uri.getPort();
    }

    /** 对象键编码成 URI 路径：除 RFC 3986 非保留字符和 / 以外全部百分号编码。 */
    public static String encodePath(String key) {
        return encode(key, false);
    }

    public static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    // ---- 内部 ----

    static String canonicalRequest(String method, String rawPath, String canonicalQuery,
                                   TreeMap<String, String> canonicalHeaders, String signedHeaders, String payloadHash) {
        StringBuilder headerBlock = new StringBuilder();
        canonicalHeaders.forEach((name, value) -> headerBlock.append(name).append(':').append(value).append('\n'));
        return method + "\n"
                + (rawPath == null || rawPath.isEmpty() ? "/" : rawPath) + "\n"
                + canonicalQuery + "\n"
                + headerBlock + "\n"
                + signedHeaders + "\n"
                + payloadHash;
    }

    static String stringToSign(String canonicalRequest, Instant at, String scope) {
        return ALGORITHM + "\n" + amzDate(at) + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
    }

    private static String signature(String canonicalRequest, Instant at, String scope, String region, String service,
                                    Credentials credentials) {
        byte[] key = hmac(("AWS4" + credentials.secretKey()).getBytes(StandardCharsets.UTF_8), SCOPE_DATE.format(at));
        key = hmac(key, region);
        key = hmac(key, service);
        key = hmac(key, "aws4_request");
        return HexFormat.of().formatHex(hmac(key, stringToSign(canonicalRequest, at, scope)));
    }

    private static String scope(Instant at, String region, String service) {
        return SCOPE_DATE.format(at) + "/" + region + "/" + service + "/aws4_request";
    }

    private static TreeMap<String, String> canonicalHeaders(Map<String, String> headers) {
        TreeMap<String, String> canonical = new TreeMap<>();
        headers.forEach((name, value) -> canonical.put(name.toLowerCase(Locale.ROOT).trim(),
                value == null ? "" : value.trim().replaceAll(" +", " ")));
        return canonical;
    }

    static String canonicalQuery(String rawQuery) {
        return joinQuery(new TreeMap<>(parseQuery(rawQuery)));
    }

    /** 查询参数先解码再按 SigV4 规则重新编码；同名参数这里用不到，后者覆盖前者。 */
    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> parameters = new TreeMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return parameters;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            parameters.put(URLDecoder.decode(name, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return parameters;
    }

    private static String joinQuery(TreeMap<String, String> decoded) {
        TreeMap<String, String> encoded = new TreeMap<>();
        decoded.forEach((name, value) -> encoded.put(encode(name, true), encode(value, true)));
        List<String> parts = new ArrayList<>();
        encoded.forEach((name, value) -> parts.add(name + "=" + value));
        return String.join("&", parts);
    }

    private static String encode(String value, boolean encodeSlash) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~' || (c == '/' && !encodeSlash)) {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return out.toString();
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
