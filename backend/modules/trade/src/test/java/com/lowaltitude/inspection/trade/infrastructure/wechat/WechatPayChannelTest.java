package com.lowaltitude.inspection.trade.infrastructure.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowaltitude.inspection.trade.application.PaymentChannel;
import com.lowaltitude.inspection.trade.application.PaymentChannel.AppPayParams;
import com.lowaltitude.inspection.trade.application.PaymentChannel.ChannelNotification;
import com.lowaltitude.inspection.trade.application.PaymentChannel.OtherNotification;
import com.lowaltitude.inspection.trade.application.PaymentChannel.PaymentNotification;
import com.lowaltitude.inspection.trade.application.PaymentChannel.PaymentQuery;
import com.lowaltitude.inspection.trade.application.PaymentChannel.PaymentState;
import com.lowaltitude.inspection.trade.application.PaymentChannel.PrepayRequest;
import com.lowaltitude.inspection.trade.application.PaymentChannel.RefundAccepted;
import com.lowaltitude.inspection.trade.application.PaymentChannel.RefundNotification;
import com.lowaltitude.inspection.trade.application.PaymentChannel.RefundQuery;
import com.lowaltitude.inspection.trade.application.PaymentChannel.RefundRequest;
import com.lowaltitude.inspection.trade.application.PaymentChannel.RefundState;
import com.lowaltitude.inspection.trade.application.PaymentChannelException;
import com.lowaltitude.inspection.trade.application.PaymentChannelException.Kind;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WechatPayChannelTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OUT_TRADE_NO = "P2026111800000001A1";
    private static final Instant PAY_BEFORE = Instant.parse("2026-11-18T02:30:00Z");

    private final FakeWechatPay wechat = new FakeWechatPay();
    private final WechatPayChannel channel = wechat.channel();

    private static PrepayRequest prepay() {
        return new PrepayRequest(FakeWechatPay.SUB_MCHID, OUT_TRADE_NO, "示例机器人商家-编程机器人套装", 276000, PAY_BEFORE);
    }

    private static JsonNode json(String body) throws IOException {
        return JSON.readTree(body);
    }

    // ---- 下单 ----

    @Test
    void appPrepayPostsTheDocumentedBodyAndReturnsThePrepayId() throws IOException {
        wechat.replySigned(200, "{\"prepay_id\":\"wx26112221580621e9b071c00d9e093b0000\"}");

        String prepayId = channel.createAppPayment(prepay());

        assertThat(prepayId).isEqualTo("wx26112221580621e9b071c00d9e093b0000");
        WechatPayTransport.Request request = wechat.lastRequest();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri().toString()).isEqualTo("https://api.mch.weixin.qq.com/v3/pay/partner/transactions/app");
        assertThat(request.headers()).containsEntry("Accept", "application/json")
                .containsEntry("Content-Type", "application/json")
                .containsKey("User-Agent");
        assertThat(FakeWechatPay.checkAuthorization(request))
                .containsEntry("mchid", FakeWechatPay.SP_MCHID)
                .containsEntry("serial_no", FakeWechatPay.SERIAL);

        JsonNode body = json(request.body());
        assertThat(body.get("sp_appid").asText()).isEqualTo(FakeWechatPay.SP_APPID);
        assertThat(body.get("sp_mchid").asText()).isEqualTo(FakeWechatPay.SP_MCHID);
        assertThat(body.get("sub_mchid").asText()).isEqualTo(FakeWechatPay.SUB_MCHID);
        assertThat(body.has("sub_appid")).isFalse();
        assertThat(body.get("description").asText()).isEqualTo("示例机器人商家-编程机器人套装");
        assertThat(body.get("out_trade_no").asText()).isEqualTo(OUT_TRADE_NO);
        // 北京时间、带时区的 RFC 3339。
        assertThat(body.get("time_expire").asText()).isEqualTo("2026-11-18T10:30:00+08:00");
        assertThat(body.get("notify_url").asText())
                .isEqualTo("https://pay.example.test/api/v1/trade/channels/wechat/notifications");
        assertThat(body.get("settle_info").get("profit_sharing").isBoolean()).isTrue();
        assertThat(body.get("settle_info").get("profit_sharing").asBoolean()).isFalse();
        assertThat(body.get("amount").get("total").isIntegralNumber()).isTrue();
        assertThat(body.get("amount").get("total").asLong()).isEqualTo(276000);
        assertThat(body.get("amount").get("currency").asText()).isEqualTo("CNY");
        assertThat(body.size()).isEqualTo(9);
    }

    @Test
    void aLongDescriptionIsCutAt127CharactersWithoutSplittingAnEmoji() throws IOException {
        wechat.replySigned(200, "{\"prepay_id\":\"wx1\"}");
        String description = "机".repeat(126) + "🚁" + "多余";

        channel.createAppPayment(new PrepayRequest(FakeWechatPay.SUB_MCHID, OUT_TRADE_NO, description, 1, PAY_BEFORE));

        String sent = json(wechat.lastRequest().body()).get("description").asText();
        assertThat(sent.codePointCount(0, sent.length())).isEqualTo(127);
        assertThat(sent).endsWith("🚁");
    }

    @Test
    void prepayInputIsCheckedBeforeAnythingIsSent() {
        assertThatThrownBy(() -> channel.createAppPayment(new PrepayRequest("abc", OUT_TRADE_NO, "x", 1, PAY_BEFORE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> channel.createAppPayment(new PrepayRequest(FakeWechatPay.SUB_MCHID, "P|1234567", "x", 1, PAY_BEFORE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> channel.createAppPayment(new PrepayRequest(FakeWechatPay.SUB_MCHID, "P" + "1".repeat(32), "x", 1, PAY_BEFORE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> channel.createAppPayment(new PrepayRequest(FakeWechatPay.SUB_MCHID, OUT_TRADE_NO, "x", 0, PAY_BEFORE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> channel.createAppPayment(new PrepayRequest(FakeWechatPay.SUB_MCHID, OUT_TRADE_NO, "  ", 1, PAY_BEFORE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> channel.createAppPayment(new PrepayRequest(FakeWechatPay.SUB_MCHID, OUT_TRADE_NO, "x", 1, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(wechat.requests).isEmpty();
    }

    @Test
    void anUnsignedOrForeignSignedPrepayAnswerIsNotTrusted() {
        String body = "{\"prepay_id\":\"wx1\"}";
        wechat.reply(new WechatPayTransport.Response(200, Map.of(), body));
        assertKind(() -> channel.createAppPayment(prepay()), Kind.UNTRUSTED, "SIGNATURE");

        wechat.reply(new WechatPayTransport.Response(200, FakeWechatPay.signedHeaders(FakeWechatPay.NOW, "N", body,
                FakeWechatPay.MERCHANT.getPrivate(), FakeWechatPay.PUBLIC_KEY_ID), body));
        assertKind(() -> channel.createAppPayment(prepay()), Kind.UNTRUSTED, "SIGNATURE");
    }

    @Test
    void aSignedAnswerWithoutAPrepayIdIsNotTrusted() {
        wechat.replySigned(200, "{}");
        assertKind(() -> channel.createAppPayment(prepay()), Kind.UNTRUSTED, "FORMAT");

        wechat.replySigned(200, "{\"prepay_id\":\"wx 1\"}");
        assertKind(() -> channel.createAppPayment(prepay()), Kind.UNTRUSTED, "FORMAT");

        wechat.replySigned(200, "not json");
        assertKind(() -> channel.createAppPayment(prepay()), Kind.UNTRUSTED, "FORMAT");
    }

    @Test
    void anotherApiBaseIsUsedAsIs() {
        WechatPaySettings backup = new WechatPaySettings(java.net.URI.create("https://api2.mch.weixin.qq.com/"),
                FakeWechatPay.settings().paymentNotifyUrl(), FakeWechatPay.settings().refundNotifyUrl(), null);
        WechatPayChannel other = new WechatPayChannel(backup, FakeWechatPay.credentials(), wechat, wechat.clock, () -> "N");
        wechat.replySigned(200, "{\"prepay_id\":\"wx1\"}");

        other.createAppPayment(prepay());

        assertThat(wechat.lastRequest().uri().toString()).isEqualTo("https://api2.mch.weixin.qq.com/v3/pay/partner/transactions/app");
        FakeWechatPay.checkAuthorization(wechat.lastRequest());
    }

    // ---- App 调起 ----

    @Test
    void appPayParamsUseTheServiceProviderAppAndAreSignedWithTheMerchantKey() {
        AppPayParams params = channel.appPayParams("wx26112221580621e9b071c00d9e093b0000");

        assertThat(params.appId()).isEqualTo(FakeWechatPay.SP_APPID);
        assertThat(params.partnerId()).isEqualTo(FakeWechatPay.SP_MCHID);
        assertThat(params.prepayId()).isEqualTo("wx26112221580621e9b071c00d9e093b0000");
        assertThat(params.packageValue()).isEqualTo("Sign=WXPay");
        assertThat(params.timeStamp()).isEqualTo(Long.toString(FakeWechatPay.NOW.getEpochSecond()));
        String message = params.appId() + "\n" + params.timeStamp() + "\n" + params.nonceStr() + "\n" + params.prepayId() + "\n";
        assertThat(FakeWechatPay.verify(FakeWechatPay.MERCHANT.getPublic(), message, params.sign())).isTrue();
        assertThat(params.sign().length()).isLessThanOrEqualTo(512);
        assertThat(wechat.requests).isEmpty();
    }

    @Test
    void appPayParamsRefuseAMalformedPrepayId() {
        assertThatThrownBy(() -> channel.appPayParams(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> channel.appPayParams("wx\n1")).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- 查单 ----

    @Test
    void queryGetsByOutTradeNoWithBothMerchantIdsAndReadsASuccess() {
        wechat.replySigned(200, """
                {"sp_appid":"wxd678efh567hg6787","sp_mchid":"1900000001","sub_mchid":"1900000101",
                 "out_trade_no":"P2026111800000001A1","transaction_id":"4200000985202611181234567890",
                 "trade_type":"APP","trade_state":"SUCCESS","trade_state_desc":"支付成功","bank_type":"CMC",
                 "success_time":"2026-11-18T10:05:12+08:00","payer":{"sp_openid":"oUpF8uMuAJO_M2pxb1Q9zNjWeS6o"},
                 "amount":{"total":276000,"payer_total":276000,"currency":"CNY","payer_currency":"CNY"}}""");

        PaymentQuery query = channel.queryPayment(FakeWechatPay.SUB_MCHID, OUT_TRADE_NO);

        WechatPayTransport.Request request = wechat.lastRequest();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.body()).isNull();
        assertThat(request.headers()).doesNotContainKey("Content-Type");
        assertThat(request.uri().toString()).isEqualTo("https://api.mch.weixin.qq.com/v3/pay/partner/transactions/out-trade-no/"
                + OUT_TRADE_NO + "?sp_mchid=1900000001&sub_mchid=1900000101");
        FakeWechatPay.checkAuthorization(request);

        assertThat(query.state()).isEqualTo(PaymentState.SUCCESS);
        assertThat(query.rawState()).isEqualTo("SUCCESS");
        assertThat(query.spMchid()).isEqualTo("1900000001");
        assertThat(query.subMchid()).isEqualTo("1900000101");
        assertThat(query.outTradeNo()).isEqualTo(OUT_TRADE_NO);
        assertThat(query.transactionId()).isEqualTo("4200000985202611181234567890");
        assertThat(query.totalMinor()).isEqualTo(276000);
        assertThat(query.payerTotalMinor()).isEqualTo(276000);
        assertThat(query.currency()).isEqualTo("CNY");
        assertThat(query.succeededAt()).isEqualTo(Instant.parse("2026-11-18T02:05:12Z"));
    }

    @ParameterizedTest
    @CsvSource({"NOTPAY,NOTPAY", "CLOSED,CLOSED", "USERPAYING,OTHER", "PAYERROR,OTHER", "REVOKED,OTHER", "SOMETHING_NEW,OTHER"})
    void unpaidStatesNeedNoTransaction(String raw, PaymentState expected) {
        wechat.replySigned(200, "{\"sp_mchid\":\"1900000001\",\"sub_mchid\":\"1900000101\",\"out_trade_no\":\""
                + OUT_TRADE_NO + "\",\"trade_state\":\"" + raw + "\",\"trade_state_desc\":\"x\",\"amount\":{\"total\":276000}}");

        PaymentQuery query = channel.queryPayment(FakeWechatPay.SUB_MCHID, OUT_TRADE_NO);

        assertThat(query.state()).isEqualTo(expected);
        assertThat(query.rawState()).isEqualTo(raw);
        assertThat(query.transactionId()).isNull();
        assertThat(query.succeededAt()).isNull();
    }

    @Test
    void aRefundedPaymentStillCarriesItsTransactionAndAmount() {
        wechat.replySigned(200, "{\"out_trade_no\":\"" + OUT_TRADE_NO + "\",\"trade_state\":\"REFUND\","
                + "\"transaction_id\":\"4200000985202611181234567890\",\"amount\":{\"total\":276000,\"payer_total\":276000}}");

        assertThat(channel.queryPayment(FakeWechatPay.SUB_MCHID, OUT_TRADE_NO).state()).isEqualTo(PaymentState.REFUND);
    }

    @Test
    void aSuccessMissingWhatIsNeededToCheckItIsNotTrusted() {
        String base = "\"out_trade_no\":\"" + OUT_TRADE_NO + "\",\"trade_state\":\"SUCCESS\"";
        for (String body : new String[] {
                "{" + base + ",\"success_time\":\"2026-11-18T10:05:12+08:00\",\"amount\":{\"total\":1}}",
                "{" + base + ",\"transaction_id\":\"42\",\"success_time\":\"2026-11-18T10:05:12+08:00\"}",
                "{" + base + ",\"transaction_id\":\"42\",\"amount\":{\"total\":1}}",
                "{" + base + ",\"transaction_id\":\"42\",\"success_time\":\"yesterday\",\"amount\":{\"total\":1}}",
                "{" + base + ",\"transaction_id\":\"42\",\"success_time\":\"2026-11-18T10:05:12+08:00\",\"amount\":{\"total\":1.5}}",
                "{" + base + ",\"transaction_id\":\"42\",\"success_time\":\"2026-11-18T10:05:12+08:00\",\"amount\":{\"total\":\"1\"}}",
                "{\"trade_state\":\"SUCCESS\"}"}) {
            wechat.replySigned(200, body);
            assertKind(() -> channel.queryPayment(FakeWechatPay.SUB_MCHID, OUT_TRADE_NO), Kind.UNTRUSTED, "FORMAT");
        }
    }

    @Test
    void anOrderWechatDoesNotKnowIsNotFound() {
        wechat.replySigned(404, "{\"code\":\"ORDER_NOT_EXIST\",\"message\":\"订单不存在\"}");

        PaymentQuery query = channel.queryPayment(FakeWechatPay.SUB_MCHID, OUT_TRADE_NO);

        assertThat(query.state()).isEqualTo(PaymentState.NOT_FOUND);
        assertThat(query.outTradeNo()).isEqualTo(OUT_TRADE_NO);
        assertThat(query.totalMinor()).isNull();
    }

    @Test
    void another404IsAnError() {
        wechat.replySigned(404, "{\"code\":\"RESOURCE_NOT_EXISTS\",\"message\":\"x\"}");
        assertKind(() -> channel.queryPayment(FakeWechatPay.SUB_MCHID, OUT_TRADE_NO), Kind.REJECTED, "RESOURCE_NOT_EXISTS");
    }

    @Test
    void aNotFoundWithABadSignatureIsNotBelieved() {
        String body = "{\"code\":\"ORDER_NOT_EXIST\",\"message\":\"订单不存在\"}";
        Map<String, String> headers = new HashMap<>(FakeWechatPay.signedHeaders(FakeWechatPay.NOW, "N", body,
                FakeWechatPay.PLATFORM.getPrivate(), FakeWechatPay.PUBLIC_KEY_ID));
        headers.put("Wechatpay-Nonce", "OTHER");
        wechat.reply(new WechatPayTransport.Response(404, headers, body));

        assertKind(() -> channel.queryPayment(FakeWechatPay.SUB_MCHID, OUT_TRADE_NO), Kind.RETRYABLE, "UNVERIFIED_ERROR");
    }

    // “单不存在”会让订单被关掉：完全没带签名头的（可能来自网关或中间人）也不信，稍后重试。
    @Test
    void anUnsignedNotFoundIsNotBelieved() {
        wechat.reply(new WechatPayTransport.Response(404, Map.of("Content-Type", "application/json"),
                "{\"code\":\"ORDER_NOT_EXIST\",\"message\":\"订单不存在\"}"));

        assertThatThrownBy(() -> channel.queryPayment(FakeWechatPay.SUB_MCHID, OUT_TRADE_NO))
                .isInstanceOfSatisfying(PaymentChannelException.class, exception -> {
                    assertThat(exception.kind()).isEqualTo(Kind.RETRYABLE);
                    assertThat(exception.code()).isEqualTo("UNVERIFIED_ERROR");
                    assertThat(exception.httpStatus()).isEqualTo(404);
                });

        // 不用来下结论的 404 照旧按错误码分类，带不带签名头都一样。
        wechat.reply(new WechatPayTransport.Response(404, Map.of(), "{\"code\":\"RESOURCE_NOT_EXISTS\",\"message\":\"x\"}"));
        assertKind(() -> channel.queryPayment(FakeWechatPay.SUB_MCHID, OUT_TRADE_NO), Kind.REJECTED, "RESOURCE_NOT_EXISTS");
    }

    // ---- 关单 ----

    @Test
    void closePostsBothMerchantIdsAndAcceptsA204() throws IOException {
        wechat.replySigned(204, "");

        channel.closePayment(FakeWechatPay.SUB_MCHID, OUT_TRADE_NO);

        WechatPayTransport.Request request = wechat.lastRequest();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri().getPath()).isEqualTo("/v3/pay/partner/transactions/out-trade-no/" + OUT_TRADE_NO + "/close");
        assertThat(json(request.body()).toString()).isEqualTo("{\"sp_mchid\":\"1900000001\",\"sub_mchid\":\"1900000101\"}");
        FakeWechatPay.checkAuthorization(request);
    }

    @Test
    void aCloseAnsweredWithAnUnsigned204IsNotTrusted() {
        wechat.reply(new WechatPayTransport.Response(204, Map.of(), ""));
        assertKind(() -> channel.closePayment(FakeWechatPay.SUB_MCHID, OUT_TRADE_NO), Kind.UNTRUSTED, "SIGNATURE");
    }

    @Test
    void aCloseOfAPaidOrderComesBackAsRejected() {
        wechat.replySigned(403, "{\"code\":\"TRADE_ERROR\",\"message\":\"订单已支付\"}");
        assertKind(() -> channel.closePayment(FakeWechatPay.SUB_MCHID, OUT_TRADE_NO), Kind.REJECTED, "TRADE_ERROR");
    }

    // ---- 错误分类 ----

    @ParameterizedTest
    @CsvSource({
            "500,SYSTEM_ERROR,RETRYABLE",
            "500,BANK_ERROR,RETRYABLE",
            "429,FREQUENCY_LIMITED,RETRYABLE",
            "503,SOMETHING,RETRYABLE",
            "400,PARAM_ERROR,REJECTED",
            "400,INVALID_REQUEST,REJECTED",
            "401,SIGN_ERROR,REJECTED",
            "403,OUT_TRADE_NO_USED,REJECTED",
            "403,NO_AUTH,REJECTED",
            "400,ORDER_CLOSED,REJECTED"})
    void signedErrorsAreSortedByCode(int status, String code, Kind kind) {
        wechat.replySigned(status, "{\"code\":\"" + code + "\",\"message\":\"说明\"}");

        assertThatThrownBy(() -> channel.createAppPayment(prepay()))
                .isInstanceOfSatisfying(PaymentChannelException.class, exception -> {
                    assertThat(exception.kind()).isEqualTo(kind);
                    assertThat(exception.code()).isEqualTo(code);
                    assertThat(exception.httpStatus()).isEqualTo(status);
                    assertThat(exception.getMessage()).contains(code).doesNotContain("276000");
                });
    }

    @Test
    void anUnsignedGatewayErrorPageIsRetryable() {
        wechat.reply(new WechatPayTransport.Response(502, Map.of("Content-Type", "text/html"), "<html>Bad Gateway</html>"));
        assertKind(() -> channel.createAppPayment(prepay()), Kind.RETRYABLE, "HTTP_502");

        wechat.reply(new WechatPayTransport.Response(400, Map.of(), ""));
        assertKind(() -> channel.createAppPayment(prepay()), Kind.RETRYABLE, "HTTP_400");
    }

    @Test
    void anUnexpectedSuccessStatusIsNotTrusted() {
        wechat.replySigned(202, "{}");
        assertKind(() -> channel.createAppPayment(prepay()), Kind.UNTRUSTED, "UNEXPECTED_STATUS");
    }

    @Test
    void networkFailuresAreRetryable() {
        WechatPayChannel broken = new WechatPayChannel(FakeWechatPay.settings(), FakeWechatPay.credentials(), request -> {
            throw new IOException("connection reset");
        }, wechat.clock, () -> "N");
        assertKind(() -> broken.createAppPayment(prepay()), Kind.RETRYABLE, "NETWORK");
    }

    @Test
    void anInterruptedCallIsRetryableAndKeepsTheInterruptFlag() {
        WechatPayChannel interrupted = new WechatPayChannel(FakeWechatPay.settings(), FakeWechatPay.credentials(), request -> {
            throw new InterruptedException();
        }, wechat.clock, () -> "N");
        try {
            assertKind(() -> interrupted.queryPayment(FakeWechatPay.SUB_MCHID, OUT_TRADE_NO), Kind.RETRYABLE, "INTERRUPTED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    // ---- 退款 ----

    private static RefundRequest refundRequest(long refund, String reason) {
        return new RefundRequest(FakeWechatPay.SUB_MCHID, OUT_TRADE_NO, "R2026111800000001A1", refund, 276000, reason);
    }

    @Test
    void refundApplyPostsTheDocumentedBodyAndReadsTheAcceptance() throws IOException {
        wechat.replySigned(200, """
                {"refund_id":"50000000382019052709732678859","out_refund_no":"R2026111800000001A1",
                 "create_time":"2026-11-18T10:20:00+08:00",
                 "amount":{"refund":138000,"payer_refund":138000,"discount_refund":0,"currency":"CNY"}}""");

        RefundAccepted accepted = channel.applyRefund(refundRequest(138000, "买家取消订单"));

        WechatPayTransport.Request request = wechat.lastRequest();
        assertThat(request.uri().toString()).isEqualTo("https://api.mch.weixin.qq.com/v3/ecommerce/refunds/apply");
        FakeWechatPay.checkAuthorization(request);
        JsonNode body = json(request.body());
        assertThat(body.get("sub_mchid").asText()).isEqualTo(FakeWechatPay.SUB_MCHID);
        assertThat(body.get("sp_appid").asText()).isEqualTo(FakeWechatPay.SP_APPID);
        assertThat(body.get("out_trade_no").asText()).isEqualTo(OUT_TRADE_NO);
        assertThat(body.has("transaction_id")).isFalse();
        assertThat(body.get("out_refund_no").asText()).isEqualTo("R2026111800000001A1");
        assertThat(body.get("reason").asText()).isEqualTo("买家取消订单");
        assertThat(body.get("amount").get("refund").asLong()).isEqualTo(138000);
        assertThat(body.get("amount").get("total").asLong()).isEqualTo(276000);
        assertThat(body.get("amount").get("currency").asText()).isEqualTo("CNY");
        assertThat(body.get("notify_url").asText()).isEqualTo("https://pay.example.test/api/v1/trade/channels/wechat/notifications");
        assertThat(body.has("refund_account")).isFalse();

        assertThat(accepted.refundId()).isEqualTo("50000000382019052709732678859");
        assertThat(accepted.outRefundNo()).isEqualTo("R2026111800000001A1");
        assertThat(accepted.refundMinor()).isEqualTo(138000);
        assertThat(accepted.acceptedAt()).isEqualTo(Instant.parse("2026-11-18T02:20:00Z"));
    }

    @Test
    void aBlankReasonIsLeftOutAndALongOneIsCutAt80() throws IOException {
        wechat.replySigned(200, "{\"refund_id\":\"5\",\"out_refund_no\":\"R2026111800000001A1\",\"amount\":{\"refund\":1}}");
        channel.applyRefund(refundRequest(1, "  "));
        assertThat(json(wechat.lastRequest().body()).has("reason")).isFalse();

        wechat.replySigned(200, "{\"refund_id\":\"5\",\"out_refund_no\":\"R2026111800000001A1\",\"amount\":{\"refund\":1}}");
        channel.applyRefund(refundRequest(1, "退".repeat(90)));
        assertThat(json(wechat.lastRequest().body()).get("reason").asText()).hasSize(80);
    }

    @Test
    void refundInputIsCheckedBeforeAnythingIsSent() {
        assertThatThrownBy(() -> channel.applyRefund(refundRequest(276001, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> channel.applyRefund(refundRequest(0, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> channel.applyRefund(new RefundRequest(FakeWechatPay.SUB_MCHID, OUT_TRADE_NO, "R|20261118", 1, 2, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> channel.applyRefund(new RefundRequest(FakeWechatPay.SUB_MCHID, OUT_TRADE_NO, "R1", 1, 2, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(wechat.requests).isEmpty();
    }

    @Test
    void anIncompleteAcceptanceIsNotTrusted() {
        wechat.replySigned(200, "{\"out_refund_no\":\"R2026111800000001A1\",\"amount\":{\"refund\":1}}");
        assertKind(() -> channel.applyRefund(refundRequest(1, null)), Kind.UNTRUSTED, "FORMAT");
    }

    @ParameterizedTest
    @CsvSource({"403,NOT_ENOUGH,REJECTED", "403,USER_ACCOUNT_ABNORMAL,REJECTED", "404,RESOURCE_NOT_EXISTS,REJECTED",
            "429,FREQUENCY_LIMITED,RETRYABLE", "500,SYSTEM_ERROR,RETRYABLE"})
    void refundErrorsAreSortedByCode(int status, String code, Kind kind) {
        wechat.replySigned(status, "{\"code\":\"" + code + "\",\"message\":\"x\"}");
        assertKind(() -> channel.applyRefund(refundRequest(1, null)), kind, code);
    }

    @Test
    void refundQueryGetsByOutRefundNoAndReadsTheStatus() {
        wechat.replySigned(200, """
                {"refund_id":"50000000382019052709732678859","out_refund_no":"R2026111800000001A1",
                 "transaction_id":"4200000985202611181234567890","out_trade_no":"P2026111800000001A1",
                 "channel":"ORIGINAL","user_received_account":"支付用户零钱","success_time":"2026-11-18T10:21:00+08:00",
                 "create_time":"2026-11-18T10:20:00+08:00","status":"SUCCESS",
                 "amount":{"refund":138000,"payer_refund":138000,"currency":"CNY"}}""");

        RefundQuery query = channel.queryRefund(FakeWechatPay.SUB_MCHID, "R2026111800000001A1");

        WechatPayTransport.Request request = wechat.lastRequest();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.uri().toString())
                .isEqualTo("https://api.mch.weixin.qq.com/v3/ecommerce/refunds/out-refund-no/R2026111800000001A1?sub_mchid=1900000101");
        FakeWechatPay.checkAuthorization(request);
        assertThat(query.state()).isEqualTo(RefundState.SUCCESS);
        assertThat(query.subMchid()).isEqualTo(FakeWechatPay.SUB_MCHID);
        assertThat(query.outTradeNo()).isEqualTo(OUT_TRADE_NO);
        assertThat(query.refundId()).isEqualTo("50000000382019052709732678859");
        assertThat(query.refundMinor()).isEqualTo(138000);
        assertThat(query.totalMinor()).isNull();
        assertThat(query.succeededAt()).isEqualTo(Instant.parse("2026-11-18T02:21:00Z"));
    }

    @ParameterizedTest
    @CsvSource({"PROCESSING,PROCESSING", "CLOSED,CLOSED", "ABNORMAL,ABNORMAL", "NEW_STATE,OTHER"})
    void otherRefundStatesAreRead(String raw, RefundState expected) {
        wechat.replySigned(200, "{\"refund_id\":\"5\",\"out_refund_no\":\"R2026111800000001A1\",\"status\":\"" + raw
                + "\",\"amount\":{\"refund\":1}}");
        assertThat(channel.queryRefund(FakeWechatPay.SUB_MCHID, "R2026111800000001A1").state()).isEqualTo(expected);
    }

    @Test
    void aRefundSuccessWithoutItsTimeIsNotTrusted() {
        wechat.replySigned(200, "{\"refund_id\":\"5\",\"out_refund_no\":\"R2026111800000001A1\",\"status\":\"SUCCESS\",\"amount\":{\"refund\":1}}");
        assertKind(() -> channel.queryRefund(FakeWechatPay.SUB_MCHID, "R2026111800000001A1"), Kind.UNTRUSTED, "FORMAT");
    }

    @Test
    void aRefundWechatDoesNotKnowIsNotFound() {
        wechat.replySigned(404, "{\"code\":\"RESOURCE_NOT_EXISTS\",\"message\":\"退款单不存在\"}");
        assertThat(channel.queryRefund(FakeWechatPay.SUB_MCHID, "R2026111800000001A1").state()).isEqualTo(RefundState.NOT_FOUND);

        wechat.replySigned(404, "{\"code\":\"ORDER_NOT_EXIST\"}");
        assertKind(() -> channel.queryRefund(FakeWechatPay.SUB_MCHID, "R2026111800000001A1"), Kind.REJECTED, "ORDER_NOT_EXIST");
    }

    // 退款不存在也是结论：没带签名头的不信；带了签名头却验不过的同样不信。
    @Test
    void anUnsignedOrUnverifiedRefundNotFoundIsNotBelieved() {
        String body = "{\"code\":\"RESOURCE_NOT_EXISTS\",\"message\":\"退款单不存在\"}";
        wechat.reply(new WechatPayTransport.Response(404, Map.of(), body));
        assertKind(() -> channel.queryRefund(FakeWechatPay.SUB_MCHID, "R2026111800000001A1"),
                Kind.RETRYABLE, "UNVERIFIED_ERROR");

        Map<String, String> headers = new HashMap<>(FakeWechatPay.signedHeaders(FakeWechatPay.NOW, "N", body,
                FakeWechatPay.PLATFORM.getPrivate(), FakeWechatPay.PUBLIC_KEY_ID));
        headers.put("Wechatpay-Nonce", "OTHER");
        wechat.reply(new WechatPayTransport.Response(404, headers, body));
        assertKind(() -> channel.queryRefund(FakeWechatPay.SUB_MCHID, "R2026111800000001A1"),
                Kind.RETRYABLE, "UNVERIFIED_ERROR");

        // 不用来下结论的错误码照旧：没带签名头的 404 ORDER_NOT_EXIST 还是 REJECTED。
        wechat.reply(new WechatPayTransport.Response(404, Map.of(), "{\"code\":\"ORDER_NOT_EXIST\"}"));
        assertKind(() -> channel.queryRefund(FakeWechatPay.SUB_MCHID, "R2026111800000001A1"),
                Kind.REJECTED, "ORDER_NOT_EXIST");
    }

    // ---- 回调 ----

    private static final String PAID = """
            {"sp_mchid":"1900000001","sub_mchid":"1900000101","sp_appid":"wxd678efh567hg6787",
             "out_trade_no":"P2026111800000001A1","transaction_id":"4200000985202611181234567890","trade_type":"APP",
             "trade_state":"SUCCESS","trade_state_desc":"支付成功","bank_type":"CMC","success_time":"2026-11-18T10:05:12+08:00",
             "payer":{"sp_openid":"oUpF8uMuAJO_M2pxb1Q9zNjWeS6o"},
             "amount":{"total":276000,"payer_total":276000,"currency":"CNY","payer_currency":"CNY"}}""";

    private static final String REFUNDED = """
            {"sp_mchid":"1900000001","sub_mchid":"1900000101","out_trade_no":"P2026111800000001A1",
             "transaction_id":"4200000985202611181234567890","out_refund_no":"R2026111800000001A1",
             "refund_id":"50000000382019052709732678859","refund_status":"SUCCESS","success_time":"2026-11-18T10:21:00+08:00",
             "user_received_account":"支付用户零钱","amount":{"total":276000,"refund":138000,"payer_total":276000,"payer_refund":138000}}""";

    @Test
    void aPaymentNotificationIsVerifiedDecryptedAndRead() {
        FakeWechatPay.Notification notification =
                FakeWechatPay.notification("EV-018370938", "TRANSACTION.SUCCESS", "transaction", PAID);

        ChannelNotification parsed = channel.parseNotification(notification.headers(), notification.body());

        assertThat(parsed).isInstanceOfSatisfying(PaymentNotification.class, payment -> {
            assertThat(payment.notificationId()).isEqualTo("EV-018370938");
            assertThat(payment.eventType()).isEqualTo("TRANSACTION.SUCCESS");
            assertThat(payment.payment().state()).isEqualTo(PaymentState.SUCCESS);
            assertThat(payment.payment().spMchid()).isEqualTo("1900000001");
            assertThat(payment.payment().subMchid()).isEqualTo("1900000101");
            assertThat(payment.payment().outTradeNo()).isEqualTo(OUT_TRADE_NO);
            assertThat(payment.payment().transactionId()).isEqualTo("4200000985202611181234567890");
            assertThat(payment.payment().totalMinor()).isEqualTo(276000);
            assertThat(payment.payment().succeededAt()).isEqualTo(Instant.parse("2026-11-18T02:05:12Z"));
        });
        assertThat(wechat.requests).isEmpty();
    }

    @Test
    void refundNotificationsAreReadForEveryOutcome() {
        for (String status : new String[] {"SUCCESS", "ABNORMAL", "CLOSED"}) {
            String plain = REFUNDED.replace("\"refund_status\":\"SUCCESS\"", "\"refund_status\":\"" + status + "\"");
            FakeWechatPay.Notification notification =
                    FakeWechatPay.notification("EV-R-" + status, "REFUND." + status, "refund", plain);

            ChannelNotification parsed = channel.parseNotification(notification.headers(), notification.body());

            assertThat(parsed).isInstanceOfSatisfying(RefundNotification.class, refund -> {
                assertThat(refund.notificationId()).isEqualTo("EV-R-" + status);
                assertThat(refund.refund().state()).isEqualTo(RefundState.valueOf(status));
                assertThat(refund.refund().subMchid()).isEqualTo("1900000101");
                assertThat(refund.refund().outRefundNo()).isEqualTo("R2026111800000001A1");
                assertThat(refund.refund().refundMinor()).isEqualTo(138000);
                assertThat(refund.refund().totalMinor()).isEqualTo(276000);
            });
        }
    }

    @Test
    void otherEventsAreAcknowledgedWithoutDecrypting() {
        String body = "{\"id\":\"EV-9\",\"event_type\":\"PROFITSHARING.SUCCESS\",\"resource_type\":\"encrypt-resource\","
                + "\"resource\":{\"algorithm\":\"AEAD_AES_256_GCM\",\"ciphertext\":\"garbage\",\"nonce\":\"x\",\"original_type\":\"x\"}}";
        Map<String, String> headers = FakeWechatPay.signedHeaders(FakeWechatPay.NOW, "N", body,
                FakeWechatPay.PLATFORM.getPrivate(), FakeWechatPay.PUBLIC_KEY_ID);

        assertThat(channel.parseNotification(headers, body))
                .isEqualTo(new OtherNotification("EV-9", "PROFITSHARING.SUCCESS"));
    }

    @Test
    void lowercaseHeaderNamesAreFine() {
        FakeWechatPay.Notification notification = FakeWechatPay.notification("EV-1", "TRANSACTION.SUCCESS", "transaction", PAID);
        Map<String, String> lower = new HashMap<>();
        notification.headers().forEach((name, value) -> lower.put(name.toLowerCase(), value));

        assertThat(channel.parseNotification(lower, notification.body())).isInstanceOf(PaymentNotification.class);
    }

    @Test
    void aNotificationWithABadSignatureIsRejectedBeforeDecrypting() {
        FakeWechatPay.Notification notification = FakeWechatPay.notification("EV-1", "TRANSACTION.SUCCESS", "transaction", PAID);
        Map<String, String> headers = new HashMap<>(notification.headers());
        headers.put("Wechatpay-Signature", "WECHATPAY/SIGNTEST/abc");

        assertKind(() -> channel.parseNotification(headers, notification.body()), Kind.UNTRUSTED, "SIGNATURE");
        assertKind(() -> channel.parseNotification(notification.headers(), notification.body().replace("EV-1", "EV-2")),
                Kind.UNTRUSTED, "SIGNATURE");
        assertKind(() -> channel.parseNotification(Map.of(), notification.body()), Kind.UNTRUSTED, "SIGNATURE");
    }

    @Test
    void aCorrectlySignedButUndecryptableNotificationIsRejected() {
        String sealed = FakeWechatPay.encrypt("0123456789abcdefABCDEF012345678X".getBytes(), "transaction", "fdasflkja484", PAID);
        String body = "{\"id\":\"EV-1\",\"event_type\":\"TRANSACTION.SUCCESS\",\"resource_type\":\"encrypt-resource\","
                + "\"resource\":{\"original_type\":\"transaction\",\"algorithm\":\"AEAD_AES_256_GCM\",\"ciphertext\":\""
                + sealed + "\",\"associated_data\":\"transaction\",\"nonce\":\"fdasflkja484\"}}";
        Map<String, String> headers = FakeWechatPay.signedHeaders(FakeWechatPay.NOW, "N", body,
                FakeWechatPay.PLATFORM.getPrivate(), FakeWechatPay.PUBLIC_KEY_ID);

        assertKind(() -> channel.parseNotification(headers, body), Kind.UNTRUSTED, "DECRYPT");
    }

    @Test
    void malformedNotificationsAreRejected() {
        FakeWechatPay.Notification good = FakeWechatPay.notification("EV-1", "TRANSACTION.SUCCESS", "transaction", PAID);
        for (String body : new String[] {
                good.body().replace("\"original_type\":\"transaction\"", "\"original_type\":\"refund\""),
                good.body().replace("AEAD_AES_256_GCM", "AEAD_AES_128_GCM"),
                good.body().replace("\"resource_type\":\"encrypt-resource\"", "\"resource_type\":\"plain\""),
                good.body().replace("\"id\":\"EV-1\",", ""),
                "[]",
                "not json"}) {
            Map<String, String> headers = FakeWechatPay.signedHeaders(FakeWechatPay.NOW, "N", body,
                    FakeWechatPay.PLATFORM.getPrivate(), FakeWechatPay.PUBLIC_KEY_ID);
            assertKind(() -> channel.parseNotification(headers, body), Kind.UNTRUSTED, null);
        }
    }

    @Test
    void aPaymentNotificationWhosePlaintextLacksTheTransactionIsRejected() {
        FakeWechatPay.Notification notification = FakeWechatPay.notification("EV-1", "TRANSACTION.SUCCESS", "transaction",
                PAID.replace("\"transaction_id\":\"4200000985202611181234567890\",", ""));
        assertKind(() -> channel.parseNotification(notification.headers(), notification.body()), Kind.UNTRUSTED, "FORMAT");
    }

    // ---- 交易账单（E06） ----

    private static final java.time.LocalDate BILL_DATE = java.time.LocalDate.parse("2026-11-17");
    private static final String DOWNLOAD = "https://api.mch.weixin.qq.com/v3/billdownload/file?token=6XIv5TUPto7pByrTSKjVgEAQnSKvGxXWzI0mQYdTdBM&tartype=";

    private static byte[] billBytes() {
        return WechatPayBillParserTest.standardBill().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void aBillIsAppliedForSignedThenDownloadedSignedAndParsed() {
        byte[] raw = billBytes();
        wechat.replyBillApplication(FakeWechatPay.sha1(raw), DOWNLOAD);
        wechat.replyFile(200, raw);

        PaymentChannel.TradeBill bill = channel.tradeBill(FakeWechatPay.SUB_MCHID, BILL_DATE);

        assertThat(wechat.requests).hasSize(2);
        WechatPayTransport.Request apply = wechat.requests.get(0);
        assertThat(apply.method()).isEqualTo("GET");
        assertThat(apply.body()).isNull();
        // 不压缩（没有 tar_type），全部交易（ALL），子商户号和日期在查询串里。
        assertThat(apply.uri().toString()).isEqualTo("https://api.mch.weixin.qq.com/v3/bill/tradebill"
                + "?bill_date=2026-11-17&sub_mchid=1900000101&bill_type=ALL");
        assertThat(FakeWechatPay.checkAuthorization(apply)).containsEntry("mchid", FakeWechatPay.SP_MCHID);

        WechatPayTransport.Request download = wechat.requests.get(1);
        assertThat(download.method()).isEqualTo("GET");
        assertThat(download.body()).isNull();
        assertThat(download.uri().toString()).isEqualTo(DOWNLOAD);
        // 下载也要签名，签名串里的路径是去掉域名、带查询串的那一段。
        Map<String, String> fields = FakeWechatPay.checkAuthorization(download);
        assertThat(fields).containsEntry("mchid", FakeWechatPay.SP_MCHID).containsEntry("serial_no", FakeWechatPay.SERIAL);
        assertThat(download.uri().getRawPath() + "?" + download.uri().getRawQuery())
                .isEqualTo("/v3/billdownload/file?token=6XIv5TUPto7pByrTSKjVgEAQnSKvGxXWzI0mQYdTdBM&tartype=");

        assertThat(bill.subMchid()).isEqualTo(FakeWechatPay.SUB_MCHID);
        assertThat(bill.billDate()).isEqualTo(BILL_DATE);
        assertThat(bill.lines()).isEqualTo(WechatPayBillParser.parse(WechatPayBillParserTest.standardBill()));
        assertThat(bill.lines()).extracting(PaymentChannel.BillLine::kind)
                .containsExactly(PaymentChannel.BillLineKind.PAYMENT, PaymentChannel.BillLineKind.REFUND);
    }

    // 下载地址里转义过的查询串原样签名、原样发出。
    @Test
    void anEscapedDownloadQueryIsSignedAsIs() {
        byte[] raw = billBytes();
        String url = "https://api.mch.weixin.qq.com/v3/billdownload/file?token=a%2Bb%2Fc%3D&tartype=";
        wechat.replyBillApplication(FakeWechatPay.sha1(raw).toUpperCase(), url);
        wechat.replyFile(200, raw);

        assertThat(channel.tradeBill(FakeWechatPay.SUB_MCHID, BILL_DATE).lines()).hasSize(2);

        WechatPayTransport.Request download = wechat.lastRequest();
        assertThat(download.uri().getRawQuery()).isEqualTo("token=a%2Bb%2Fc%3D&tartype=");
        FakeWechatPay.checkAuthorization(download);
    }

    // 那天这个商户没有交易：微信回 400 NO_STATEMENT_EXIST，当作空账单，不下载。
    @Test
    void noStatementMeansAnEmptyBill() {
        wechat.replySigned(400, "{\"code\":\"NO_STATEMENT_EXIST\",\"message\":\"账单文件不存在\"}");

        PaymentChannel.TradeBill bill = channel.tradeBill(FakeWechatPay.SUB_MCHID, BILL_DATE);

        assertThat(bill.lines()).isEmpty();
        assertThat(bill.subMchid()).isEqualTo(FakeWechatPay.SUB_MCHID);
        assertThat(bill.billDate()).isEqualTo(BILL_DATE);
        assertThat(wechat.requests).hasSize(1);
    }

    // 带了签名头却验不过的 NO_STATEMENT_EXIST 不信（不能因此把一天当成没交易）。
    @Test
    void anUnverifiedNoStatementIsNotBelieved() {
        String body = "{\"code\":\"NO_STATEMENT_EXIST\",\"message\":\"账单文件不存在\"}";
        Map<String, String> headers = new HashMap<>(FakeWechatPay.signedHeaders(FakeWechatPay.NOW, "N", body,
                FakeWechatPay.PLATFORM.getPrivate(), FakeWechatPay.PUBLIC_KEY_ID));
        headers.put("Wechatpay-Nonce", "OTHER");
        wechat.reply(new WechatPayTransport.Response(400, headers, body));

        assertKind(() -> channel.tradeBill(FakeWechatPay.SUB_MCHID, BILL_DATE), Kind.RETRYABLE, "UNVERIFIED_ERROR");
    }

    // 完全没带签名头的 NO_STATEMENT_EXIST 也不信，不下载、不当成空账单。
    @Test
    void anUnsignedNoStatementIsNotBelieved() {
        wechat.reply(new WechatPayTransport.Response(400, Map.of("Content-Type", "application/json"),
                "{\"code\":\"NO_STATEMENT_EXIST\",\"message\":\"账单文件不存在\"}"));

        assertKind(() -> channel.tradeBill(FakeWechatPay.SUB_MCHID, BILL_DATE), Kind.RETRYABLE, "UNVERIFIED_ERROR");
        assertThat(wechat.requests).hasSize(1);

        // 别的 400 错误码照旧按错误码分类。
        wechat.reply(new WechatPayTransport.Response(400, Map.of(), "{\"code\":\"PARAM_ERROR\",\"message\":\"x\"}"));
        assertKind(() -> channel.tradeBill(FakeWechatPay.SUB_MCHID, BILL_DATE), Kind.REJECTED, "PARAM_ERROR");
    }

    @ParameterizedTest
    @CsvSource({
            "400,STATEMENT_CREATING,RETRYABLE",
            "400,PARAM_ERROR,REJECTED",
            "400,INVALID_REQUEST,REJECTED",
            "403,NO_AUTH,REJECTED",
            "429,FREQUENCY_LIMITED,RETRYABLE",
            "500,SYSTEM_ERROR,RETRYABLE"})
    void billApplicationErrorsAreSortedByCode(int status, String code, Kind kind) {
        wechat.replySigned(status, "{\"code\":\"" + code + "\",\"message\":\"说明\"}");

        assertKind(() -> channel.tradeBill(FakeWechatPay.SUB_MCHID, BILL_DATE), kind, code);
        assertThat(wechat.requests).hasSize(1);
    }

    @Test
    void anUnsignedBillApplicationIsNotTrusted() {
        wechat.reply(new WechatPayTransport.Response(200, Map.of(),
                "{\"hash_type\":\"SHA1\",\"hash_value\":\"" + FakeWechatPay.sha1(billBytes()) + "\",\"download_url\":\""
                        + DOWNLOAD + "\"}"));

        assertKind(() -> channel.tradeBill(FakeWechatPay.SUB_MCHID, BILL_DATE), Kind.UNTRUSTED, "SIGNATURE");
        assertThat(wechat.requests).hasSize(1);
    }

    // 申请应答要有 SHA1、40 位十六进制的摘要和下载地址，否则不下载。
    @Test
    void anIncompleteBillApplicationIsNotTrusted() {
        String hash = FakeWechatPay.sha1(billBytes());
        for (String body : new String[] {
                "{\"hash_type\":\"MD5\",\"hash_value\":\"" + hash + "\",\"download_url\":\"" + DOWNLOAD + "\"}",
                "{\"hash_value\":\"" + hash + "\",\"download_url\":\"" + DOWNLOAD + "\"}",
                "{\"hash_type\":\"SHA1\",\"hash_value\":\"" + hash.substring(1) + "\",\"download_url\":\"" + DOWNLOAD + "\"}",
                "{\"hash_type\":\"SHA1\",\"hash_value\":\"" + hash + "0\",\"download_url\":\"" + DOWNLOAD + "\"}",
                "{\"hash_type\":\"SHA1\",\"hash_value\":\"" + "g".repeat(40) + "\",\"download_url\":\"" + DOWNLOAD + "\"}",
                "{\"hash_type\":\"SHA1\",\"hash_value\":\"" + hash + "\"}",
                "{\"hash_type\":\"SHA1\",\"hash_value\":\"" + hash + "\",\"download_url\":\"\"}",
                "{\"hash_type\":\"SHA1\",\"hash_value\":\"" + hash + "\",\"download_url\":\"not a url\"}"}) {
            wechat.replySigned(200, body);
            assertKind(() -> channel.tradeBill(FakeWechatPay.SUB_MCHID, BILL_DATE), Kind.UNTRUSTED, "FORMAT");
        }
        assertThat(wechat.requests).hasSize(8);
    }

    // 带签名的下载请求不发给微信以外的主机：别的域名、http、别的端口、长得像的域名都拒绝。
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "https://evil.example/v3/billdownload/file?token=1",
            "http://api.mch.weixin.qq.com/v3/billdownload/file?token=1",
            "https://api.mch.weixin.qq.com:8443/v3/billdownload/file?token=1",
            "https://api.mch.weixin.qq.com.evil.example/v3/billdownload/file?token=1",
            "https://api3.mch.weixin.qq.com/v3/billdownload/file?token=1",
            "https://mch.weixin.qq.com/v3/billdownload/file?token=1",
            "/v3/billdownload/file?token=1",
            "ftp://api.mch.weixin.qq.com/v3/billdownload/file?token=1"})
    void aDownloadUrlPointingElsewhereIsRefused(String url) {
        wechat.replyBillApplication(FakeWechatPay.sha1(billBytes()), url);

        assertKind(() -> channel.tradeBill(FakeWechatPay.SUB_MCHID, BILL_DATE), Kind.UNTRUSTED, "BILL_URL");
        assertThat(wechat.requests).hasSize(1);
    }

    // 微信的两个官方域名都放行（接口走 api、下载给 api2 也行），域名大小写不论。
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "https://api2.mch.weixin.qq.com/v3/billdownload/file?token=1",
            "https://API.MCH.WEIXIN.QQ.COM/v3/billdownload/file?token=1"})
    void theOfficialHostsAreTrusted(String url) {
        byte[] raw = billBytes();
        wechat.replyBillApplication(FakeWechatPay.sha1(raw), url);
        wechat.replyFile(200, raw);

        assertThat(channel.tradeBill(FakeWechatPay.SUB_MCHID, BILL_DATE).lines()).hasSize(2);
        assertThat(wechat.lastRequest().uri().toString()).isEqualTo(url);
        FakeWechatPay.checkAuthorization(wechat.lastRequest());
    }

    // 接口地址配成别的（本机联调的模拟服务）：和接口地址同源的下载地址放行，端口不同的不行。
    @Test
    void aDownloadFromTheConfiguredApiOriginIsTrusted() {
        WechatPaySettings local = new WechatPaySettings(java.net.URI.create("http://127.0.0.1:18999"),
                FakeWechatPay.settings().paymentNotifyUrl(), FakeWechatPay.settings().refundNotifyUrl(), null);
        WechatPayChannel mock = new WechatPayChannel(local, FakeWechatPay.credentials(), wechat, wechat.clock, () -> "N");
        byte[] raw = billBytes();
        wechat.replyBillApplication(FakeWechatPay.sha1(raw), "http://127.0.0.1:18999/v3/billdownload/file?token=1");
        wechat.replyFile(200, raw);

        assertThat(mock.tradeBill(FakeWechatPay.SUB_MCHID, BILL_DATE).lines()).hasSize(2);
        assertThat(wechat.requests.get(0).uri().toString())
                .startsWith("http://127.0.0.1:18999/v3/bill/tradebill?");
        assertThat(wechat.lastRequest().uri().toString()).isEqualTo("http://127.0.0.1:18999/v3/billdownload/file?token=1");

        wechat.replyBillApplication(FakeWechatPay.sha1(raw), "http://127.0.0.1:18998/v3/billdownload/file?token=1");
        assertKind(() -> mock.tradeBill(FakeWechatPay.SUB_MCHID, BILL_DATE), Kind.UNTRUSTED, "BILL_URL");
        // 本机联调时，微信的官方域名也只认 https。
        wechat.replyBillApplication(FakeWechatPay.sha1(raw), "http://api.mch.weixin.qq.com/v3/billdownload/file?token=1");
        assertKind(() -> mock.tradeBill(FakeWechatPay.SUB_MCHID, BILL_DATE), Kind.UNTRUSTED, "BILL_URL");
    }

    // 文件的 SHA1 和申请时给的不同（被改过、下载不全）：整份不信。
    @Test
    void aFileThatDoesNotMatchItsHashIsNotTrusted() {
        byte[] raw = billBytes();
        wechat.replyBillApplication(FakeWechatPay.sha1(raw), DOWNLOAD);
        byte[] tampered = raw.clone();
        tampered[tampered.length - 3] ^= 1;
        wechat.replyFile(200, tampered);

        assertKind(() -> channel.tradeBill(FakeWechatPay.SUB_MCHID, BILL_DATE), Kind.UNTRUSTED, "BILL_HASH");

        wechat.replyBillApplication(FakeWechatPay.sha1(raw), DOWNLOAD);
        wechat.replyFile(200, java.util.Arrays.copyOf(raw, raw.length - 10));
        assertKind(() -> channel.tradeBill(FakeWechatPay.SUB_MCHID, BILL_DATE), Kind.UNTRUSTED, "BILL_HASH");
    }

    // 摘要对得上但不是 UTF-8（比如 GBK 的文件）：严格解码，不信。
    @Test
    void aFileThatIsNotUtf8IsNotTrusted() {
        byte[] gbk = WechatPayBillParserTest.standardBill().getBytes(java.nio.charset.Charset.forName("GBK"));
        wechat.replyBillApplication(FakeWechatPay.sha1(gbk), DOWNLOAD);
        wechat.replyFile(200, gbk);

        assertKind(() -> channel.tradeBill(FakeWechatPay.SUB_MCHID, BILL_DATE), Kind.UNTRUSTED, "FORMAT");

        byte[] broken = new byte[] {'a', (byte) 0xC3, '\n'};
        wechat.replyBillApplication(FakeWechatPay.sha1(broken), DOWNLOAD);
        wechat.replyFile(200, broken);
        assertKind(() -> channel.tradeBill(FakeWechatPay.SUB_MCHID, BILL_DATE), Kind.UNTRUSTED, "FORMAT");
    }

    // 摘要对得上、汇总对不上：解析器的 FORMAT 原样抛出。
    @Test
    void aFileThatDoesNotAddUpIsNotTrusted() {
        byte[] raw = WechatPayBillParserTest.standardBill().replace("`2,`2760.00", "`3,`2760.00")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        wechat.replyBillApplication(FakeWechatPay.sha1(raw), DOWNLOAD);
        wechat.replyFile(200, raw);

        assertKind(() -> channel.tradeBill(FakeWechatPay.SUB_MCHID, BILL_DATE), Kind.UNTRUSTED, "FORMAT");
    }

    // 下载失败按状态和错误码分类：5xx、没有错误码的 4xx（网关页面）能重试，带错误码的 4xx 是拒绝；非 200 的 2xx 不信。
    @ParameterizedTest
    @CsvSource({
            "500,,RETRYABLE,HTTP_500",
            "502,<html>Bad Gateway</html>,RETRYABLE,HTTP_502",
            "403,<html>Forbidden</html>,RETRYABLE,HTTP_403",
            "404,'{\"code\":\"RESOURCE_NOT_EXISTS\",\"message\":\"x\"}',REJECTED,RESOURCE_NOT_EXISTS",
            "400,'{\"code\":\"INVALID_REQUEST\",\"message\":\"token 过期\"}',REJECTED,INVALID_REQUEST",
            "500,'{\"code\":\"SYSTEM_ERROR\",\"message\":\"x\"}',RETRYABLE,SYSTEM_ERROR",
            "204,,UNTRUSTED,UNEXPECTED_STATUS"})
    void downloadFailuresAreSorted(int status, String body, Kind kind, String code) {
        wechat.replyBillApplication(FakeWechatPay.sha1(billBytes()), DOWNLOAD);
        wechat.replyFile(status, (body == null ? "" : body).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> channel.tradeBill(FakeWechatPay.SUB_MCHID, BILL_DATE))
                .isInstanceOfSatisfying(PaymentChannelException.class, exception -> {
                    assertThat(exception.kind()).isEqualTo(kind);
                    assertThat(exception.code()).isEqualTo(code);
                    assertThat(exception.httpStatus()).isEqualTo(status);
                });
    }

    @Test
    void aNetworkFailureDuringTheDownloadIsRetryable() {
        byte[] raw = billBytes();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        WechatPayChannel flaky = new WechatPayChannel(FakeWechatPay.settings(), FakeWechatPay.credentials(), request -> {
            if (calls.incrementAndGet() == 2) {
                throw new IOException("connection reset");
            }
            return wechat.send(request);
        }, wechat.clock, () -> "N");
        wechat.replyBillApplication(FakeWechatPay.sha1(raw), DOWNLOAD);

        assertKind(() -> flaky.tradeBill(FakeWechatPay.SUB_MCHID, BILL_DATE), Kind.RETRYABLE, "NETWORK");
        assertThat(calls).hasValue(2);
    }

    @Test
    void billInputIsCheckedBeforeAnythingIsSent() {
        assertThatThrownBy(() -> channel.tradeBill("abc", BILL_DATE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> channel.tradeBill(null, BILL_DATE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> channel.tradeBill(FakeWechatPay.SUB_MCHID + "&bill_type=X", BILL_DATE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> channel.tradeBill(FakeWechatPay.SUB_MCHID, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(wechat.requests).isEmpty();
    }

    @Test
    void truncateCountsCharactersNotBytes() {
        assertThat(WechatPayChannel.truncate(null, 3)).isEmpty();
        assertThat(WechatPayChannel.truncate("  机器人  ", 3)).isEqualTo("机器人");
        assertThat(WechatPayChannel.truncate("🚁🚁🚁🚁", 3)).isEqualTo("🚁🚁🚁");
    }

    private static void assertKind(ThrowingCallable call, Kind kind, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PaymentChannelException.class, exception -> {
            assertThat(exception.kind()).as(exception.getMessage()).isEqualTo(kind);
            if (code != null) {
                assertThat(exception.code()).isEqualTo(code);
            }
        });
    }
}
