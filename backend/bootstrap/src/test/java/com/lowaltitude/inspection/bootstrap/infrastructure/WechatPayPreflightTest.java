package com.lowaltitude.inspection.bootstrap.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.trade.application.TradeViews.ChannelCheckItemView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 微信支付配置的逐项自检（S2-1）：一次列出全部问题；消息里不带密钥、文件内容和路径；只在真实微信模式拦启动。
 */
class WechatPayPreflightTest {

    // 测试时现生成的密钥，不是任何真实账户的。
    private static final String API_V3_KEY = "0123456789abcdefABCDEF0123456789";
    private static final URI CALLBACK = URI.create("https://pay.example.test/api/v1/trade/channels/wechat/notifications");

    @TempDir
    static Path secrets;

    private static Path privateKey;
    private static Path publicKey;
    private static Path merchantPublicKey;
    private static Path certificate;
    private static boolean posixPermissions;

    @BeforeAll
    static void writeKeys() throws NoSuchAlgorithmException, IOException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair merchant = generator.generateKeyPair();
        KeyPair platform = generator.generateKeyPair();
        privateKey = Files.writeString(secrets.resolve("apiclient_key.pem"), pem("PRIVATE KEY", merchant.getPrivate().getEncoded()));
        posixPermissions = Files.getFileStore(privateKey).supportsFileAttributeView("posix");
        if (posixPermissions) {
            Files.setPosixFilePermissions(privateKey, PosixFilePermissions.fromString("r--------"));
        }
        publicKey = Files.writeString(secrets.resolve("pub_key.pem"), pem("PUBLIC KEY", platform.getPublic().getEncoded()));
        merchantPublicKey = Files.writeString(secrets.resolve("merchant_pub.pem"),
                pem("PUBLIC KEY", merchant.getPublic().getEncoded()));
        certificate = Files.writeString(secrets.resolve("apiclient_cert.pem"), pem("CERTIFICATE", new byte[] {1, 2, 3}));
    }

    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END " + label + "-----\n";
    }

    private static TradeWechatPayProperties properties(URI apiBase, String mchid, Path key, String apiV3, Path pub,
                                                       URI notify) {
        return new TradeWechatPayProperties(true, mchid, "wxd678efh567hg6787", "5157F09EFDC096DE15EBE81A47057A7232F1B8E1",
                key, apiV3, "PUB_KEY_ID_0114232134912410000000000000", pub, apiBase, notify, notify, null, null, null);
    }

    private static Map<String, String> statuses(List<ChannelCheckItemView> items) {
        return items.stream().collect(Collectors.toMap(ChannelCheckItemView::code, ChannelCheckItemView::status));
    }

    @Test
    void aCompleteRealConfigurationPassesEveryItem() {
        TradeWechatPayProperties complete = properties(null, "1900000001", privateKey, API_V3_KEY, publicKey, CALLBACK);

        List<ChannelCheckItemView> items = WechatPayPreflight.inspect(complete);

        assertThat(WechatPayPreflight.mode(complete)).isEqualTo("WECHAT");
        assertThat(statuses(items)).containsOnlyKeys("ENABLED", "API_BASE", "SP_MCHID", "SP_APPID", "MERCHANT_SERIAL_NO",
                "MERCHANT_PRIVATE_KEY", "MERCHANT_PRIVATE_KEY_FILE", "API_V3_KEY", "PLATFORM_PUBLIC_KEY_ID",
                "PLATFORM_PUBLIC_KEY", "KEYS_DISTINCT", "PAYMENT_NOTIFY_URL", "REFUND_NOTIFY_URL");
        Map<String, String> results = statuses(items);
        assertThat(results.remove("MERCHANT_PRIVATE_KEY_FILE")).isEqualTo(posixPermissions ? "PASS" : "SKIPPED");
        assertThat(results.values()).containsOnly("PASS");
        assertThat(WechatPayPreflight.requireStartable(complete)).isEmpty();
    }

    @Test
    void everyProblemIsListedAtOnceWithoutSecretsOrPaths() {
        TradeWechatPayProperties broken = properties(URI.create("https://api.mch.weixin.qq.com"), "19000x",
                certificate, "too-short-secret", merchantPublicKey, URI.create("https://10.0.0.8/notify"));

        Map<String, String> statuses = statuses(WechatPayPreflight.inspect(broken));

        assertThat(statuses).containsEntry("SP_MCHID", "FAIL")
                .containsEntry("MERCHANT_PRIVATE_KEY", "FAIL")
                .containsEntry("API_V3_KEY", "FAIL")
                .containsEntry("PLATFORM_PUBLIC_KEY", "PASS")
                .containsEntry("PAYMENT_NOTIFY_URL", "FAIL")
                .doesNotContainKey("KEYS_DISTINCT");
        assertThatThrownBy(() -> WechatPayPreflight.requireStartable(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("共 5 项")
                .hasMessageContaining("trade.wechat.api-v3-key")
                .hasMessageContaining("trade.wechat.merchant-private-key-path")
                .message()
                .doesNotContain("too-short-secret")
                .doesNotContain(secrets.toString());
    }

    @Test
    void thePlatformKeySwappedForTheMerchantsOwnPublicKeyIsCaught() {
        TradeWechatPayProperties swapped = properties(null, "1900000001", privateKey, API_V3_KEY, merchantPublicKey, CALLBACK);

        assertThat(statuses(WechatPayPreflight.inspect(swapped))).containsEntry("KEYS_DISTINCT", "FAIL");
    }

    @Test
    void doubtfulValuesOnlyWarn() throws IOException {
        Path open = Files.copy(privateKey, secrets.resolve("open_key.pem"));
        if (posixPermissions) {
            Files.setPosixFilePermissions(open, PosixFilePermissions.fromString("rw-r--r--"));
        }
        TradeWechatPayProperties doubtful = properties(URI.create("https://proxy.example.test"), "190000000123", open,
                API_V3_KEY, publicKey, URI.create("https://pay.example.test/wechat/notify"));

        Map<String, String> statuses = statuses(WechatPayPreflight.inspect(doubtful));

        assertThat(statuses).containsEntry("API_BASE", "WARN")
                .containsEntry("SP_MCHID", "WARN")
                .containsEntry("MERCHANT_PRIVATE_KEY_FILE", posixPermissions ? "WARN" : "SKIPPED")
                .containsEntry("PAYMENT_NOTIFY_URL", "WARN");
        var warnings = WechatPayPreflight.requireStartable(doubtful).stream().map(ChannelCheckItemView::code).toList();
        assertThat(warnings).contains("API_BASE", "SP_MCHID", "PAYMENT_NOTIFY_URL");
        assertThat(warnings.contains("MERCHANT_PRIVATE_KEY_FILE")).isEqualTo(posixPermissions);
    }

    @Test
    void theLocalFakeIsNotStoppedAtStartupButIsReportedAsSimulated() {
        TradeWechatPayProperties fake = properties(URI.create("http://127.0.0.1:17460"), "1900000001", privateKey,
                API_V3_KEY, publicKey, URI.create("http://127.0.0.1:8080/api/v1/trade/channels/wechat/notifications"));

        assertThat(WechatPayPreflight.simulated(fake)).isTrue();
        assertThat(WechatPayPreflight.mode(fake)).isEqualTo("SIMULATED");
        assertThat(WechatPayPreflight.requireStartable(fake)).isEmpty();
        assertThat(statuses(WechatPayPreflight.inspect(fake))).containsEntry("ENABLED", "WARN")
                .containsEntry("PAYMENT_NOTIFY_URL", "WARN")
                .doesNotContainKey("MERCHANT_PRIVATE_KEY_FILE")
                .doesNotContainValue("FAIL");
    }

    @Test
    void aDisabledChannelSaysSoAndNothingElse() {
        TradeWechatPayProperties disabled = new TradeWechatPayProperties(false, null, null, null, null, null, null, null,
                null, null, null, null, null, null);

        assertThat(WechatPayPreflight.mode(disabled)).isEqualTo("DISABLED");
        assertThat(WechatPayPreflight.inspect(disabled)).extracting(ChannelCheckItemView::code).containsExactly("ENABLED");
        assertThat(WechatPayPreflight.requireStartable(disabled)).isEmpty();
    }
}
