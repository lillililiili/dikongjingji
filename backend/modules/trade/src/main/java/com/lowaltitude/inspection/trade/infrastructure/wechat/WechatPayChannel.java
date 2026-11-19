package com.lowaltitude.inspection.trade.infrastructure.wechat;

import java.io.IOException;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowaltitude.inspection.trade.application.PaymentChannel;
import com.lowaltitude.inspection.trade.application.PaymentChannelException;
import com.lowaltitude.inspection.trade.application.PaymentChannelException.Kind;

/**
 * 微信支付平台收付通（服务商模式）的普通支付。接口和字段见官方文档 pay.weixin.qq.com/doc/v3/partner/：
 * APP 下单 4012714669、查单 4012760568、关单 4012760574、支付回调 4012090195、申请退款 4012476892、
 * 查退款 4012476911、退款回调 4012124635、App 调起 4012090168 / 4012365868、签名验签 4012365870、
 * 回调解密 4012082320、微信支付公钥 4012925323 / 4013059017。
 *
 * <p>每个应答都验签：2xx 验不过就不信（UNTRUSTED）。错误应答带了签名头就必须验过，验不过当作结果不确定（RETRYABLE），
 * 不按它的错误码下结论；完全没带签名头的错误应答（比如网关直接返回的 5xx）按错误码分类——它只会让调用方重试或停下，
 * 不会让任何单子变成“已付款”或“已退款”。拿来下结论的错误应答（查单 404 ORDER_NOT_EXIST、查退款 404
 * RESOURCE_NOT_EXISTS、账单 400 NO_STATEMENT_EXIST）例外：必须验过签，没带签名头时当作 RETRYABLE。
 * 错误应答是否总带签名头，联调时确认。
 */
public final class WechatPayChannel implements PaymentChannel {

    static final String APP_PREPAY = "/v3/pay/partner/transactions/app";
    static final String QUERY_BY_OUT_TRADE_NO = "/v3/pay/partner/transactions/out-trade-no/";
    static final String REFUND_APPLY = "/v3/ecommerce/refunds/apply";
    static final String REFUND_BY_OUT_REFUND_NO = "/v3/ecommerce/refunds/out-refund-no/";

    static final int MAX_DESCRIPTION = 127;
    static final int MAX_REASON = 80;

    private static final Pattern SUB_MCHID = Pattern.compile("^[0-9]{8,32}$");
    private static final Pattern OUT_TRADE_NO = Pattern.compile("^[0-9A-Za-z_*-]{6,32}$");
    // 微信允许 _-|*@，这里不收 |：它在 URL 路径里要转义，转义后签名串和实际路径容易对不上。
    private static final Pattern OUT_REFUND_NO = Pattern.compile("^[0-9A-Za-z_*@-]{6,64}$");
    private static final Pattern PREPAY_ID = Pattern.compile("^[0-9A-Za-z_-]{1,64}$");
    // STATEMENT_CREATING：账单还在生成（10 点前），过一会儿再申请。
    private static final Set<String> RETRYABLE_CODES =
            Set.of("SYSTEM_ERROR", "BANK_ERROR", "FREQUENCY_LIMITED", "STATEMENT_CREATING");
    private static final DateTimeFormatter RFC3339 = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final String USER_AGENT = "low-altitude-trade/0.1 (Java)";

    private final WechatPaySettings settings;
    private final WechatPayCredentials credentials;
    private final WechatPayTransport transport;
    private final WechatPaySigner signer;
    private final WechatPayVerifier verifier;
    private final WechatPayCipher cipher;
    private final ObjectMapper json = new ObjectMapper();

    public WechatPayChannel(WechatPaySettings settings, WechatPayCredentials credentials, WechatPayTransport transport) {
        this(settings, credentials, transport, Clock.systemUTC(), randomNonces());
    }

