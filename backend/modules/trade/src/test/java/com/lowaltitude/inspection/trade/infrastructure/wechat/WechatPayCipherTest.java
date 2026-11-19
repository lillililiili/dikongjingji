package com.lowaltitude.inspection.trade.infrastructure.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.lowaltitude.inspection.trade.application.PaymentChannelException;
import org.junit.jupiter.api.Test;

class WechatPayCipherTest {

    private static final String PLAIN = "{\"out_trade_no\":\"P20261118000001\",\"trade_state\":\"SUCCESS\",\"trade_state_desc\":\"支付成功\"}";
    private final WechatPayCipher cipher = new WechatPayCipher(FakeWechatPay.API_V3_KEY);

    @Test
    void decryptsWhatAes256GcmEncryptedWithTheSameKeyNonceAndAssociatedData() {
        String sealed = FakeWechatPay.encrypt(FakeWechatPay.API_V3_KEY, "transaction", "fdasflkja484", PLAIN);

        assertThat(cipher.decrypt("transaction", "fdasflkja484", sealed)).isEqualTo(PLAIN);
    }

    @Test
    void theLastSixteenBytesAreTheTag() {
        String sealed = FakeWechatPay.encrypt(FakeWechatPay.API_V3_KEY, "transaction", "fdasflkja484", PLAIN);
        assertThat(Base64.getDecoder().decode(sealed)).hasSize(PLAIN.getBytes(StandardCharsets.UTF_8).length + 16);
    }

    @Test
    void anEmptyAssociatedDataIsAllowed() {
        String sealed = FakeWechatPay.encrypt(FakeWechatPay.API_V3_KEY, "", "abcdefghijkl", PLAIN);

        assertThat(cipher.decrypt("", "abcdefghijkl", sealed)).isEqualTo(PLAIN);
        assertThat(cipher.decrypt(null, "abcdefghijkl", sealed)).isEqualTo(PLAIN);
    }

    @Test
    void aChangedTagOrCiphertextIsRejected() {
        byte[] sealed = Base64.getDecoder().decode(
                FakeWechatPay.encrypt(FakeWechatPay.API_V3_KEY, "transaction", "fdasflkja484", PLAIN));
        byte[] lastByte = sealed.clone();
        lastByte[lastByte.length - 1] ^= 1;
        byte[] firstByte = sealed.clone();
        firstByte[0] ^= 1;

        assertUntrusted(() -> cipher.decrypt("transaction", "fdasflkja484", Base64.getEncoder().encodeToString(lastByte)));
        assertUntrusted(() -> cipher.decrypt("transaction", "fdasflkja484", Base64.getEncoder().encodeToString(firstByte)));
    }

    @Test
    void anotherAssociatedDataNonceOrKeyIsRejected() {
        String sealed = FakeWechatPay.encrypt(FakeWechatPay.API_V3_KEY, "transaction", "fdasflkja484", PLAIN);

        assertUntrusted(() -> cipher.decrypt("refund", "fdasflkja484", sealed));
        assertUntrusted(() -> cipher.decrypt("transaction", "fdasflkja485", sealed));
        WechatPayCipher other = new WechatPayCipher("0123456789abcdefABCDEF012345678X".getBytes(StandardCharsets.UTF_8));
        assertUntrusted(() -> other.decrypt("transaction", "fdasflkja484", sealed));
    }

    @Test
    void incompleteOrMalformedResourcesAreRejected() {
        assertUntrusted(() -> cipher.decrypt("transaction", null, "AAAA"));
        assertUntrusted(() -> cipher.decrypt("transaction", "", "AAAA"));
        assertUntrusted(() -> cipher.decrypt("transaction", "fdasflkja484", null));
        assertUntrusted(() -> cipher.decrypt("transaction", "fdasflkja484", "not base64 !"));
        assertUntrusted(() -> cipher.decrypt("transaction", "fdasflkja484", "AAAA"));
    }

    @Test
    void theKeyIsCopiedSoLaterChangesToTheArrayDoNotMatter() {
        byte[] key = FakeWechatPay.API_V3_KEY.clone();
        WechatPayCipher copy = new WechatPayCipher(key);
        key[0] ^= 1;
        String sealed = FakeWechatPay.encrypt(FakeWechatPay.API_V3_KEY, "transaction", "fdasflkja484", PLAIN);

        assertThat(copy.decrypt("transaction", "fdasflkja484", sealed)).isEqualTo(PLAIN);
    }

    private static void assertUntrusted(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PaymentChannelException.class,
                exception -> assertThat(exception.kind()).isEqualTo(PaymentChannelException.Kind.UNTRUSTED));
    }
}
