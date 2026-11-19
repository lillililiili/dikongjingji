package com.lowaltitude.inspection.trade.infrastructure.wechat;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** 发一次 HTTP 请求。拆出来是为了测试时换成假的；正式实现是 {@link JdkWechatPayTransport}。 */
public interface WechatPayTransport {

    Response send(Request request) throws IOException, InterruptedException;

    /** @param body 要发的原文；签名用的就是这几个字节。GET 时为 null。 */
    record Request(String method, URI uri, Map<String, String> headers, String body) {
    }

    /**
     * @param headers 键不分大小写
     * @param body    收到的原文按 UTF-8 解出的文本，没有时是空串
     * @param raw     收到的原始字节（账单要对它算哈希）
     */
    record Response(int status, Map<String, String> headers, String body, byte[] raw) {

        public Response {
            headers = caseInsensitive(headers);
            body = body == null ? "" : body;
            raw = raw == null ? body.getBytes(StandardCharsets.UTF_8) : raw.clone();
        }

        public Response(int status, Map<String, String> headers, String body) {
            this(status, headers, body, null);
        }

        @Override
        public byte[] raw() {
            return raw.clone();
        }
    }

    static Map<String, String> caseInsensitive(Map<String, String> headers) {
        TreeMap<String, String> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            copy.putAll(headers);
        }
        return Collections.unmodifiableMap(copy);
    }
}
