package com.lowaltitude.inspection.trade.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.trade.application.PaymentChannel.PaymentQuery;
import com.lowaltitude.inspection.trade.application.PaymentChannel.PaymentState;
import com.lowaltitude.inspection.trade.application.PaymentChannel.RefundQuery;
import com.lowaltitude.inspection.trade.application.PaymentChannel.RefundState;
import com.lowaltitude.inspection.trade.application.TradeViews.ChannelCheckItemView;
import com.lowaltitude.inspection.trade.application.TradeViews.ChannelCheckView;

/**
 * 支付通道自检（S2-1，平台运营）：先列配置自检的结果，再向渠道发两个没有副作用的请求——
 * 按一个不存在的商户订单号查单、按一个不存在的退款单号查退款。渠道验过签地回答“没有这一单”，
 * 就说明请求签名（服务商号、证书序列号、私钥）、网络出口、应答验签（微信支付公钥和公钥 ID）都对，
 * 子商户也和服务商绑定了。
 *
 * <p>不下单、不关单、不退款、不写数据库，可以随时重复调用。自检单号以 CHK 开头，平台自己的单号不会这样开头。
 */
public class TradeChannelCheckService {

    public static final String PASS = "PASS";
    public static final String WARN = "WARN";
    public static final String FAIL = "FAIL";
    public static final String SKIPPED = "SKIPPED";

    static final String PROBE_PREFIX = "CHK";
    private static final Pattern SUB_MCHID = Pattern.compile("^[0-9]{8,32}$");

    private final Supplier<PaymentChannel> channels;
    private final PaymentChannelDiagnostics diagnostics;
    private final Clock clock;
    private final Supplier<String> probeNumbers;

    /**
     * @param probeNumbers 每次给一串新的数字（不超过 20 位），拼在 CHK 后面当自检单号
     */
    public TradeChannelCheckService(
            Supplier<PaymentChannel> channels,
            PaymentChannelDiagnostics diagnostics,
            Clock clock,
            Supplier<String> probeNumbers
    ) {
        this.channels = channels;
        this.diagnostics = diagnostics;
        this.clock = clock;
        this.probeNumbers = probeNumbers;
    }

    /**
     * @param subMchid 用来查单的二级商户号（在微信服务商后台已绑定的那家）；不给时跳过向渠道发请求的两项
     */
    public ChannelCheckView run(String subMchid) {
        String sub = subMchid == null || subMchid.isBlank() ? null : subMchid.trim();
        if (sub != null && !SUB_MCHID.matcher(sub).matches()) {
            throw new IllegalArgumentException("subMchid must be 8-32 digits");
        }
        List<ChannelCheckItemView> items = new ArrayList<>(diagnostics.configuration());
        PaymentChannel channel = channels.get();
        if (channel == null) {
            items.add(item("CHANNEL", FAIL, "后端没有可用的支付渠道：trade.wechat.enabled 不是 true"));
            items.add(item("QUERY_PAYMENT", SKIPPED, "没有支付渠道，不查单"));
            items.add(item("QUERY_REFUND", SKIPPED, "没有支付渠道，不查退款"));
        } else if (sub == null) {
            items.add(item("CHANNEL", PASS, "后端已建好支付渠道"));
            items.add(item("QUERY_PAYMENT", SKIPPED, "没有给子商户号：给一个已在服务商后台绑定的二级商户号才能查单"));
            items.add(item("QUERY_REFUND", SKIPPED, "没有给子商户号，不查退款"));
        } else {
            items.add(item("CHANNEL", PASS, "后端已建好支付渠道"));
            items.add(probePayment(channel, sub));
            items.add(probeRefund(channel, sub));
        }
        boolean passed = items.stream().noneMatch(found -> FAIL.equals(found.status()));
        return new ChannelCheckView(diagnostics.mode(), passed, clock.instant(), List.copyOf(items));
    }

