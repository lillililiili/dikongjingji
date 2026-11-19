package com.lowaltitude.inspection.bootstrap.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import com.lowaltitude.inspection.trade.application.PaymentChannel;
import com.lowaltitude.inspection.trade.infrastructure.wechat.WechatPayChannel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TradeWechatPayConfigurationTest {

    // 测试时现生成的密钥，不是任何真实账户的。
    private static final String API_V3_KEY = "0123456789abcdefABCDEF0123456789";

    @TempDir
    static Path secrets;

    private static Path privateKey;
    private static Path publicKey;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TradeWechatPayConfiguration.class);

    @BeforeAll
    static void writeKeys() throws NoSuchAlgorithmException, IOException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair merchant = generator.generateKeyPair();
        KeyPair platform = generator.generateKeyPair();
        privateKey = Files.writeString(secrets.resolve("apiclient_key.pem"), pem("PRIVATE KEY", merchant.getPrivate().getEncoded()));
        publicKey = Files.writeString(secrets.resolve("pub_key.pem"), pem("PUBLIC KEY", platform.getPublic().getEncoded()));
    }

    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END " + label + "-----\n";
    }

    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues(
                "trade.wechat.enabled=true",
                "trade.wechat.sp-mchid=1900000001",
                "trade.wechat.sp-appid=wxd678efh567hg6787",
                "trade.wechat.merchant-serial-no=5157F09EFDC096DE15EBE81A47057A7232F1B8E1",
                "trade.wechat.merchant-private-key-path=" + privateKey,
                "trade.wechat.api-v3-key=" + API_V3_KEY,
                "trade.wechat.platform-public-key-id=PUB_KEY_ID_0114232134912410000000000000",
                "trade.wechat.platform-public-key-path=" + publicKey,
                "trade.wechat.payment-notify-url=https://pay.example.test/api/v1/trade/channels/wechat/notifications",
                "trade.wechat.refund-notify-url=https://pay.example.test/api/v1/trade/channels/wechat/notifications");
    }

    @Test
    void thereIsNoChannelUnlessItIsTurnedOn() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PaymentChannel.class);
        });
        runner.withPropertyValues("trade.wechat.enabled=false").run(context ->
                assertThat(context).doesNotHaveBean(PaymentChannel.class));
    }

    @Test
    void aCompleteConfigurationBuildsTheWechatChannel() {
        enabled().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PaymentChannel.class);
            assertThat(context.getBean(PaymentChannel.class)).isInstanceOf(WechatPayChannel.class);
            assertThat(context.getBean(TradeWechatPayProperties.class).toString())
                    .contains("<redacted>")
                    .doesNotContain(API_V3_KEY);
        });
    }

    @Test
    void aMissingKeyFileStopsStartupNamingOnlyTheProperty() {
        enabled().withPropertyValues("trade.wechat.merchant-private-key-path=" + secrets.resolve("missing.pem")).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure()))
                    .contains("trade.wechat.merchant-private-key-path")
                    .doesNotContain(secrets.toString());
        });
    }

    @Test
    void aWrongApiV3KeyStopsStartupWithoutEchoingIt() {
        enabled().withPropertyValues("trade.wechat.api-v3-key=too-short-secret").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure()))
                    .contains("api-v3-key")
                    .doesNotContain("too-short-secret");
        });
    }

    @Test
    void anHttpNotifyUrlStopsStartup() {
        enabled().withPropertyValues("trade.wechat.payment-notify-url=http://pay.example.test/notify").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("payment-notify-url");
        });
    }

    @Test
    void aPlatformCertificateSerialInsteadOfAPublicKeyIdStopsStartup() {
        enabled().withPropertyValues("trade.wechat.platform-public-key-id=5157F09EFDC096DE15EBE81A47057A7232F1B8E1").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("PUB_KEY_ID_");
        });
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
