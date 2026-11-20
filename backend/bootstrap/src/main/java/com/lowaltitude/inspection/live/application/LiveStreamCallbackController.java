package com.lowaltitude.inspection.live.application;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 直播服务商的回调（开播、断流、结束）。不登录，靠签名（LiveStreamProvider.verifyCallback）；请求体原样交给端口验签。
 * 签名头是开发环境模拟服务的形状，选定服务商时按它的文档换。
 */
@RestController
public final class LiveStreamCallbackController {

    static final String TIMESTAMP = "X-Live-Timestamp";
    static final String SIGNATURE = "X-Live-Signature";

    private final LiveService service;

    public LiveStreamCallbackController(LiveService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/live/stream-callbacks")
    public ResponseEntity<Void> receive(
            @RequestHeader(name = TIMESTAMP, required = false) String timestamp,
            @RequestHeader(name = SIGNATURE, required = false) String signature,
            @RequestBody String body
    ) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (timestamp != null) {
            headers.put(TIMESTAMP.toLowerCase(java.util.Locale.ROOT), timestamp);
        }
        if (signature != null) {
            headers.put(SIGNATURE.toLowerCase(java.util.Locale.ROOT), signature);
        }
        service.receiveStreamCallback(headers, body);
        return ResponseEntity.noContent().build();
    }
}
