package com.lowaltitude.inspection.bootstrap.infrastructure.storage;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 测试用的本机假 S3（JDK 自带 HttpServer，只监听 127.0.0.1 的随机端口）：路径式寻址 /桶/对象键，支持 PUT、GET、HEAD、
 * DELETE。每个请求都按收到的真实头和请求体重新算 SigV4 签名再比对（含 Host 头和 x-amz-content-sha256），预签名 GET
 * 按查询串签名和有效期比对；签名不对回 403，就像真的存储一样。只认测试给的一个账号。
 */
final class FakeS3Server implements AutoCloseable {

    private static final Pattern AUTHORIZATION = Pattern.compile(
            "^AWS4-HMAC-SHA256 Credential=([^/]+)/(\\d{8})/([^/]+)/s3/aws4_request, SignedHeaders=([a-z0-9;-]+), "
                    + "Signature=([0-9a-f]{64})$");
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final HttpServer server;
    private final S3SigV4Signer.Credentials credentials;
    private final String region;
    private final Clock clock;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final Map<String, String> contentTypes = new ConcurrentHashMap<>();
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile int forcedStatus;

    FakeS3Server(S3SigV4Signer.Credentials credentials, String region, Clock clock) throws IOException {
        this.credentials = credentials;
        this.region = region;
        this.clock = clock;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    byte[] object(String bucket, String key) {
        return objects.get(bucket + "/" + key);
    }

    String contentType(String bucket, String key) {
        return contentTypes.get(bucket + "/" + key);
    }

    void putDirectly(String bucket, String key, byte[] content) {
        objects.put(bucket + "/" + key, content);
    }

    List<String> requests() {
        return List.copyOf(requests);
    }

    /** 之后的请求一律回这个状态码（0 恢复正常），模拟存储出错。 */
    void failWith(int status) {
        this.forcedStatus = status;
    }

    private volatile boolean closed;

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange; InputStream in = exchange.getRequestBody()) {
            byte[] body = in.readAllBytes();
            String method = exchange.getRequestMethod();
            URI uri = exchange.getRequestURI();
            requests.add(method + " " + uri.getRawPath());
            if (forcedStatus != 0) {
                respond(exchange, forcedStatus, new byte[0]);
                return;
            }
            boolean presigned = uri.getRawQuery() != null && uri.getRawQuery().contains("X-Amz-Signature=");
            if (!(presigned ? presignValid(method, uri, exchange.getRequestHeaders().getFirst("Host")) : authorizationValid(exchange, method, uri, body))) {
                respond(exchange, 403, "SignatureDoesNotMatch".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String path = URLDecoder.decode(uri.getRawPath().substring(1), StandardCharsets.UTF_8);
            switch (method) {
                case "PUT" -> {
                    objects.put(path, body);
                    String type = exchange.getRequestHeaders().getFirst("Content-Type");
                    if (type != null) {
                        contentTypes.put(path, type);
                    }
                    respond(exchange, 200, new byte[0]);
                }
                case "GET" -> {
                    byte[] content = objects.get(path);
                    respond(exchange, content == null ? 404 : 200, content == null ? new byte[0] : content);
                }
                case "HEAD" -> {
                    boolean found = objects.containsKey(path);
                    exchange.sendResponseHeaders(found ? 200 : 404, -1);
                }
                case "DELETE" -> {
                    objects.remove(path);
                    respond(exchange, 204, new byte[0]);
                }
                default -> respond(exchange, 405, new byte[0]);
            }
        }
    }

    private boolean authorizationValid(HttpExchange exchange, String method, URI uri, byte[] body) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        Matcher matcher = authorization == null ? null : AUTHORIZATION.matcher(authorization);
        if (matcher == null || !matcher.matches() || !credentials.accessKey().equals(matcher.group(1))
                || !region.equals(matcher.group(3))) {
            return false;
        }
        String payloadHash = exchange.getRequestHeaders().getFirst("x-amz-content-sha256");
        if (payloadHash == null || !payloadHash.equals(S3SigV4Signer.sha256Hex(body))) {
            return false;
        }
        String amzDate = exchange.getRequestHeaders().getFirst("x-amz-date");
        if (amzDate == null) {
            return false;
        }
        Instant at = Instant.from(AMZ_DATE.parse(amzDate));
        if (Duration.between(at, clock.instant()).abs().compareTo(Duration.ofMinutes(15)) > 0) {
            return false;
        }
        Map<String, String> signed = new LinkedHashMap<>();
        for (String name : matcher.group(4).split(";")) {
            String value = exchange.getRequestHeaders().getFirst(name);
            if (value == null) {
                return false;
            }
            signed.put(name, value);
        }
        String expected = S3SigV4Signer.authorization(method, uri, signed, payloadHash, at, region, "s3", credentials);
        return expected.equals(authorization);
    }

    private boolean presignValid(String method, URI uri, String host) {
        Map<String, String> query = new LinkedHashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            int equals = pair.indexOf('=');
            query.put(pair.substring(0, equals), URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        Instant at = Instant.from(AMZ_DATE.parse(query.get("X-Amz-Date")));
        Duration ttl = Duration.ofSeconds(Long.parseLong(query.get("X-Amz-Expires")));
        if (clock.instant().isAfter(at.plus(ttl))) {
            return false;
        }
        URI unsigned = URI.create("http://" + host + uri.getRawPath());
        URI expected = S3SigV4Signer.presign(method, unsigned, at, ttl, region, "s3", credentials);
        return expected.getRawQuery().equals(uri.getRawQuery());
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
    }
}
