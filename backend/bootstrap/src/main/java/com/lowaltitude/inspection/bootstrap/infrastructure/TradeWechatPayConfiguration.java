package com.lowaltitude.inspection.bootstrap.infrastructure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.lowaltitude.inspection.trade.application.PaymentChannel;
import com.lowaltitude.inspection.trade.application.PaymentChannelDiagnostics;
import com.lowaltitude.inspection.trade.application.TradeViews.ChannelCheckItemView;
import com.lowaltitude.inspection.trade.infrastructure.wechat.JdkWechatPayTransport;
import com.lowaltitude.inspection.trade.infrastructure.wechat.WechatPayChannel;
import com.lowaltitude.inspection.trade.infrastructure.wechat.WechatPayCredentials;
import com.lowaltitude.inspection.trade.infrastructure.wechat.WechatPaySettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * trade.wechat.enabled=true 时才有支付渠道。没有开就没有 {@link PaymentChannel}，发起支付的接口回“渠道不可用”；
 * 不会退回到任何假渠道。开了但配置不全时启动直接失败，错误里只说缺哪一项，不带密钥内容。
 *
 * <p>指向真实微信（接口域名不是本机回环上的 http）时，启动前先跑 {@link WechatPayPreflight} 的逐项自检（S2-1）：
 * 有一项 FAIL 就不启动，一次列出所有 FAIL；WARN 只记日志。指向本机模拟微信时不跑这一步，只做原有的格式检查。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TradeWechatPayProperties.class)
public class TradeWechatPayConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TradeWechatPayConfiguration.class);

    @Bean
    @ConditionalOnProperty(prefix = "trade.wechat", name = "enabled", havingValue = "true")
    PaymentChannel wechatPayChannel(TradeWechatPayProperties properties) {
        List<ChannelCheckItemView> warnings = WechatPayPreflight.requireStartable(properties);
        for (ChannelCheckItemView warning : warnings) {
            log.warn("wechat pay preflight {}: {}", warning.code(), warning.message());
        }
        return new WechatPayChannel(settings(properties), credentials(properties),
                new JdkWechatPayTransport(properties.connectTimeout(), properties.readTimeout()));
    }

    /** 支付通道自检接口用的配置自检；没开时也有，回答“没有开启”。每次调用都重新读文件，改了文件不用重启就能复查。 */
    @Bean
    PaymentChannelDiagnostics wechatPayDiagnostics(TradeWechatPayProperties properties) {
        return new PaymentChannelDiagnostics() {
            @Override
            public String mode() {
                return WechatPayPreflight.mode(properties);
            }

            @Override
            public List<ChannelCheckItemView> configuration() {
                return WechatPayPreflight.inspect(properties);
            }
        };
    }

    static WechatPaySettings settings(TradeWechatPayProperties properties) {
        return new WechatPaySettings(properties.apiBase(), properties.paymentNotifyUrl(),
                properties.refundNotifyUrl(), properties.signatureTolerance());
    }

    static WechatPayCredentials credentials(TradeWechatPayProperties properties) {
        return new WechatPayCredentials(
                trim(properties.spMchid()),
                trim(properties.spAppid()),
                trim(properties.merchantSerialNo()),
                WechatPayCredentials.parsePrivateKey(readSecretFile(properties.merchantPrivateKeyPath(),
                        "trade.wechat.merchant-private-key-path")),
                trim(properties.platformPublicKeyId()),
                WechatPayCredentials.parsePublicKey(readSecretFile(properties.platformPublicKeyPath(),
                        "trade.wechat.platform-public-key-path")),
                WechatPayCredentials.parseApiV3Key(properties.apiV3Key()));
    }

    private static String readSecretFile(Path path, String property) {
        if (path == null) {
            throw new IllegalStateException(property + " must be configured when wechat pay is enabled");
        }
        try {
            return Files.readString(path, StandardCharsets.US_ASCII);
        } catch (IOException exception) {
            // 只报属性名，不报路径：路径本身也透露服务器布局。
            throw new IllegalStateException(property + " could not be read");
        }
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