    WechatPayChannel(
            WechatPaySettings settings,
            WechatPayCredentials credentials,
            WechatPayTransport transport,
            Clock clock,
            Supplier<String> nonces
    ) {
        this.settings = settings;
        this.credentials = credentials;
        this.transport = transport;
        this.signer = new WechatPaySigner(
                credentials.spMchid(), credentials.merchantSerialNo(), credentials.merchantPrivateKey(), clock, nonces);
        this.verifier = new WechatPayVerifier(
                credentials.platformPublicKeyId(), credentials.platformPublicKey(), clock, settings.signatureTolerance());
        this.cipher = new WechatPayCipher(credentials.apiV3Key());
    }

    static Supplier<String> randomNonces() {
        SecureRandom random = new SecureRandom();
        return () -> {
            byte[] bytes = new byte[16];
            random.nextBytes(bytes);
            return HexFormat.of().withUpperCase().formatHex(bytes);
        };
    }

    @Override
    public String providerId() {
        return credentials.spMchid();
    }

    // ---- 支付 ----

    @Override
    public String createAppPayment(PrepayRequest request) {
        requireSubMchid(request.subMchid());
        requireOutTradeNo(request.outTradeNo());
        require(request.amountMinor() > 0, "amount must be positive");
        require(request.payBefore() != null, "pay-before is required");
        String description = truncate(request.description(), MAX_DESCRIPTION);
        require(!description.isBlank(), "description is required");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sp_appid", credentials.spAppid());
        body.put("sp_mchid", credentials.spMchid());
        body.put("sub_mchid", request.subMchid());
        body.put("description", description);
        body.put("out_trade_no", request.outTradeNo());
        body.put("time_expire", RFC3339.format(request.payBefore().atZone(BEIJING)));
        body.put("notify_url", settings.paymentNotifyUrl().toString());
        // 平台不分账：明确写 false，钱按普通订单结算给商家，不冻结。
        body.put("settle_info", Map.of("profit_sharing", false));
        body.put("amount", amount(request.amountMinor()));

        JsonNode response = exchange("POST", APP_PREPAY, write(body), 200).body();
        String prepayId = text(response, "prepay_id");
        if (prepayId == null || !PREPAY_ID.matcher(prepayId).matches()) {
            throw format("prepay_id is missing");
        }
        return prepayId;
    }

    @Override
    public AppPayParams appPayParams(String prepayId) {
        require(prepayId != null && PREPAY_ID.matcher(prepayId).matches(), "prepay id is invalid");
        // 下单只传了 sp_appid，所以 App 用 sp_appid，partnerId 对应填服务商号（4012090168）。
        WechatPaySigner.AppSignature signature = signer.signAppPay(credentials.spAppid(), prepayId);
        return new AppPayParams(credentials.spAppid(), credentials.spMchid(), prepayId, "Sign=WXPay",
                signature.nonceStr(), signature.timeStamp(), signature.sign());
    }

    @Override
    public PaymentQuery queryPayment(String subMchid, String outTradeNo) {
        requireSubMchid(subMchid);
        requireOutTradeNo(outTradeNo);
        String path = QUERY_BY_OUT_TRADE_NO + outTradeNo + "?sp_mchid=" + credentials.spMchid() + "&sub_mchid=" + subMchid;
        Exchange exchange = exchange("GET", path, null, 200, 404);
        if (exchange.status() == 404) {
            // 404 只认 ORDER_NOT_EXIST；别的 404 交给错误处理。
            if (!"ORDER_NOT_EXIST".equals(text(exchange.body(), "code"))) {
                throw failure(exchange.status(), exchange.body());
            }
            // “单不存在”会让订单被关掉，没验过签的不能信。
            requireVerified(exchange);
            return new PaymentQuery(PaymentState.NOT_FOUND, "ORDER_NOT_EXIST", credentials.spMchid(), subMchid,
                    outTradeNo, null, null, null, null, null);
        }
        return payment(exchange.body());
    }

