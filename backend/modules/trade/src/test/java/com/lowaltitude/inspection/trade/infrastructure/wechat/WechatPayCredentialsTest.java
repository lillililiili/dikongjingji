package com.lowaltitude.inspection.trade.infrastructure.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

class WechatPayCredentialsTest {

    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + label + "-----\n";
    }

    @Test
    void readsThePemFilesWechatHandsOut() {
        var privateKey = WechatPayCredentials.parsePrivateKey(pem("PRIVATE KEY", FakeWechatPay.MERCHANT.getPrivate().getEncoded()));
        var publicKey = WechatPayCredentials.parsePublicKey(pem("PUBLIC KEY", FakeWechatPay.PLATFORM.getPublic().getEncoded()));

        assertThat(privateKey.getEncoded()).isEqualTo(FakeWechatPay.MERCHANT.getPrivate().getEncoded());
        assertThat(publicKey.getEncoded()).isEqualTo(FakeWechatPay.PLATFORM.getPublic().getEncoded());
    }

    @Test
    void pemWithCarriageReturnsOrSurroundingTextStillParses() {
        String text = "Bag Attributes\r\n" + pem("PRIVATE KEY", FakeWechatPay.MERCHANT.getPrivate().getEncoded()).replace("\n", "\r\n");
        assertThat(WechatPayCredentials.parsePrivateKey(text).getEncoded())
                .isEqualTo(FakeWechatPay.MERCHANT.getPrivate().getEncoded());
    }

    @Test
    void wrongPemTypesAreRefusedWithoutEchoingTheContent() {
        String pkcs1 = pem("RSA PRIVATE KEY", new byte[] {1, 2, 3});
        assertThatThrownBy(() -> WechatPayCredentials.parsePrivateKey(pkcs1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PRIVATE KEY")
                .hasMessageNotContaining("AQID");
        assertThatThrownBy(() -> WechatPayCredentials.parsePublicKey(pem("CERTIFICATE", new byte[] {1})))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WechatPayCredentials.parsePrivateKey(pem("PRIVATE KEY", new byte[] {1, 2, 3})))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PKCS#8");
        assertThatThrownBy(() -> WechatPayCredentials.parsePrivateKey("-----BEGIN PRIVATE KEY-----\n@@@\n-----END PRIVATE KEY-----"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WechatPayCredentials.parsePrivateKey(" "))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("missing");
    }

    @Test
    void theApiV3KeyMustBe32Characters() {
        assertThat(WechatPayCredentials.parseApiV3Key(" 0123456789abcdefABCDEF0123456789\n")).hasSize(32);
        assertThatThrownBy(() -> WechatPayCredentials.parseApiV3Key("short")).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("short");
        assertThatThrownBy(() -> WechatPayCredentials.parseApiV3Key(null)).isInstanceOf(IllegalStateException.class);
        // 32 个汉字是 96 字节，不是 AES-256 的密钥长度。
        assertThatThrownBy(() -> WechatPayCredentials.parseApiV3Key("密".repeat(32))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void identityFieldsAreChecked() {
        assertThatThrownBy(() -> new WechatPayCredentials("abc", FakeWechatPay.SP_APPID, FakeWechatPay.SERIAL,
                FakeWechatPay.MERCHANT.getPrivate(), FakeWechatPay.PUBLIC_KEY_ID, FakeWechatPay.PLATFORM.getPublic(),
                FakeWechatPay.API_V3_KEY)).hasMessageContaining("sp-mchid");
        assertThatThrownBy(() -> new WechatPayCredentials(FakeWechatPay.SP_MCHID, "", FakeWechatPay.SERIAL,
                FakeWechatPay.MERCHANT.getPrivate(), FakeWechatPay.PUBLIC_KEY_ID, FakeWechatPay.PLATFORM.getPublic(),
                FakeWechatPay.API_V3_KEY)).hasMessageContaining("sp-appid");
        assertThatThrownBy(() -> new WechatPayCredentials(FakeWechatPay.SP_MCHID, FakeWechatPay.SP_APPID, "not-hex",
                FakeWechatPay.MERCHANT.getPrivate(), FakeWechatPay.PUBLIC_KEY_ID, FakeWechatPay.PLATFORM.getPublic(),
                FakeWechatPay.API_V3_KEY)).hasMessageContaining("serial");
        // 平台证书序列号不是公钥 ID：一期只接公钥模式。
        assertThatThrownBy(() -> new WechatPayCredentials(FakeWechatPay.SP_MCHID, FakeWechatPay.SP_APPID, FakeWechatPay.SERIAL,
                FakeWechatPay.MERCHANT.getPrivate(), "5157F09EFDC096DE15EBE81A47057A7232F1B8E1", FakeWechatPay.PLATFORM.getPublic(),
                FakeWechatPay.API_V3_KEY)).hasMessageContaining("PUB_KEY_ID_");
        assertThatThrownBy(() -> new WechatPayCredentials(FakeWechatPay.SP_MCHID, FakeWechatPay.SP_APPID, FakeWechatPay.SERIAL,
                FakeWechatPay.MERCHANT.getPrivate(), FakeWechatPay.PUBLIC_KEY_ID, FakeWechatPay.PLATFORM.getPublic(),
                new byte[16])).hasMessageContaining("32 bytes");
    }

    @Test
    void shortRsaKeysAreRefused() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        KeyPair weak = generator.generateKeyPair();

        assertThatThrownBy(() -> new WechatPayCredentials(FakeWechatPay.SP_MCHID, FakeWechatPay.SP_APPID, FakeWechatPay.SERIAL,
                weak.getPrivate(), FakeWechatPay.PUBLIC_KEY_ID, FakeWechatPay.PLATFORM.getPublic(), FakeWechatPay.API_V3_KEY))
                .hasMessageContaining("2048");
        assertThatThrownBy(() -> new WechatPayCredentials(FakeWechatPay.SP_MCHID, FakeWechatPay.SP_APPID, FakeWechatPay.SERIAL,
                FakeWechatPay.MERCHANT.getPrivate(), FakeWechatPay.PUBLIC_KEY_ID, weak.getPublic(), FakeWechatPay.API_V3_KEY))
                .hasMessageContaining("2048");
    }

    @Test
    void toStringNeverPrintsKeysAndTheApiV3KeyIsCopied() {
        byte[] key = FakeWechatPay.API_V3_KEY.clone();
        WechatPayCredentials credentials = new WechatPayCredentials(FakeWechatPay.SP_MCHID, FakeWechatPay.SP_APPID,
                FakeWechatPay.SERIAL, FakeWechatPay.MERCHANT.getPrivate(), FakeWechatPay.PUBLIC_KEY_ID,
                FakeWechatPay.PLATFORM.getPublic(), key);
        key[0] = 'X';

        assertThat(credentials.apiV3Key()).isEqualTo(FakeWechatPay.API_V3_KEY);
        credentials.apiV3Key()[1] = 'Y';
        assertThat(credentials.apiV3Key()).isEqualTo(FakeWechatPay.API_V3_KEY);
        assertThat(credentials.toString())
                .contains(FakeWechatPay.SP_MCHID, "<redacted>")
                .doesNotContain("0123456789abcdef")
                .doesNotContain("Sun RSA")
                .doesNotContain("modulus");
    }

    @Test
    void settingsDefaultToTheMainDomainAndFiveMinutes() {
        WechatPaySettings settings = FakeWechatPay.settings();
        assertThat(settings.apiBase()).isEqualTo(URI.create("https://api.mch.weixin.qq.com"));
        assertThat(settings.signatureTolerance()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void settingsRefuseNotifyUrlsThatWechatWouldRefuse() {
        URI good = URI.create("https://pay.example.test/notify");
        assertThatThrownBy(() -> new WechatPaySettings(null, URI.create("http://pay.example.test/notify"), good, null))
                .hasMessageContaining("payment-notify-url");
        assertThatThrownBy(() -> new WechatPaySettings(null, URI.create("https://pay.example.test/notify?x=1"), good, null))
                .hasMessageContaining("payment-notify-url");
        assertThatThrownBy(() -> new WechatPaySettings(null, good, null, null)).hasMessageContaining("refund-notify-url");
        assertThatThrownBy(() -> new WechatPaySettings(null, good, URI.create("https://pay.example.test/" + "a".repeat(250)), null))
                .hasMessageContaining("refund-notify-url");
        assertThatThrownBy(() -> new WechatPaySettings(URI.create("http://api.mch.weixin.qq.com"), good, good, null))
                .hasMessageContaining("api-base");
        assertThatThrownBy(() -> new WechatPaySettings(URI.create("https://api.mch.weixin.qq.com/v3"), good, good, null))
                .hasMessageContaining("api-base");
        assertThatThrownBy(() -> new WechatPaySettings(null, good, good, Duration.ZERO)).hasMessageContaining("tolerance");
        assertThatThrownBy(() -> new WechatPaySettings(null, good, good, Duration.ofMinutes(16))).hasMessageContaining("tolerance");
        assertThat(new WechatPaySettings(URI.create("https://api2.mch.weixin.qq.com/"), good, good, Duration.ofMinutes(1)).apiBase())
                .hasHost("api2.mch.weixin.qq.com");
    }

    // 开发机上接模拟微信的服务：只有本机回环地址可以用 http。
    @Test
    void plainHttpIsAllowedOnlyOnTheLoopbackAddresses() {
        for (String host : List.of("127.0.0.1", "localhost", "LOCALHOST", "[::1]")) {
            URI api = URI.create("http://" + host + ":18480");
            URI notify = URI.create("http://" + host + ":18420/api/v1/trade/channels/wechat/notifications");

            WechatPaySettings settings = new WechatPaySettings(api, notify, notify, null);

            assertThat(settings.apiBase()).as(host).isEqualTo(api);
            assertThat(settings.paymentNotifyUrl()).as(host).isEqualTo(notify);
        }
        URI good = URI.create("https://pay.example.test/notify");
        for (String host : List.of("127.0.0.2", "10.0.0.1", "192.168.1.10", "localhost.example.test",
                "127.0.0.1.example.test", "[::2]", "0.0.0.0")) {
            assertThatThrownBy(() -> new WechatPaySettings(URI.create("http://" + host), good, good, null))
                    .as(host)
                    .hasMessageContaining("api-base");
            assertThatThrownBy(() -> new WechatPaySettings(null, URI.create("http://" + host + "/notify"), good, null))
                    .as(host)
                    .hasMessageContaining("payment-notify-url");
            assertThatThrownBy(() -> new WechatPaySettings(null, good, URI.create("http://" + host + "/notify"), null))
                    .as(host)
                    .hasMessageContaining("refund-notify-url");
        }
        // 回环地址也只放行 http，其他协议照样拒绝；回调地址的其他规则（不带查询参数）不因回环放松。
        assertThatThrownBy(() -> new WechatPaySettings(URI.create("ftp://127.0.0.1"), good, good, null))
                .hasMessageContaining("api-base");
        assertThatThrownBy(() -> new WechatPaySettings(null, URI.create("http://127.0.0.1/notify?x=1"), good, null))
                .hasMessageContaining("payment-notify-url");
        assertThatThrownBy(() -> new WechatPaySettings(URI.create("http://127.0.0.1/v3"), good, good, null))
                .hasMessageContaining("api-base");
    }
}
