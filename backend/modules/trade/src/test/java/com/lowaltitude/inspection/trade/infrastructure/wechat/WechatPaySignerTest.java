package com.lowaltitude.inspection.trade.infrastructure.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.Test;

class WechatPaySignerTest {

    private final FakeWechatPay wechat = new FakeWechatPay();
    private final WechatPaySigner signer = new WechatPaySigner(FakeWechatPay.SP_MCHID, FakeWechatPay.SERIAL,
            FakeWechatPay.MERCHANT.getPrivate(), wechat.clock, () -> "593BEC0C930BF1AFEB40B4A08C8FB242");

    @Test
    void authorizationCarriesTheFiveFieldsAndSignsTheFiveLines() {
        String body = "{\"sp_mchid\":\"1900000001\",\"sub_mchid\":\"1900000101\"}";
        String header = signer.authorization("POST", "/v3/pay/partner/transactions/out-trade-no/P1234567/close", body);

        assertThat(header).startsWith("WECHATPAY2-SHA256-RSA2048 mchid=\"1900000001\",nonce_str=\"593BEC0C930BF1AFEB40B4A08C8FB242\",signature=\"");
        assertThat(header).endsWith(",timestamp=\"" + FakeWechatPay.NOW.getEpochSecond() + "\",serial_no=\"" + FakeWechatPay.SERIAL + "\"");

        String expected = "POST\n/v3/pay/partner/transactions/out-trade-no/P1234567/close\n"
                + FakeWechatPay.NOW.getEpochSecond() + "\n593BEC0C930BF1AFEB40B4A08C8FB242\n" + body + "\n";
        String signature = header.replaceAll(".*signature=\"([^\"]+)\".*", "$1");
        assertThat(FakeWechatPay.verify(FakeWechatPay.MERCHANT.getPublic(), expected, signature)).isTrue();
    }

    @Test
    void aGetWithAQuerySignsThePathWithItsQueryAndAnEmptyBodyLine() {
        String path = "/v3/pay/partner/transactions/out-trade-no/P1234567?sp_mchid=1900000001&sub_mchid=1900000101";
        String header = signer.authorization("GET", path, null);

        Map<String, String> fields = FakeWechatPay.checkAuthorization(new WechatPayTransport.Request(
                "GET", URI.create("https://api.mch.weixin.qq.com" + path), Map.of("Authorization", header), null));
        assertThat(fields).containsEntry("mchid", "1900000001").containsEntry("serial_no", FakeWechatPay.SERIAL);

        String expected = "GET\n" + path + "\n" + FakeWechatPay.NOW.getEpochSecond() + "\n593BEC0C930BF1AFEB40B4A08C8FB242\n\n";
        assertThat(FakeWechatPay.verify(FakeWechatPay.MERCHANT.getPublic(), expected, fields.get("signature"))).isTrue();
    }

    @Test
    void theSignatureCoversEveryByteOfTheBody() {
        String header = signer.authorization("POST", "/v3/ecommerce/refunds/apply", "{\"amount\":{\"refund\":100}}");

        assertThatThrownBy(() -> FakeWechatPay.checkAuthorization(new WechatPayTransport.Request("POST",
                URI.create("https://api.mch.weixin.qq.com/v3/ecommerce/refunds/apply"),
                Map.of("Authorization", header), "{\"amount\":{\"refund\":1000}}")))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void appPaySignatureCoversAppIdTimestampNonceAndPrepayId() {
        WechatPaySigner.AppSignature signature = signer.signAppPay(FakeWechatPay.SP_APPID, "wx201410272009395522657a690389285100");

        assertThat(signature.timeStamp()).isEqualTo(Long.toString(FakeWechatPay.NOW.getEpochSecond())).hasSize(10);
        assertThat(signature.nonceStr()).isEqualTo("593BEC0C930BF1AFEB40B4A08C8FB242");
        String expected = FakeWechatPay.SP_APPID + "\n" + signature.timeStamp() + "\n" + signature.nonceStr()
                + "\nwx201410272009395522657a690389285100\n";
        assertThat(FakeWechatPay.verify(FakeWechatPay.MERCHANT.getPublic(), expected, signature.sign())).isTrue();
        assertThat(FakeWechatPay.verify(FakeWechatPay.MERCHANT.getPublic(), expected.replace("wx2014", "wx2015"), signature.sign()))
                .isFalse();
    }

    @Test
    void randomNoncesAre32UpperHexAndDoNotRepeat() {
        var nonces = WechatPayChannel.randomNonces();
        String first = nonces.get();
        assertThat(first).matches("^[0-9A-F]{32}$");
        assertThat(nonces.get()).isNotEqualTo(first);
    }
}
