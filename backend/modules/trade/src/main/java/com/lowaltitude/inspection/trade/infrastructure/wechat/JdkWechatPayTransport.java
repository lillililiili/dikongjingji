package com.lowaltitude.inspection.trade.infrastructure.wechat;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** 用 JDK 自带的 HttpClient 发请求；不跟随重定向（微信的接口不会重定向，跟过去的签名也对不上）。 */
public final class JdkWechatPayTransport implements WechatPayTransport {

    private final HttpClient client;
    private final Duration requestTimeout;

    public JdkWechatPayTransport(Duration connectTimeout, Duration requestTimeout) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.requestTimeout = requestTimeout;
    }

    @Override
    public Response send(Request request) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri()).timeout(requestTimeout);
        request.headers().forEach(builder::header);
        HttpRequest.BodyPublisher publisher = request.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8);
        builder.method(request.method(), publisher);
        HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        Map<String, String> headers = new HashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name, values.getFirst());
            }
        });
        byte[] raw = response.body();
        return new Response(response.statusCode(), headers, new String(raw, StandardCharsets.UTF_8), raw);
    }
}
