package com.lowaltitude.inspection.trade.infrastructure.wechat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 测试里扮演微信支付：用商户公钥核对我们发出的请求签名，用自造的“微信支付公钥”对应的私钥给应答和回调签名，
 * 用自造的 APIv3 密钥加密回调。全部密钥都是测试时现生成的，不是任何真实账户的。
 */
final class FakeWechatPay implements WechatPayTransport {

    static final String SP_MCHID = "1900000001";
    static final String SP_APPID = "wxd678efh567hg6787";
    static final String SERIAL = "5157F09EFDC096DE15EBE81A47057A7232F1B8E1";
    static final String PUBLIC_KEY_ID = "PUB_KEY_ID_0114232134912410000000000000";
    static final String SUB_MCHID = "1900000101";
    static final byte[] API_V3_KEY = "0123456789abcdefABCDEF0123456789".getBytes(StandardCharsets.UTF_8);
    static final Instant NOW = Instant.parse("2026-11-18T02:00:00Z");

    static final KeyPair MERCHANT = newKeyPair();
    static final KeyPair PLATFORM = newKeyPair();

    private static final Pattern AUTH_FIELD = Pattern.compile("(\\w+)=\"([^\"]*)\"");

    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final List<Request> requests = new ArrayList<>();
    private final Deque<Response> responses = new ArrayDeque<>();
    private int nonceCounter;

    static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static WechatPayCredentials credentials() {
        return new WechatPayCredentials(SP_MCHID, SP_APPID, SERIAL, MERCHANT.getPrivate(), PUBLIC_KEY_ID,
                PLATFORM.getPublic(), API_V3_KEY);
    }

    static WechatPaySettings settings() {
        return new WechatPaySettings(null, URI.create("https://pay.example.test/api/v1/trade/channels/wechat/notifications"),
                URI.create("https://pay.example.test/api/v1/trade/channels/wechat/notifications"), null);
    }

    WechatPayChannel channel() {
        return new WechatPayChannel(settings(), credentials(), this, clock, () -> "NONCE" + (++nonceCounter));
    }

    // ---- 应答 ----

    void reply(Response response) {
        responses.add(response);
    }

    void replySigned(int status, String body) {
        reply(new Response(status, signedHeaders(NOW, "RESPNONCE", body, PLATFORM.getPrivate(), PUBLIC_KEY_ID), body));
    }

    /** 账单文件的下载应答：文档说不带微信的签名头，原样给字节。 */
    void replyFile(int status, byte[] raw) {
        reply(new Response(status, Map.of("Content-Type", "application/octet-stream"),
                new String(raw, StandardCharsets.UTF_8), raw));
    }

    /** 申请账单的应答（签过名）：SHA1 摘要和下载地址。 */
    void replyBillApplication(String hashValue, String downloadUrl) {
        replySigned(200, "{\"hash_type\":\"SHA1\",\"hash_value\":\"" + hashValue + "\",\"download_url\":\""
                + downloadUrl + "\"}");
    }

    static String sha1(byte[] raw) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(raw));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Override
    public Response send(Request request) {
        requests.add(request);
        if (responses.isEmpty()) {
            throw new AssertionError("unexpected request " + request.method() + " " + request.uri());
        }
        return responses.removeFirst();
    }

    Request lastRequest() {
        return requests.getLast();
    }

    // ---- 签名 ----

    static Map<String, String> signedHeaders(Instant at, String nonce, String body, PrivateKey key, String serial) {
        String timestamp = Long.toString(at.getEpochSecond());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Wechatpay-Timestamp", timestamp);
        headers.put("Wechatpay-Nonce", nonce);
        headers.put("Wechatpay-Signature", sign(key, timestamp + "\n" + nonce + "\n" + body + "\n"));
        headers.put("Wechatpay-Serial", serial);
        headers.put("Wechatpay-Signature-Type", "WECHATPAY2-SHA256-RSA2048");
        headers.put("Request-ID", "08F78BB5AF0610D302");
        return headers;
    }

    static String sign(PrivateKey key, String message) {
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key);
            signer.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static boolean verify(PublicKey key, String message, String signature) {
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (GeneralSecurityException exception) {
            return false;
        }
    }

    /** 微信那边核对 Authorization：拆字段、按 5 行规则重建签名串、用商户公钥验证。返回拆出来的字段。 */
    static Map<String, String> checkAuthorization(Request request) {
        String header = request.headers().get("Authorization");
        if (header == null || !header.startsWith("WECHATPAY2-SHA256-RSA2048 ")) {
            throw new AssertionError("authorization schema is wrong: " + header);
        }
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher matcher = AUTH_FIELD.matcher(header.substring("WECHATPAY2-SHA256-RSA2048 ".length()));
        while (matcher.find()) {
            fields.put(matcher.group(1), matcher.group(2));
        }
        URI uri = request.uri();
        String pathAndQuery = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        String message = request.method() + "\n" + pathAndQuery + "\n" + fields.get("timestamp") + "\n"
                + fields.get("nonce_str") + "\n" + (request.body() == null ? "" : request.body()) + "\n";
        if (!verify(MERCHANT.getPublic(), message, fields.get("signature"))) {
            throw new AssertionError("request signature does not verify");
        }
        return fields;
    }

    // ---- 回调 ----

    static String encrypt(byte[] key, String associatedData, String nonce, String plaintext) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
            if (!associatedData.isEmpty()) {
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            }
            return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 一条按文档格式组装、加密、签名的回调。 */
    static Notification notification(String id, String eventType, String originalType, String plaintext) {
        String ciphertext = encrypt(API_V3_KEY, originalType, "fdasflkja484", plaintext);
        String body = "{\"id\":\"" + id + "\",\"create_time\":\"2026-11-18T10:00:00+08:00\",\"resource_type\":\"encrypt-resource\","
                + "\"event_type\":\"" + eventType + "\",\"summary\":\"支付成功\",\"resource\":{\"original_type\":\""
                + originalType + "\",\"algorithm\":\"AEAD_AES_256_GCM\",\"ciphertext\":\"" + ciphertext
                + "\",\"associated_data\":\"" + originalType + "\",\"nonce\":\"fdasflkja484\"}}";
        return new Notification(signedHeaders(NOW, "NOTIFYNONCE", body, PLATFORM.getPrivate(), PUBLIC_KEY_ID), body);
    }

    record Notification(Map<String, String> headers, String body) {
    }
}
