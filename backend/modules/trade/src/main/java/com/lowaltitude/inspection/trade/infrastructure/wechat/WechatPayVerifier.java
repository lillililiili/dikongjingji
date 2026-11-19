package com.lowaltitude.inspection.trade.infrastructure.wechat;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import com.lowaltitude.inspection.trade.application.PaymentChannelException;
import com.lowaltitude.inspection.trade.application.PaymentChannelException.Kind;

/**
 * 验应答和回调的签名：签名串 3 行，每行以 \n 结尾——Wechatpay-Timestamp、Wechatpay-Nonce、原始报文主体
 * （没有主体时是空串）。用微信支付公钥做 SHA256withRSA 验证。
 *
 * <p>只认配置里的那一把公钥：Wechatpay-Serial 必须等于公钥 ID（PUB_KEY_ID_…）。不是这把钥匙的一律不信，
 * 包括平台证书序列号——一期只接公钥模式。
 *
 * <p>签名探测流量（签名以 WECHATPAY/SIGNTEST/ 开头）不做特殊处理，照常验证，自然不通过。
 */
final class WechatPayVerifier {

    static final String TIMESTAMP = "Wechatpay-Timestamp";
    static final String NONCE = "Wechatpay-Nonce";
    static final String SIGNATURE = "Wechatpay-Signature";
    static final String SERIAL = "Wechatpay-Serial";

    private final String publicKeyId;
    private final PublicKey publicKey;
    private final Clock clock;
    private final Duration tolerance;

    WechatPayVerifier(String publicKeyId, PublicKey publicKey, Clock clock, Duration tolerance) {
        this.publicKeyId = publicKeyId;
        this.publicKey = publicKey;
        this.clock = clock;
        this.tolerance = tolerance;
    }

    /** 报文有没有带签名头（四个头至少有一个）。 */
    static boolean hasSignatureHeaders(Map<String, String> headers) {
        return headers.containsKey(SIGNATURE) || headers.containsKey(SERIAL)
                || headers.containsKey(TIMESTAMP) || headers.containsKey(NONCE);
    }

    /** @param headers 键不分大小写的头 */
    void verify(Map<String, String> headers, String body) {
        String serial = headers.get(SERIAL);
        String signature = headers.get(SIGNATURE);
        String timestamp = headers.get(TIMESTAMP);
        String nonce = headers.get(NONCE);
        if (isBlank(serial) || isBlank(signature) || isBlank(timestamp) || isBlank(nonce)) {
            throw untrusted("signature headers are missing");
        }
        if (!publicKeyId.equals(serial.trim())) {
            throw untrusted("signed by an unknown key");
        }
        Instant signedAt;
        try {
            signedAt = Instant.ofEpochSecond(Long.parseLong(timestamp.trim()));
        } catch (NumberFormatException exception) {
            throw untrusted("signature timestamp is not a number");
        }
        // 拦住重放的旧报文；也拦住时间跑偏太多的，那通常是本机时钟有问题，宁可让渠道重发。
        Duration skew = Duration.between(signedAt, clock.instant()).abs();
        if (skew.compareTo(tolerance) > 0) {
            throw untrusted("signature timestamp is outside the allowed window");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(signature.trim());
        } catch (IllegalArgumentException exception) {
            throw untrusted("signature is not Base64");
        }
        String message = timestamp.trim() + "\n" + nonce.trim() + "\n" + (body == null ? "" : body) + "\n";
        boolean valid;
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            valid = verifier.verify(decoded);
        } catch (GeneralSecurityException exception) {
            valid = false;
        }
        if (!valid) {
            throw untrusted("signature does not match");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static PaymentChannelException untrusted(String message) {
        return new PaymentChannelException(Kind.UNTRUSTED, "SIGNATURE", 0, "wechat pay " + message);
    }
}
