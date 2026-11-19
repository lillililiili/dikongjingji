package com.lowaltitude.inspection.trade.infrastructure.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.lowaltitude.inspection.trade.application.PaymentChannelException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WechatPayVerifierTest {

    private static final String BODY = "{\"prepay_id\":\"wx26112221580621e9b071c00d9e093b0000\"}";

    private final WechatPayVerifier verifier = new WechatPayVerifier(FakeWechatPay.PUBLIC_KEY_ID,
            FakeWechatPay.PLATFORM.getPublic(), new FakeWechatPay().clock, Duration.ofMinutes(5));

    private static Map<String, String> signed(Instant at, String body) {
        return WechatPayTransport.caseInsensitive(FakeWechatPay.signedHeaders(
                at, "C5AC0D3E2A4F4C2E9F1E", body, FakeWechatPay.PLATFORM.getPrivate(), FakeWechatPay.PUBLIC_KEY_ID));
    }

    @Test
    void aBodySignedByThePlatformKeyIsAccepted() {
        assertThatCode(() -> verifier.verify(signed(FakeWechatPay.NOW, BODY), BODY)).doesNotThrowAnyException();
    }

    @Test
    void anEmptyBodyIsSignedAsAnEmptyLine() {
        assertThatCode(() -> verifier.verify(signed(FakeWechatPay.NOW, ""), "")).doesNotThrowAnyException();
        assertThatCode(() -> verifier.verify(signed(FakeWechatPay.NOW, ""), null)).doesNotThrowAnyException();
    }

    @Test
    void headerNamesAreMatchedWithoutCase() {
        Map<String, String> lower = new HashMap<>();
        FakeWechatPay.signedHeaders(FakeWechatPay.NOW, "N1", BODY, FakeWechatPay.PLATFORM.getPrivate(), FakeWechatPay.PUBLIC_KEY_ID)
                .forEach((name, value) -> lower.put(name.toLowerCase(), value));

        assertThatCode(() -> verifier.verify(WechatPayTransport.caseInsensitive(lower), BODY)).doesNotThrowAnyException();
    }

    @Test
    void aChangedBodyIsRejected() {
        assertUntrusted(signed(FakeWechatPay.NOW, BODY), BODY.replace("wx26", "wx27"));
        assertUntrusted(signed(FakeWechatPay.NOW, BODY), BODY + " ");
    }

    @Test
    void aSignatureFromAnotherKeyIsRejectedEvenWithTheRightSerial() {
        Map<String, String> headers = WechatPayTransport.caseInsensitive(FakeWechatPay.signedHeaders(
                FakeWechatPay.NOW, "N1", BODY, FakeWechatPay.MERCHANT.getPrivate(), FakeWechatPay.PUBLIC_KEY_ID));
        assertUntrusted(headers, BODY);
    }

    @Test
    void anotherSerialIsRejectedEvenIfTheSignatureWouldVerify() {
        // 平台证书序列号、别的公钥 ID：一期只认配置的那一把公钥。
        for (String serial : new String[] {"PUB_KEY_ID_0000000000000000000000000001", "5157F09EFDC096DE15EBE81A47057A7232F1B8E1"}) {
            Map<String, String> headers = WechatPayTransport.caseInsensitive(FakeWechatPay.signedHeaders(
                    FakeWechatPay.NOW, "N1", BODY, FakeWechatPay.PLATFORM.getPrivate(), serial));
            assertUntrusted(headers, BODY);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Wechatpay-Timestamp", "Wechatpay-Nonce", "Wechatpay-Signature", "Wechatpay-Serial"})
    void everySignatureHeaderIsRequired(String missing) {
        Map<String, String> headers = new HashMap<>(signed(FakeWechatPay.NOW, BODY));
        headers.keySet().removeIf(name -> name.equalsIgnoreCase(missing));
        assertUntrusted(WechatPayTransport.caseInsensitive(headers), BODY);

        headers.put(missing, " ");
        assertUntrusted(WechatPayTransport.caseInsensitive(headers), BODY);
    }

    @Test
    void timestampsWithinFiveMinutesEitherWayAreAccepted() {
        for (Duration offset : new Duration[] {Duration.ofMinutes(-5), Duration.ofMinutes(4), Duration.ofMinutes(5)}) {
            Instant at = FakeWechatPay.NOW.plus(offset);
            assertThatCode(() -> verifier.verify(signed(at, BODY), BODY)).as(offset.toString()).doesNotThrowAnyException();
        }
    }

    @Test
    void oldOrFarFutureTimestampsAreRejected() {
        assertUntrusted(signed(FakeWechatPay.NOW.minusSeconds(301), BODY), BODY);
        assertUntrusted(signed(FakeWechatPay.NOW.plusSeconds(301), BODY), BODY);
    }

    @Test
    void aTimestampThatIsNotANumberIsRejected() {
        Map<String, String> headers = new HashMap<>(signed(FakeWechatPay.NOW, BODY));
        headers.put("Wechatpay-Timestamp", "2026-11-18");
        assertUntrusted(WechatPayTransport.caseInsensitive(headers), BODY);
    }

    @Test
    void signatureProbesAreVerifiedLikeAnythingElseAndFail() {
        Map<String, String> headers = new HashMap<>(signed(FakeWechatPay.NOW, BODY));
        headers.put("Wechatpay-Signature", "WECHATPAY/SIGNTEST/9dahdfs0dshasdsd");
        assertUntrusted(WechatPayTransport.caseInsensitive(headers), BODY);
    }

    @Test
    void aSignatureThatIsNotBase64IsRejected() {
        Map<String, String> headers = new HashMap<>(signed(FakeWechatPay.NOW, BODY));
        headers.put("Wechatpay-Signature", "%%%");
        assertUntrusted(WechatPayTransport.caseInsensitive(headers), BODY);
    }

    @Test
    void theToleranceIsConfigurable() {
        WechatPayVerifier strict = new WechatPayVerifier(FakeWechatPay.PUBLIC_KEY_ID, FakeWechatPay.PLATFORM.getPublic(),
                Clock.fixed(FakeWechatPay.NOW, java.time.ZoneOffset.UTC), Duration.ofSeconds(30));
        assertThatThrownBy(() -> strict.verify(signed(FakeWechatPay.NOW.minusSeconds(31), BODY), BODY))
                .isInstanceOf(PaymentChannelException.class);
    }

    @Test
    void hasSignatureHeadersSeesAnyOfTheFour() {
        assertThat(WechatPayVerifier.hasSignatureHeaders(WechatPayTransport.caseInsensitive(Map.of()))).isFalse();
        assertThat(WechatPayVerifier.hasSignatureHeaders(WechatPayTransport.caseInsensitive(Map.of("wechatpay-nonce", "x"))))
                .isTrue();
    }

    private void assertUntrusted(Map<String, String> headers, String body) {
        assertThatThrownBy(() -> verifier.verify(headers, body))
                .isInstanceOfSatisfying(PaymentChannelException.class, exception -> {
                    assertThat(exception.kind()).isEqualTo(PaymentChannelException.Kind.UNTRUSTED);
                    assertThat(exception.code()).isEqualTo("SIGNATURE");
                });
    }
}
