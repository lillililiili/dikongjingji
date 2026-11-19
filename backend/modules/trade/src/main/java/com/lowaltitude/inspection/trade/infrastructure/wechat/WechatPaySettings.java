package com.lowaltitude.inspection.trade.infrastructure.wechat;

import java.net.URI;
import java.time.Duration;

/**
 * 微信支付的非保密配置。
 *
 * @param apiBase            接口域名，默认 https://api.mch.weixin.qq.com（备用 https://api2.mch.weixin.qq.com）
 * @param paymentNotifyUrl   支付回调地址：外网可访问的 HTTPS，不能带查询参数
 * @param refundNotifyUrl    退款回调地址，规则同上（可以和支付回调同一个地址，按事件类型区分）
 * @param signatureTolerance 应答和回调的签名时间与本机时间最多差多少；文档没给值，先定 5 分钟，联调时确认
 *
 * <p>地址都必须是 https。唯一的例外是本机回环（127.0.0.1、localhost、[::1]）上的 http：只用于在开发机上接一个
 * 模拟微信的服务把整条链路跑通，任何外部主机都不会走到这条路。
 */
public record WechatPaySettings(URI apiBase, URI paymentNotifyUrl, URI refundNotifyUrl, Duration signatureTolerance) {

    public static final URI DEFAULT_API_BASE = URI.create("https://api.mch.weixin.qq.com");
    public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

    public WechatPaySettings {
        apiBase = apiBase == null ? DEFAULT_API_BASE : apiBase;
        signatureTolerance = signatureTolerance == null ? DEFAULT_TOLERANCE : signatureTolerance;
        require(secure(apiBase)
                && (apiBase.getPath() == null || apiBase.getPath().isEmpty() || "/".equals(apiBase.getPath())),
                "api-base must be an https origin");
        requireNotifyUrl(paymentNotifyUrl, "payment-notify-url");
        requireNotifyUrl(refundNotifyUrl, "refund-notify-url");
        require(!signatureTolerance.isNegative() && !signatureTolerance.isZero()
                && signatureTolerance.compareTo(Duration.ofMinutes(15)) <= 0,
                "signature-tolerance must be between 1 second and 15 minutes");
    }

    private static void requireNotifyUrl(URI url, String name) {
        require(url != null && secure(url) && url.getRawQuery() == null
                && url.getRawFragment() == null && url.toString().length() <= 255,
                name + " must be an https url without query, at most 255 characters");
    }

    private static boolean secure(URI url) {
        String host = url.getHost();
        if (host == null) {
            return false;
        }
        if ("https".equals(url.getScheme())) {
            return true;
        }
        return "http".equals(url.getScheme())
                && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "[::1]".equals(host));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
