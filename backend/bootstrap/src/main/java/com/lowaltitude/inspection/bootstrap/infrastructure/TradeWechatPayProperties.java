package com.lowaltitude.inspection.bootstrap.infrastructure;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 微信支付配置（trade.wechat.*），全部从服务器环境变量或密钥文件来，仓库里只有占位。
 *
 * <p>两个私密文件（服务商 API 证书私钥、微信支付公钥）只给路径，文件放在服务器上、只让运行后端的用户可读；
 * APIv3 密钥走环境变量。toString 不输出 APIv3 密钥。
 */
@ConfigurationProperties(prefix = "trade.wechat")
public record TradeWechatPayProperties(
        boolean enabled,
        String spMchid,
        String spAppid,
        String merchantSerialNo,
        Path merchantPrivateKeyPath,
        String apiV3Key,
        String platformPublicKeyId,
        Path platformPublicKeyPath,
        URI apiBase,
        URI paymentNotifyUrl,
        URI refundNotifyUrl,
        Duration signatureTolerance,
        Duration connectTimeout,
        Duration readTimeout
) {

    public TradeWechatPayProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
    }

    @Override
    public String toString() {
        return "TradeWechatPayProperties[enabled=" + enabled + ", spMchid=" + spMchid + ", spAppid=" + spAppid
                + ", platformPublicKeyId=" + platformPublicKeyId + ", apiV3Key=<redacted>]";
    }
}
