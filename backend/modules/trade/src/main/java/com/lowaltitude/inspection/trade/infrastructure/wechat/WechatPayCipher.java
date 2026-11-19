package com.lowaltitude.inspection.trade.infrastructure.wechat;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.lowaltitude.inspection.trade.application.PaymentChannelException;
import com.lowaltitude.inspection.trade.application.PaymentChannelException.Kind;

/**
 * 解密回调里的 resource（AEAD_AES_256_GCM）：密钥是 APIv3 密钥，IV 是 resource.nonce 的字节，
 * 附加数据是 resource.associated_data（可能为空），密文 Base64 解码后末尾 16 字节是认证标签。
 */
final class WechatPayCipher {

    static final String ALGORITHM = "AEAD_AES_256_GCM";
    private static final int TAG_BITS = 128;

    private final byte[] key;

    WechatPayCipher(byte[] apiV3Key) {
        this.key = apiV3Key.clone();
    }

    String decrypt(String associatedData, String nonce, String ciphertext) {
        if (nonce == null || nonce.isEmpty() || ciphertext == null || ciphertext.isEmpty()) {
            throw untrusted("encrypted resource is incomplete");
        }
        byte[] sealed;
        try {
            sealed = Base64.getDecoder().decode(ciphertext);
        } catch (IllegalArgumentException exception) {
            throw untrusted("ciphertext is not Base64");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce.getBytes(StandardCharsets.UTF_8)));
            if (associatedData != null && !associatedData.isEmpty()) {
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            }
            return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            // 标签不对：密钥错了，或报文被改过。
            throw untrusted("resource could not be decrypted");
        }
    }

    private static PaymentChannelException untrusted(String message) {
        return new PaymentChannelException(Kind.UNTRUSTED, "DECRYPT", 0, "wechat pay " + message);
    }
}