    @Override
    public void closePayment(String subMchid, String outTradeNo) {
        requireSubMchid(subMchid);
        requireOutTradeNo(outTradeNo);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sp_mchid", credentials.spMchid());
        body.put("sub_mchid", subMchid);
        // 文档写成功是 204；200 也当成功（同样验过签）。
        exchange("POST", QUERY_BY_OUT_TRADE_NO + outTradeNo + "/close", write(body), 204, 200);
    }

    // ---- 退款 ----

    @Override
    public RefundAccepted applyRefund(RefundRequest request) {
        requireSubMchid(request.subMchid());
        requireOutTradeNo(request.outTradeNo());
        require(request.outRefundNo() != null && OUT_REFUND_NO.matcher(request.outRefundNo()).matches(),
                "out refund no is invalid");
        require(request.refundMinor() > 0 && request.totalMinor() > 0 && request.refundMinor() <= request.totalMinor(),
                "refund amount is invalid");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sub_mchid", request.subMchid());
        body.put("sp_appid", credentials.spAppid());
        body.put("out_trade_no", request.outTradeNo());
        body.put("out_refund_no", request.outRefundNo());
        String reason = truncate(request.reason(), MAX_REASON);
        if (!reason.isBlank()) {
            body.put("reason", reason);
        }
        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("refund", request.refundMinor());
        amount.put("total", request.totalMinor());
        amount.put("currency", "CNY");
        body.put("amount", amount);
        body.put("notify_url", settings.refundNotifyUrl().toString());

        JsonNode response = exchange("POST", REFUND_APPLY, write(body), 200).body();
        String refundId = text(response, "refund_id");
        String outRefundNo = text(response, "out_refund_no");
        Long refunded = number(response.path("amount"), "refund");
        if (refundId == null || outRefundNo == null || refunded == null) {
            throw format("refund acceptance is incomplete");
        }
        return new RefundAccepted(refundId, outRefundNo, refunded, time(response, "create_time"));
    }

    @Override
    public RefundQuery queryRefund(String subMchid, String outRefundNo) {
        requireSubMchid(subMchid);
        require(outRefundNo != null && OUT_REFUND_NO.matcher(outRefundNo).matches(), "out refund no is invalid");
        Exchange exchange = exchange("GET", REFUND_BY_OUT_REFUND_NO + outRefundNo + "?sub_mchid=" + subMchid, null, 200, 404);
        if (exchange.status() == 404) {
            if (!"RESOURCE_NOT_EXISTS".equals(text(exchange.body(), "code"))) {
                throw failure(exchange.status(), exchange.body());
            }
            requireVerified(exchange);
            return new RefundQuery(RefundState.NOT_FOUND, "RESOURCE_NOT_EXISTS", subMchid, null, outRefundNo,
                    null, null, null, null);
        }
        // 查询应答里没有 sub_mchid，用请求的；有 status 字段。
        return refund(exchange.body(), subMchid, "status");
    }

    // ---- 账单 ----

    static final String TRADE_BILL = "/v3/bill/tradebill";
    private static final Set<String> OFFICIAL_HOSTS = Set.of("api.mch.weixin.qq.com", "api2.mch.weixin.qq.com");

