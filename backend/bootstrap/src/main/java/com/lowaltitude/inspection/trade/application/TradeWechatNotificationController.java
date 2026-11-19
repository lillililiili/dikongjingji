package com.lowaltitude.inspection.trade.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import com.lowaltitude.inspection.trade.application.TradePaymentService.NotificationResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 微信支付的回调（契约 receiveWechatNotification）。不登录；验签、解密、核对都在 {@link TradePaymentService}。
 *
 * <p>请求体按原始字节读（验签要和微信签的一字不差），限 64KB——正常的回调只有一两 KB。
 * 回复：处理了（含重复的通知）204；验签不过 401；处理失败 500。后两种微信会按间隔重发。
 */
@RestController
public final class TradeWechatNotificationController {

    static final int MAX_BODY_BYTES = 64 * 1024;
    private static final Map<String, String> FAIL = Map.of("code", "FAIL", "message", "失败");

    private final TradePaymentService payments;

    public TradeWechatNotificationController(TradePaymentService payments) {
        this.payments = payments;
    }

    @PostMapping("/api/v1/trade/channels/wechat/notifications")
    public ResponseEntity<Map<String, String>> receive(HttpServletRequest request) throws IOException {
        String body = readBody(request);
        if (body == null) {
            return fail(HttpStatus.PAYLOAD_TOO_LARGE);
        }
        NotificationResult result = payments.handleNotification(headers(request), body);
        return switch (result) {
            case ACKNOWLEDGED -> ResponseEntity.noContent().build();
            case UNTRUSTED -> fail(HttpStatus.UNAUTHORIZED);
            case FAILED -> fail(HttpStatus.INTERNAL_SERVER_ERROR);
        };
    }

    /** 读不超过上限的原始字节，按 UTF-8 严格解码；超长或不是合法 UTF-8 时返回 null / 空串交给验签拒绝。 */
    static String readBody(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            return null;
        }
        byte[] bytes;
        try (InputStream input = request.getInputStream()) {
            bytes = input.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (bytes.length > MAX_BODY_BYTES) {
            return null;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            // 不是合法 UTF-8：解码会改变字节，验签必然不过，直接给一个验不过的空体。
            return "";
        }
    }

    private static Map<String, String> headers(HttpServletRequest request) {
        TreeMap<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }

    private static ResponseEntity<Map<String, String>> fail(HttpStatus status) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(FAIL);
    }
}