    private ChannelCheckItemView probePayment(PaymentChannel channel, String sub) {
        String outTradeNo = PROBE_PREFIX + probeNumbers.get();
        try {
            PaymentQuery query = channel.queryPayment(sub, outTradeNo);
            if (query.state() == PaymentState.NOT_FOUND) {
                return item("QUERY_PAYMENT", PASS, "查单通过：请求签名、网络和应答验签都对，微信回答没有这一单");
            }
            return item("QUERY_PAYMENT", WARN, "微信说自检单号存在（" + query.rawState() + "），请人工核对");
        } catch (PaymentChannelException exception) {
            return item("QUERY_PAYMENT", FAIL, "查单失败：" + explain(exception));
        } catch (RuntimeException exception) {
            return item("QUERY_PAYMENT", FAIL, "查单出错：" + exception.getClass().getSimpleName());
        }
    }

    private ChannelCheckItemView probeRefund(PaymentChannel channel, String sub) {
        String outRefundNo = PROBE_PREFIX + "R" + probeNumbers.get();
        try {
            RefundQuery query = channel.queryRefund(sub, outRefundNo);
            if (query.state() == RefundState.NOT_FOUND) {
                return item("QUERY_REFUND", PASS, "查退款通过：微信回答没有这一笔退款");
            }
            return item("QUERY_REFUND", WARN, "微信说自检退款单号存在（" + query.rawState() + "），请人工核对");
        } catch (PaymentChannelException exception) {
            return item("QUERY_REFUND", FAIL, "查退款失败：" + explain(exception));
        } catch (RuntimeException exception) {
            return item("QUERY_REFUND", FAIL, "查退款出错：" + exception.getClass().getSimpleName());
        }
    }

    /** 把渠道的错误翻成运维能照着查的话；只用错误码和状态码，不用渠道原文（原文可能带请求里的值）。 */
    static String explain(PaymentChannelException exception) {
        String code = exception.code() == null ? "" : exception.code();
        String status = exception.httpStatus() == 0 ? "" : "，HTTP " + exception.httpStatus();
        return switch (exception.kind()) {
            case UNTRUSTED -> "微信的应答验签不过，检查微信支付公钥文件和公钥 ID（PUB_KEY_ID_…）是否属于这个服务商号（" + code + "）";
            case RETRYABLE -> switch (code) {
                case "NETWORK" -> "连不上微信支付，检查服务器出口网络、DNS 和防火墙";
                case "INTERRUPTED" -> "请求被中断，再试一次";
                // 自检靠“单不存在”的 404 下结论，代码要求它带签名并验过。没带签名时网络和请求签名其实是通的：
                // 这正是联调要确认的事（docs/redesign/05-微信支付联调记录.md §3 第 12 条），记下来再改判法。
                case "UNVERIFIED_ERROR" -> "微信的错误应答没有签名或验签不过。HTTP 404 时多半是“单不存在”的应答不带签名："
                        + "请求签名和网络是通的，记进联调记录 §3 第 12 条；也排查是否经过了代理（" + code + status + "）";
                default -> "微信暂时不可用，稍后再试（" + code + status + "）";
            };
            case REJECTED -> switch (code) {
                case "SIGN_ERROR" -> "微信说请求签名不对，检查 API 证书序列号和私钥是否同一张证书（" + code + status + "）";
                case "MCH_NOT_EXISTS" -> "服务商号或子商户号不存在（" + code + status + "）";
                case "NO_AUTH", "INVALID_REQUEST" ->
                        "没有权限：子商户号没有和服务商号绑定，或服务商没有开通这个产品（" + code + status + "）";
                case "PARAM_ERROR" -> "参数不对，检查服务商号和子商户号（" + code + status + "）";
                default -> "微信拒绝了请求（" + code + status + "）";
            };
        };
    }

    private static ChannelCheckItemView item(String code, String status, String message) {
        return new ChannelCheckItemView(code, status, message);
    }
}