    @Override
    public TradeBill tradeBill(String subMchid, LocalDate billDate) {
        requireSubMchid(subMchid);
        require(billDate != null, "bill date is required");
        // 不压缩：SHA1 直接对下载到的字节算，避开“压缩前还是压缩后”的问题。
        String path = TRADE_BILL + "?bill_date=" + billDate + "&sub_mchid=" + subMchid + "&bill_type=ALL";
        Exchange applied = exchange("GET", path, null, 200, 400);
        if (applied.status() == 400) {
            String code = text(applied.body(), "code");
            if ("NO_STATEMENT_EXIST".equals(code)) {
                // 那天这个商户没有交易，也没有退款。空账单会让对账认为平台这边的单都是多出来的，没验过签的不能信。
                requireVerified(applied);
                return new TradeBill(subMchid, billDate, List.of());
            }
            throw failure(applied.status(), applied.body());
        }
        String hashType = text(applied.body(), "hash_type");
        String hash = text(applied.body(), "hash_value");
        String downloadUrl = text(applied.body(), "download_url");
        if (!"SHA1".equals(hashType) || hash == null || !hash.matches("^[0-9A-Fa-f]{40}$") || downloadUrl == null) {
            throw format("bill application is incomplete");
        }
        URI download = trustedDownload(downloadUrl);
        String pathAndQuery = download.getRawPath() + (download.getRawQuery() == null ? "" : "?" + download.getRawQuery());
        URI origin = URI.create(download.getScheme() + "://" + download.getRawAuthority());
        // 下载应答不带微信的签名（文档如此），靠申请时拿到的 SHA1 校验。
        WechatPayTransport.Response file = send(origin, "GET", pathAndQuery, null);
        if (file.status() != 200) {
            throw failure(file.status(), readLeniently(file.body()));
        }
        byte[] raw = file.raw();
        String actual;
        try {
            actual = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(raw));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is unavailable", exception);
        }
        if (!actual.equalsIgnoreCase(hash)) {
            throw new PaymentChannelException(Kind.UNTRUSTED, "BILL_HASH", 0, "wechat pay bill does not match its hash");
        }
        String text;
        try {
            text = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(raw))
                    .toString();
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw format("bill is not UTF-8");
        }
        return new TradeBill(subMchid, billDate, WechatPayBillParser.parse(text));
    }

    /**
     * 下载地址只认微信自己的域名（或配置的接口地址本身，本机联调时）：带着签名的请求不发给别的主机。
     */
    private URI trustedDownload(String downloadUrl) {
        URI url;
        try {
            url = URI.create(downloadUrl);
        } catch (IllegalArgumentException exception) {
            throw format("bill download url is malformed");
        }
        URI base = settings.apiBase();
        boolean sameOrigin = url.getScheme() != null && url.getScheme().equals(base.getScheme())
                && url.getHost() != null && url.getHost().equalsIgnoreCase(base.getHost()) && url.getPort() == base.getPort();
        boolean official = "https".equals(url.getScheme()) && url.getPort() == -1
                && url.getHost() != null && OFFICIAL_HOSTS.contains(url.getHost().toLowerCase(java.util.Locale.ROOT));
        if (!sameOrigin && !official) {
            throw new PaymentChannelException(Kind.UNTRUSTED, "BILL_URL", 0, "wechat pay bill download url points elsewhere");
        }
        return url;
    }

    // ---- 回调 ----

    @Override
    public ChannelNotification parseNotification(Map<String, String> headers, String body) {
        verifier.verify(WechatPayTransport.caseInsensitive(headers), body);
        JsonNode notification = read(body);
        String id = text(notification, "id");
        String eventType = text(notification, "event_type");
        if (id == null || eventType == null) {
            throw format("notification id or event type is missing");
        }
        boolean payment = "TRANSACTION.SUCCESS".equals(eventType);
        boolean refund = eventType.startsWith("REFUND.");
        if (!payment && !refund) {
            return new OtherNotification(id, eventType);
        }
        if (!"encrypt-resource".equals(text(notification, "resource_type"))) {
            throw format("notification resource is not encrypted");
        }
        JsonNode resource = notification.path("resource");
        if (!WechatPayCipher.ALGORITHM.equals(text(resource, "algorithm"))) {
            throw format("notification uses an unknown algorithm");
        }
        String expectedType = payment ? "transaction" : "refund";
        if (!expectedType.equals(text(resource, "original_type"))) {
            throw format("notification resource type does not match the event");
        }
        JsonNode plain = read(cipher.decrypt(
                text(resource, "associated_data"), text(resource, "nonce"), text(resource, "ciphertext")));
        if (payment) {
            return new PaymentNotification(id, eventType, payment(plain));
        }
        return new RefundNotification(id, eventType, refund(plain, text(plain, "sub_mchid"), "refund_status"));
    }

    // ---- 应答翻译 ----

    private PaymentQuery payment(JsonNode node) {
        String rawState = text(node, "trade_state");
        String outTradeNo = text(node, "out_trade_no");
        if (rawState == null || outTradeNo == null) {
            throw format("payment state is missing");
        }
        PaymentState state = switch (rawState) {
            case "SUCCESS" -> PaymentState.SUCCESS;
            case "REFUND" -> PaymentState.REFUND;
            case "NOTPAY" -> PaymentState.NOTPAY;
            case "CLOSED" -> PaymentState.CLOSED;
            default -> PaymentState.OTHER;
        };
        JsonNode amount = node.path("amount");
        PaymentQuery query = new PaymentQuery(state, rawState, text(node, "sp_mchid"), text(node, "sub_mchid"),
                outTradeNo, text(node, "transaction_id"), number(amount, "total"), number(amount, "payer_total"),
                text(amount, "currency"), time(node, "success_time"));
        // 付过款的单缺交易号或金额就没法核对，不能当成功；成功的还必须有付款时间。
        if ((state == PaymentState.SUCCESS || state == PaymentState.REFUND)
                && (query.transactionId() == null || query.totalMinor() == null)) {
            throw format("paid payment is missing its transaction or amount");
        }
        if (state == PaymentState.SUCCESS && query.succeededAt() == null) {
            throw format("successful payment is missing its time");
        }
        return query;
    }

    private RefundQuery refund(JsonNode node, String subMchid, String stateField) {
        String rawState = text(node, stateField);
        String outRefundNo = text(node, "out_refund_no");
        if (rawState == null || outRefundNo == null) {
            throw format("refund state is missing");
        }
        RefundState state = switch (rawState) {
            case "SUCCESS" -> RefundState.SUCCESS;
            case "CLOSED" -> RefundState.CLOSED;
            case "PROCESSING" -> RefundState.PROCESSING;
            case "ABNORMAL" -> RefundState.ABNORMAL;
            default -> RefundState.OTHER;
        };
        JsonNode amount = node.path("amount");
        RefundQuery query = new RefundQuery(state, rawState, subMchid, text(node, "out_trade_no"), outRefundNo,
                text(node, "refund_id"), number(amount, "refund"), number(amount, "total"), time(node, "success_time"));
        if (state == RefundState.SUCCESS
                && (query.refundId() == null || query.refundMinor() == null || query.succeededAt() == null)) {
            throw format("successful refund is missing its id, amount or time");
        }
        return query;
    }

    // ---- HTTP ----

    /** verified：这个应答验过签（2xx 一定验过；非 2xx 只有带了签名头才验）。 */
    private record Exchange(int status, JsonNode body, boolean verified) {
    }

    /**
     * 拿非 2xx 应答下结论（单不存在、退款不存在、没有账单）之前调用：它必须验过签。
     * 没带签名头的可能来自网关或中间人，当作结果不确定，让调用方稍后重试。
     */
    private static void requireVerified(Exchange exchange) {
        if (!exchange.verified()) {
            throw new PaymentChannelException(Kind.RETRYABLE, "UNVERIFIED_ERROR", exchange.status(),
                    "wechat pay answered " + exchange.status() + " without a signature");
        }
    }

    /** 签名并发出请求，网络问题翻译成 RETRYABLE。不验签。 */
    private WechatPayTransport.Response send(URI origin, String method, String pathAndQuery, String body) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", signer.authorization(method, pathAndQuery, body));
        headers.put("Accept", "application/json");
        headers.put("User-Agent", USER_AGENT);
        if (body != null) {
            headers.put("Content-Type", "application/json");
        }
        try {
            return transport.send(new WechatPayTransport.Request(
                    method, URI.create(origin.toString().replaceAll("/+$", "") + pathAndQuery), headers, body));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PaymentChannelException(Kind.RETRYABLE, "INTERRUPTED", "wechat pay call was interrupted", exception);
        } catch (IOException exception) {
            throw new PaymentChannelException(Kind.RETRYABLE, "NETWORK", "wechat pay is unreachable", exception);
        }
    }

    /** 发请求、验签；status 不在 expected 里时按错误处理。 */
    private Exchange exchange(String method, String pathAndQuery, String body, int... expected) {
        WechatPayTransport.Response response = send(settings.apiBase(), method, pathAndQuery, body);

        int status = response.status();
        boolean success = status >= 200 && status < 300;
        boolean verified = false;
        if (success) {
            verifier.verify(response.headers(), response.body());
            verified = true;
        } else if (WechatPayVerifier.hasSignatureHeaders(response.headers())) {
            try {
                verifier.verify(response.headers(), response.body());
            } catch (PaymentChannelException exception) {
                throw new PaymentChannelException(Kind.RETRYABLE, "UNVERIFIED_ERROR", status,
                        "wechat pay error response failed verification");
            }
            verified = true;
        }
        JsonNode parsed;
        if (response.body().isEmpty()) {
            parsed = json.createObjectNode();
        } else if (success) {
            parsed = read(response.body());
        } else {
            // 错误应答可能是网关给的 HTML，读不出 JSON 就当没有错误码（按状态码判断能否重试）。
            parsed = readLeniently(response.body());
        }
        for (int code : expected) {
            if (code == status) {
                return new Exchange(status, parsed, verified);
            }
        }
        throw failure(status, parsed);
    }

    private PaymentChannelException failure(int status, JsonNode body) {
        String code = text(body, "code");
        if (status >= 200 && status < 300) {
            return new PaymentChannelException(Kind.UNTRUSTED, "UNEXPECTED_STATUS", status,
                    "wechat pay answered with an unexpected status " + status);
        }
        String message = truncate(text(body, "message"), 120);
        if (status >= 500 || code == null || RETRYABLE_CODES.contains(code)) {
            return new PaymentChannelException(Kind.RETRYABLE, code == null ? "HTTP_" + status : code, status,
                    "wechat pay " + (code == null ? "error" : code) + " (" + status + ") " + message);
        }
        return new PaymentChannelException(Kind.REJECTED, code, status,
                "wechat pay " + code + " (" + status + ") " + message);
    }

    // ---- JSON ----

    private String write(Map<String, Object> body) {
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not write the wechat pay request", exception);
        }
    }

    private JsonNode read(String body) {
        try {
            JsonNode node = json.readTree(body);
            if (node == null || !node.isObject()) {
                throw format("body is not a JSON object");
            }
            return node;
        } catch (JsonProcessingException exception) {
            throw format("body is not JSON");
        }
    }

    private JsonNode readLeniently(String body) {
        try {
            JsonNode node = json.readTree(body);
            return node != null && node.isObject() ? node : json.createObjectNode();
        } catch (JsonProcessingException exception) {
            return json.createObjectNode();
        }
    }

    private static Map<String, Object> amount(long totalMinor) {
        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("total", totalMinor);
        amount.put("currency", "CNY");
        return amount;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() || value.asText().isEmpty() ? null : value.asText();
    }

    private static Long number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw format(field + " is not an integer");
        }
        return value.asLong();
    }

    private static Instant time(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException exception) {
            throw format(field + " is not an RFC 3339 time");
        }
    }

    // ---- 校验 ----

    /** 按字符（码点）截断，不切开汉字和表情。 */
    static String truncate(String value, int maxCodePoints) {
        if (value == null) {
            return "";
        }
        String trimmed = value.strip();
        if (trimmed.codePointCount(0, trimmed.length()) <= maxCodePoints) {
            return trimmed;
        }
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, maxCodePoints));
    }

    private static void requireSubMchid(String subMchid) {
        require(subMchid != null && SUB_MCHID.matcher(subMchid).matches(), "sub mchid is invalid");
    }

    private static void requireOutTradeNo(String outTradeNo) {
        require(outTradeNo != null && OUT_TRADE_NO.matcher(outTradeNo).matches(), "out trade no is invalid");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static PaymentChannelException format(String message) {
        return new PaymentChannelException(Kind.UNTRUSTED, "FORMAT", 0, "wechat pay " + message);
    }
}
