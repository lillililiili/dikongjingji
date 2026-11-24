package com.lowaltitude.inspection.identity.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Only the server can compare the original application, including its one-time credentials. */
final class SignupRequestSignature {
    private static final String ALGORITHM = "HmacSHA256";
    private final SecretKeySpec key;

    SignupRequestSignature(String dataKey) {
        byte[] root = Base64.getDecoder().decode(dataKey);
        Mac derive = mac(new SecretKeySpec(root, ALGORITHM));
        byte[] purpose = "identity.organization-signup.v1".getBytes(StandardCharsets.UTF_8);
        this.key = new SecretKeySpec(derive.doFinal(purpose), ALGORITHM);
    }

    String sign(UUID requestId, String... fields) {
        Mac signature = mac(key);
        signature.update(requestId.toString().getBytes(StandardCharsets.US_ASCII));
        for (String field : fields) {
            byte[] bytes = field == null ? new byte[0] : field.getBytes(StandardCharsets.UTF_8);
            signature.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            signature.update(bytes);
        }
        return HexFormat.of().formatHex(signature.doFinal());
    }

    private static Mac mac(SecretKeySpec key) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac;
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Cannot sign organization signup requests", failure);
        }
    }
}
