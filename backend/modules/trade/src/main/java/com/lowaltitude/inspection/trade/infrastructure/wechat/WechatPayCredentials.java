package com.lowaltitude.inspection.trade.infrastructure.wechat;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * 服务商的身份和密钥。只从服务器的密钥配置读进来，不进代码、仓库和客户端；toString 不输出任何密钥。
 *
 * @param spMchid             服务商户号（请求签名的 mchid、下单的 sp_mchid）
 * @param spAppid             服务商在开放平台的移动应用 APPID（下单的 sp_appid、App 调起的 appId）
 * @param merchantSerialNo    服务商 API 证书序列号（Authorization 里的 serial_no）
 * @param merchantPrivateKey  服务商 API 证书私钥（apiclient_key.pem，PKCS#8）：签请求，也签 App 调起参数
 * @param platformPublicKeyId 微信支付公钥 ID（PUB_KEY_ID_ 开头），应答和回调的 Wechatpay-Serial 必须等于它
 * @param platformPublicKey   微信支付公钥（pub_key.pem）：验应答和回调的签名
 * @param apiV3Key            APIv3 密钥，32 字节：解密回调
 */
public record WechatPayCredentials(
        String spMchid,
        String spAppid,
        String merchantSerialNo,
        PrivateKey merchantPrivateKey,
        String platformPublicKeyId,
        PublicKey platformPublicKey,
        byte[] apiV3Key
) {

    private static final int MIN_RSA_BITS = 2048;

    public WechatPayCredentials {
        require(spMchid != null && spMchid.matches("^[0-9]{8,32}$"), "sp-mchid must be the numeric service provider id");
        require(spAppid != null && spAppid.matches("^[0-9A-Za-z_]{6,32}$"), "sp-appid is invalid");
        require(merchantSerialNo != null && merchantSerialNo.matches("^[0-9A-Fa-f]{8,64}$"),
                "merchant-serial-no must be the hex serial of the API certificate");
        require(merchantPrivateKey instanceof RSAPrivateCrtKey key && key.getModulus().bitLength() >= MIN_RSA_BITS,
                "merchant private key must be RSA 2048 or longer");
        require(platformPublicKeyId != null && platformPublicKeyId.matches("^PUB_KEY_ID_[0-9A-Za-z]{1,64}$"),
                "platform-public-key-id must look like PUB_KEY_ID_...");
        require(platformPublicKey instanceof RSAPublicKey key && key.getModulus().bitLength() >= MIN_RSA_BITS,
                "platform public key must be RSA 2048 or longer");
        require(apiV3Key != null && apiV3Key.length == 32, "api-v3-key must be 32 bytes");
        apiV3Key = apiV3Key.clone();
    }

    @Override
    public byte[] apiV3Key() {
        return apiV3Key.clone();
    }

    /** 读 PKCS#8 PEM（-----BEGIN PRIVATE KEY-----），这是微信给的 apiclient_key.pem 的格式。 */
    public static PrivateKey parsePrivateKey(String pem) {
        byte[] der = pemBody(pem, "PRIVATE KEY", "merchant private key");
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("merchant private key is not an RSA PKCS#8 key");
        } finally {
            Arrays.fill(der, (byte) 0);
        }
    }

    /** 读 X.509 PEM（-----BEGIN PUBLIC KEY-----），这是微信支付公钥 pub_key.pem 的格式。 */
    public static PublicKey parsePublicKey(String pem) {
        byte[] der = pemBody(pem, "PUBLIC KEY", "platform public key");
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("platform public key is not an RSA public key");
        }
    }

    /** APIv3 密钥是商户平台上设置的 32 个字符，按 UTF-8 取字节作 AES-256 密钥。 */
    public static byte[] parseApiV3Key(String text) {
        byte[] key = text == null ? new byte[0] : text.trim().getBytes(StandardCharsets.UTF_8);
        require(key.length == 32, "api-v3-key must be 32 characters");
        return key;
    }

    private static byte[] pemBody(String pem, String label, String what) {
        require(pem != null && !pem.isBlank(), what + " is missing");
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        int from = pem.indexOf(begin);
        int to = pem.indexOf(end);
        if (from < 0 || to < from) {
            // 常见的错：给了 PKCS#1（BEGIN RSA PRIVATE KEY）或证书文件。提示格式，不回显内容。
            throw new IllegalStateException(what + " must be a PEM block of type " + label);
        }
        String base64 = pem.substring(from + begin.length(), to).replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(what + " PEM body is not Base64");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public String toString() {
        return "WechatPayCredentials[spMchid=" + spMchid + ", spAppid=" + spAppid
                + ", platformPublicKeyId=" + platformPublicKeyId + ", keys=<redacted>]";
    }
}
