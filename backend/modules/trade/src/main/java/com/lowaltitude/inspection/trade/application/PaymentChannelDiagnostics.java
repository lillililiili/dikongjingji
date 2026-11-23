package com.lowaltitude.inspection.trade.application;

import java.util.List;

import com.lowaltitude.inspection.trade.application.TradeViews.ChannelCheckItemView;

/**
 * 支付渠道的配置自检（S2-1）。由接渠道的一方（启动模块的微信支付配置）实现：它知道配置从哪来、该长什么样。
 * 只检查配置本身，不发请求；向渠道发无副作用请求的部分在 {@link TradeChannelCheckService}。
 */
public interface PaymentChannelDiagnostics {

    String MODE_WECHAT = "WECHAT";
    String MODE_SIMULATED = "SIMULATED";
    String MODE_DISABLED = "DISABLED";

    /** WECHAT、SIMULATED 或 DISABLED。 */
    String mode();

    /** 逐项结果；消息里不带密钥、文件内容和服务器路径。 */
    List<ChannelCheckItemView> configuration();

    /** 没有接任何渠道配置时用：只说“没有开启”。 */
    static PaymentChannelDiagnostics none() {
        return new PaymentChannelDiagnostics() {
            @Override
            public String mode() {
                return MODE_DISABLED;
            }

            @Override
            public List<ChannelCheckItemView> configuration() {
                return List.of(new ChannelCheckItemView("ENABLED", TradeChannelCheckService.FAIL, "支付渠道没有开启"));
            }
        };
    }
}
