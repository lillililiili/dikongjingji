package com.lowaltitude.inspection.trade.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.lowaltitude.inspection.bootstrap.api.ApiError;
import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.mall.application.MallCartException;
import com.lowaltitude.inspection.mall.application.MallMerchantKind;
import com.lowaltitude.inspection.trade.application.TradeException.Error;
import com.lowaltitude.inspection.trade.application.TradePaymentService.NotificationResult;
import com.lowaltitude.inspection.trade.application.TradeViews.ChannelCheckItemView;
import com.lowaltitude.inspection.trade.application.TradeViews.ChannelCheckView;
import com.lowaltitude.inspection.trade.application.TradeViews.MerchantAccountView;
import com.lowaltitude.inspection.trade.application.TradeViews.PaymentLaunchView;
import com.lowaltitude.inspection.trade.application.TradeViews.ReconciliationDifferenceView;
import com.lowaltitude.inspection.trade.application.TradeViews.ReconciliationRunView;
import com.lowaltitude.inspection.trade.application.TradeViews.ReconciliationView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeAmountsView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeDeliveryView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeMediaView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeMerchantView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeOrderLineView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeOrderPageView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeOrderView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeRefundView;
import com.lowaltitude.inspection.trade.application.TradeViews.WechatAppCallView;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
import org.yaml.snakeyaml.Yaml;

/**
 * 契约 openapi-trade.yaml 0.1.0 是 E01–E06 的草案；E02 实现了其中五个操作，E04 加了发起支付、查一次单和微信回调，
 * E05 加了确认收货和商家的订单列表、发货、核销、退款，E06 加了对账结果（JSON / CSV）和立刻跑一次对账。
 * 这里逐项对照要登录的十四个：路由、方法、参数（含请求头）、请求体、成功响应都和控制器一致并且要登录；
 * 返回对象的字段与 schema 一致；枚举、长度和分页上限与服务一致；每一种业务拒绝的状态码和错误码在契约里有，
 * 并写在会给出它的操作下。公开的微信回调单独对照。草案里的操作现在都有控制器了（PENDING 为空）。
 *
 * <p>契约和实现已知对不上的地方（只报告、没改契约）按实现写成断言，并标了“已知差异”：契约补上后这些断言会失败，
 * 到时改成正向断言。
 */
class TradeOpenApiContractTest {

    private static final Path CONTRACT = Path.of("..", "..", "contracts", "openapi-trade.yaml");
    private static final String SCHEMA_PREFIX = "#/components/schemas/";

    private static final String RECEIPT = "POST /api/v1/trade/orders/{orderId}/receipt";
    private static final String MERCHANT_ORDERS = "GET /api/v1/provider/trade/orders";
    private static final String SHIPMENT = "POST /api/v1/provider/trade/orders/{orderId}/shipment";
    private static final String PICKUP = "POST /api/v1/provider/trade/orders/{orderId}/pickup";
    private static final String REFUNDS = "POST /api/v1/provider/trade/orders/{orderId}/refunds";
    private static final String RECONCILIATION = "GET /api/v1/platform/trade/reconciliation";
    private static final String RECONCILIATION_RUNS = "POST /api/v1/platform/trade/reconciliation/runs";
    private static final String CHANNEL_CHECKS = "POST /api/v1/platform/trade/channel-checks";

    private static final String ENTERPRISE_ORDERS = "GET /api/v1/trade/enterprise-orders";

    /** 已实现、要登录的操作：E02 五个，E04 两个，E05 五个，E06 两个，S2-1 一个，S3-2 两个（商家工作台的单张订单、收款账户）。 */
    private static final Map<String, String> IMPLEMENTED = Map.ofEntries(
            Map.entry("POST /api/v1/trade/orders", "submitOrder"),
            Map.entry("GET /api/v1/trade/orders", "listMyOrders"),
            Map.entry("GET /api/v1/trade/orders/{orderId}", "readMyOrder"),
            Map.entry("POST /api/v1/trade/orders/{orderId}/cancel", "cancelMyOrder"),
            Map.entry("POST /api/v1/trade/orders/{orderId}/payments", "launchPayment"),
            Map.entry("POST /api/v1/trade/orders/{orderId}/payment-check", "checkPayment"),
            Map.entry("PUT /api/v1/platform/trade/merchant-accounts/{organizationId}", "registerMerchantAccount"),
            Map.entry(RECEIPT, "confirmReceipt"),
            Map.entry(MERCHANT_ORDERS, "listMerchantOrders"),
            Map.entry("GET /api/v1/provider/trade/orders/{orderId}", "readMerchantOrder"),
            Map.entry("GET /api/v1/provider/trade/merchant-account", "readMyMerchantAccount"),
            Map.entry(SHIPMENT, "shipOrder"),
            Map.entry(PICKUP, "completePickup"),
            Map.entry(REFUNDS, "refundOrder"),
            Map.entry(RECONCILIATION, "exportReconciliation"),
            Map.entry(RECONCILIATION_RUNS, "runReconciliation"),
            Map.entry(CHANNEL_CHECKS, "checkPaymentChannel"),
            // 0.6.0（S5-7）：企业成员看本企业以企业身份付款的订单。
            Map.entry("GET /api/v1/trade/enterprise-orders", "listEnterpriseOrders"));

    /** 已实现的公开操作（E04 的微信回调）。 */
    private static final String NOTIFICATION = "POST /api/v1/trade/channels/wechat/notifications";

    /** 草案里还没实现的操作：E06 之后没有了。 */
    private static final Set<String> PENDING = Set.of();

    /** 返回对象：字段全部必有。 */
    private static final Map<String, Class<? extends Record>> SCHEMA_RECORDS = Map.ofEntries(
            Map.entry("TradeOrder", TradeOrderView.class),
            Map.entry("TradeOrderParties", TradeViews.TradeOrderPartiesView.class),
            Map.entry("TradeOrderAftersale", TradeViews.TradeOrderAftersaleView.class),
            Map.entry("TradeMerchant", TradeMerchantView.class),
            Map.entry("MediaAsset", TradeMediaView.class),
            Map.entry("TradeOrderLine", TradeOrderLineView.class),
            Map.entry("TradeDelivery", TradeDeliveryView.class),
            Map.entry("TradeAmounts", TradeAmountsView.class),
            Map.entry("TradeRefund", TradeRefundView.class),
            Map.entry("TradeOrderPage", TradeOrderPageView.class),
            Map.entry("MerchantAccount", MerchantAccountView.class),
            Map.entry("PaymentLaunch", PaymentLaunchView.class),
            Map.entry("WechatAppCall", WechatAppCallView.class),
            Map.entry("Reconciliation", ReconciliationView.class),
            Map.entry("ReconciliationRun", ReconciliationRunView.class),
            Map.entry("ReconciliationDifference", ReconciliationDifferenceView.class),
            Map.entry("ChannelCheck", ChannelCheckView.class),
            Map.entry("ChannelCheckItem", ChannelCheckItemView.class),
            Map.entry("TradePayer", TradeViews.TradePayerView.class),
            Map.entry("EnterpriseOrderSummary", TradeViews.EnterpriseOrderSummaryView.class),
            Map.entry("EnterpriseOrderCounts", TradeViews.EnterpriseOrderCountsView.class),
            Map.entry("EnterpriseOrderPage", TradeViews.EnterpriseOrderPageView.class));

    /** 请求体：字段名与控制器收的对象一致。 */
    private static final Map<String, Class<? extends Record>> REQUEST_RECORDS = Map.of(
            "SubmitOrderRequest", TradeOrderController.SubmitOrderRequest.class,
            "MerchantAccountRequest", TradePlatformController.MerchantAccountRequest.class,
            "LaunchPaymentRequest", TradeOrderController.LaunchPaymentRequest.class,
            "ShipOrderRequest", TradeMerchantController.ShipOrderRequest.class,
            "PickupRequest", TradeMerchantController.PickupRequest.class,
            "RefundRequest", TradeMerchantController.RefundRequest.class);

    private static final Map<String, List<String>> REQUEST_REQUIRED = Map.of(
            "SubmitOrderRequest", List.of("cityCode", "deliveryMode", "expectedPayableMinor", "agreementAccepted"),
            "MerchantAccountRequest", List.of("subMchid", "active"),
            "LaunchPaymentRequest", List.of("channel"),
            "ShipOrderRequest", List.of("carrierName", "trackingNo"),
            "PickupRequest", List.of("pickupCode"),
            "RefundRequest", List.of("amountMinor", "reason"));

    @Test
    void contractIsTheDraftAndDeclaresBearerJwt() {
        Map<String, Object> root = root();

        // 0.2.0：E09 接入收费活动报名（业务来源 ACTIVITY）。
        // 0.4.0：S2-1 加支付通道自检。
        // 0.5.0：S3-2 商家工作台的读取（按配送方式筛、单张订单、本机构收款账户）。
        // 0.6.0：S4-1 巡检付款节点（来源 INSPECTION）。
        // 0.7.0：S4-2、S4-4 订单的交易主体快照、渠道交易号、参与人、售后入口。
        // 0.8.0：S4-3 通用服务订单（来源 PRO_SERVICE）。
        // 0.9.0：S5-5 线上录播课（来源 COURSE）。
        // 0.10.0：S5-7、S5-8 付款主体、企业订单和注销冷静期。
        assertThat(map(root.get("info")).get("version")).isEqualTo("0.10.0");
        assertThat(map(map(root.get("components")).get("securitySchemes")).get("bearerAuth"))
                .isEqualTo(Map.of("type", "http", "scheme", "bearer", "bearerFormat", "JWT"));
        assertThat(root).doesNotContainKey("security");
        // 描述里写的“已实现”就是这里对照的五个。
        assertThat((String) map(root.get("info")).get("description"))
                .contains("submitOrder、listMyOrders、readMyOrder、cancelMyOrder")
                .contains("registerMerchantAccount")
                .contains("E05（2026-11-18）实现 confirmReceipt、listMerchantOrders、shipOrder、completePickup、refundOrder")
                .contains("E06（2026-11-18）实现 exportReconciliation 和 runReconciliation")
                // 定时补导入的时刻和 TradeReconciliationJob 的 cron、服务的 10 点规则一致。
                .contains("每小时 5 分和 35 分补导入：10 点前只补前天，10 点后补前天和昨天")
                .doesNotContain("每半小时");
        // 跑对账是写操作但不带幂等键：契约写明了为什么。
        assertThat((String) map(map(map(map(root().get("paths")).get("/api/v1/platform/trade/reconciliation/runs"))
                .get("post"))).get("description"))
                .contains("不带 Idempotency-Key：重复调用靠 redo 和导入状态保证，结果一样。");
    }

    @Test
    void theImplementedOperationsAreExactlyTheControllersRoutes() {
        Map<String, String> declared = new LinkedHashMap<>();
        map(root().get("paths")).forEach((path, item) -> map(item).forEach((method, operation) ->
                declared.put(method.toUpperCase() + " " + path, (String) map(operation).get("operationId"))));

        Set<String> implemented = new TreeSet<>();
        operations().forEach(operation -> implemented.add(operation.key()));

        assertThat(implemented).containsExactlyInAnyOrderElementsOf(IMPLEMENTED.keySet());
        IMPLEMENTED.forEach((key, operationId) -> assertThat(declared.get(key)).as(key).isEqualTo(operationId));
        assertThat(notificationRoute()).isEqualTo(NOTIFICATION);
        assertThat(declared.get(NOTIFICATION)).isEqualTo("receiveWechatNotification");
        Set<String> rest = new TreeSet<>(declared.values());
        rest.removeAll(IMPLEMENTED.values());
        rest.remove("receiveWechatNotification");
        assertThat(rest).containsExactlyInAnyOrderElementsOf(PENDING);
    }

    @Test
    void everyImplementedOperationNeedsALoginAndDeclaresTheRefusals() {
        assertThat(operations()).allSatisfy(operation -> {
            Map<String, Object> declared = declared(operation);
            assertThat(declared.get("security"))
                    .as(operation.key() + " security")
                    .isEqualTo(List.of(Map.of("bearerAuth", List.of())));
            assertThat(map(declared.get("responses")).keySet())
                    .as(operation.key() + " responses")
                    .contains("401", "403");
        });
        // 带订单或机构 ID 的操作写明 404。
        for (String key : List.of(
                "GET /api/v1/trade/orders/{orderId}",
                "POST /api/v1/trade/orders/{orderId}/cancel",
                "POST /api/v1/trade/orders/{orderId}/payments",
                "POST /api/v1/trade/orders/{orderId}/payment-check",
                "PUT /api/v1/platform/trade/merchant-accounts/{organizationId}",
                "POST /api/v1/trade/orders",
                RECEIPT, SHIPMENT, PICKUP, REFUNDS)) {
            assertThat(responses(key)).as(key).containsKey("404");
        }
        // 商家的列表只看本机构，没有 404。
        assertThat(responses(MERCHANT_ORDERS)).doesNotContainKey("404").containsKey("400");
        // 确认收货、发货、核销改订单状态：参数 400、状态不对 409、版本不对 412；退款不带版本，渠道不通 503。
        for (String key : List.of(RECEIPT, SHIPMENT, PICKUP)) {
            assertThat(responses(key)).as(key).containsKeys("400", "409", "412").doesNotContainKey("503");
        }
        assertThat(responses(REFUNDS)).containsKeys("400", "409", "503").doesNotContainKey("412");
        // 发起支付：订单状态不对、已过期 409，渠道没配置或不通 503；查单只会 503。
        assertThat(responses("POST /api/v1/trade/orders/{orderId}/payments")).containsKeys("409", "503");
        assertThat(responses("POST /api/v1/trade/orders/{orderId}/payment-check")).containsKey("503");
        assertThat(map(map(map(root().get("components")).get("responses")).get("ChannelUnavailable")).get("description"))
                .asString().contains("TRADE_CHANNEL_UNAVAILABLE");
        assertThat(responses("POST /api/v1/trade/orders")).containsKeys("400", "409");
        assertThat(responses("GET /api/v1/trade/orders")).containsKey("400");
        assertThat(responses("POST /api/v1/trade/orders/{orderId}/cancel")).containsKeys("400", "409", "412");
        assertThat(responses("GET /api/v1/trade/orders/{orderId}")).containsKey("400");
        assertThat(responses("PUT /api/v1/platform/trade/merchant-accounts/{organizationId}")).containsKey("400");
        // E06：日期、格式不对 400；读结果时没导入完 409；跑对账时没配渠道 503。都不带订单或机构 ID，没有 404。
        assertThat(responses(RECONCILIATION)).containsKeys("400", "409").doesNotContainKeys("404", "503");
        assertThat(responses(RECONCILIATION_RUNS)).containsKeys("400", "503").doesNotContainKeys("404", "409");
        assertThat((String) map(responses(RECONCILIATION).get("409")).get("description"))
                .contains("TRADE_RECONCILIATION_NOT_READY");
        // S2-1：自检只会因子商户号格式不对 400；渠道不通写在结果里，不回 503。
        assertThat(responses(CHANNEL_CHECKS)).containsKey("400").doesNotContainKeys("404", "409", "412", "503");
    }

    /**
     * E04 实现会给出的、原先契约漏写的响应，现在契约都写了。
     */
    @Test
    void responsesTheImplementationGivesAreDeclared() {
        assertThat(responses("POST /api/v1/trade/orders/{orderId}/payments")).containsKey("400");
        assertThat(responses("POST /api/v1/trade/orders/{orderId}/payment-check")).containsKey("400");
        assertThat(responses("POST /api/v1/trade/orders/{orderId}/cancel")).containsKey("503");
        assertThat(map(map(responses("POST /api/v1/trade/orders/{orderId}/payment-check").get("200")).get("headers")))
                .containsKey("ETag");
        assertThat(responses(NOTIFICATION)).containsKey("413");
    }

    @Test
    void everyParameterAndHeaderOfEveryRouteIsDeclaredInTheSamePlace() {
        assertThat(operations()).allSatisfy(operation -> {
            Map<String, Map<String, Object>> declared = new LinkedHashMap<>();
            for (Object entry : list(declared(operation).get("parameters"))) {
                Map<String, Object> parameter = resolveParameter(map(entry));
                declared.put(parameter.get("in") + " " + parameter.get("name"), parameter);
            }
            Map<String, Boolean> implemented = new LinkedHashMap<>();
            for (Parameter parameter : operation.method().getParameters()) {
                RequestParam query = parameter.getAnnotation(RequestParam.class);
                PathVariable path = parameter.getAnnotation(PathVariable.class);
                RequestHeader header = parameter.getAnnotation(RequestHeader.class);
                if (query != null) {
                    // 有默认值的查询参数在 Spring 里不是必填。
                    boolean required = query.required() && ValueConstants.DEFAULT_NONE.equals(query.defaultValue());
                    implemented.put("query " + name(query.name(), query.value(), parameter), required);
                }
                if (path != null) {
                    implemented.put("path " + name(path.name(), path.value(), parameter), true);
                }
                if (header != null) {
                    implemented.put("header " + name(header.name(), header.value(), parameter), header.required());
                }
            }
            assertThat(declared.keySet())
                    .as(operation.key() + " parameters")
                    .containsExactlyInAnyOrderElementsOf(implemented.keySet());
            implemented.forEach((key, required) -> {
                Map<String, Object> parameter = declared.get(key);
                assertThat(Boolean.TRUE.equals(parameter.get("required"))).as(operation.key() + " " + key)
                        .isEqualTo(required);
                if (key.startsWith("path ") || key.equals("header Idempotency-Key")) {
                    assertThat(map(parameter.get("schema"))).as(key).containsEntry("format", "uuid");
                }
            });
        });
    }

    @Test
    void everyRequestBodyAndSuccessResponsePointsAtTheSchemaOfItsJavaType() {
        assertThat(operations()).allSatisfy(operation -> {
            Map<String, Object> declared = declared(operation);

            Parameter body = Arrays.stream(operation.method().getParameters())
                    .filter(parameter -> parameter.isAnnotationPresent(RequestBody.class))
                    .findFirst()
                    .orElse(null);
            if (body == null) {
                assertThat(declared).as(operation.key()).doesNotContainKey("requestBody");
            } else {
                Map<String, Object> requestBody = map(declared.get("requestBody"));
                assertThat(requestBody).as(operation.key() + " requestBody").isNotNull();
                assertThat(requestBody.get("required"))
                        .as(operation.key() + " requestBody required")
                        .isEqualTo(body.getAnnotation(RequestBody.class).required());
                assertThat(jsonSchemaRef(requestBody))
                        .as(operation.key() + " requestBody schema")
                        .isEqualTo(SCHEMA_PREFIX + schemaOf(REQUEST_RECORDS, body.getType()));
            }

            Map<String, Object> responses = map(declared.get("responses"));
            List<String> success = responses.keySet().stream().filter(code -> code.startsWith("2")).toList();
            assertThat(success).as(operation.key()).isNotEmpty();
            if (operation.key().equals(RECONCILIATION)) {
                // 按 format 回 JSON 或 CSV，Java 里是 ResponseEntity<?>，下面单独对照。
                return;
            }
            assertThat(success).allSatisfy(code -> assertThat(jsonSchemaRef(map(responses.get(code))))
                    .as(operation.key() + " " + code)
                    .isEqualTo(SCHEMA_PREFIX + schemaOf(SCHEMA_RECORDS, returnedBody(operation.method()))));
        });
        // 对账结果：200 的 JSON 是 Reconciliation，CSV 是字符串；控制器 format=JSON 时返回的就是 ReconciliationView。
        Map<String, Object> exported = map(map(responses(RECONCILIATION).get("200")).get("content"));
        assertThat(exported.keySet()).containsExactlyInAnyOrder("application/json", "text/csv");
        assertThat(map(map(exported.get("application/json")).get("schema")).get("$ref"))
                .isEqualTo(SCHEMA_PREFIX + schemaOf(SCHEMA_RECORDS, ReconciliationView.class));
        assertThat(map(map(exported.get("text/csv")).get("schema"))).isEqualTo(Map.of("type", "string"));
        // CSV 下载时带的文件名头在契约里声明了。
        Map<String, Object> disposition = map(map(map(responses(RECONCILIATION).get("200")).get("headers"))
                .get("Content-Disposition"));
        assertThat(map(disposition.get("schema"))).isEqualTo(Map.of("type", "string"));
        assertThat((String) disposition.get("description"))
                .contains("attachment; filename=\"reconciliation-<date>.csv\"");
        assertThat(successCodes(RECONCILIATION)).containsExactly("200");
        assertThat(successCodes(RECONCILIATION_RUNS)).containsExactly("200");
        assertThat(successCodes(CHANNEL_CHECKS)).containsExactly("200");
        assertThat(map(responses(CHANNEL_CHECKS).get("200"))).doesNotContainKey("headers");
        assertThat(map(responses(RECONCILIATION_RUNS).get("200"))).doesNotContainKey("headers");
        // 首次提交 201，同一个幂等键重放 200；其余都是 200。
        assertThat(successCodes("POST /api/v1/trade/orders")).containsExactlyInAnyOrder("201", "200");
        for (String key : List.of(
                "GET /api/v1/trade/orders",
                "GET /api/v1/trade/orders/{orderId}",
                "POST /api/v1/trade/orders/{orderId}/cancel",
                "POST /api/v1/trade/orders/{orderId}/payments",
                "POST /api/v1/trade/orders/{orderId}/payment-check",
                "PUT /api/v1/platform/trade/merchant-accounts/{organizationId}",
                RECEIPT, MERCHANT_ORDERS, SHIPMENT, PICKUP)) {
            assertThat(successCodes(key)).as(key).containsExactly("200");
        }
        // 商家退款建一笔退款：201，不回 ETag（部分退款不改订单版本）。商家列表也不回 ETag。
        assertThat(successCodes(REFUNDS)).containsExactly("201");
        assertThat(map(responses(REFUNDS).get("201"))).doesNotContainKey("headers");
        assertThat(map(responses(MERCHANT_ORDERS).get("200"))).doesNotContainKey("headers");
        // 发起支付不改订单，不回 ETag。
        assertThat(map(responses("POST /api/v1/trade/orders/{orderId}/payments").get("200")))
                .doesNotContainKey("headers");
        // 提交、详情、取消都回 ETag，它就是取消时 If-Match 要带的版本。
        for (String[] response : new String[][] {
                {"POST /api/v1/trade/orders", "201"},
                {"POST /api/v1/trade/orders", "200"},
                {"GET /api/v1/trade/orders/{orderId}", "200"},
                {"POST /api/v1/trade/orders/{orderId}/cancel", "200"},
                {RECEIPT, "200"},
                {SHIPMENT, "200"},
                {PICKUP, "200"}}) {
            assertThat(map(map(responses(response[0]).get(response[1])).get("headers")))
                    .as(response[0] + " " + response[1])
                    .containsKey("ETag");
        }
    }

    @Test
    void everyResponseObjectHasExactlyTheFieldsTheContractRequires() {
        Map<String, Object> schemas = schemas();

        assertThat(SCHEMA_RECORDS).allSatisfy((schemaName, recordType) -> {
            Map<String, Object> schema = map(schemas.get(schemaName));
            List<String> fields = fields(recordType);

            assertThat(schema).as(schemaName).isNotNull();
            assertThat(schema.get("additionalProperties")).as(schemaName).isEqualTo(false);
            assertThat(map(schema.get("properties")).keySet())
                    .as(schemaName + " properties")
                    .containsExactlyInAnyOrderElementsOf(fields);
            assertThat(strings(schema.get("required")))
                    .as(schemaName + " required")
                    .containsExactlyInAnyOrderElementsOf(fields);
        });
    }

    @Test
    void everyRequestObjectHasTheControllersFieldsAndTheRequiredOnes() {
        Map<String, Object> schemas = schemas();

        assertThat(REQUEST_REQUIRED.keySet()).containsExactlyInAnyOrderElementsOf(REQUEST_RECORDS.keySet());
        assertThat(REQUEST_RECORDS).allSatisfy((schemaName, recordType) -> {
            Map<String, Object> schema = map(schemas.get(schemaName));

            assertThat(schema).as(schemaName).isNotNull();
            assertThat(schema.get("additionalProperties")).as(schemaName).isEqualTo(false);
            assertThat(map(schema.get("properties")).keySet())
                    .as(schemaName + " properties")
                    .containsExactlyInAnyOrderElementsOf(fields(recordType));
            assertThat(schema.get("required") == null ? List.of() : strings(schema.get("required")))
                    .as(schemaName + " required")
                    .containsExactlyInAnyOrderElementsOf(REQUEST_REQUIRED.get(schemaName));
        });
    }

    // 字段名对了还不够：类型、格式和指向的 schema 也要和 Java 类型对得上，基本类型不能声明成可空。
    @Test
    void everyFieldIsDeclaredWithTheTypeOfItsJavaComponent() {
        Map<String, Object> schemas = schemas();
        Map<String, Class<? extends Record>> records = new LinkedHashMap<>(SCHEMA_RECORDS);
        records.putAll(REQUEST_RECORDS);

        assertThat(records).allSatisfy((schemaName, recordType) -> {
            Map<String, Object> properties = map(map(schemas.get(schemaName)).get("properties"));
            for (RecordComponent component : recordType.getRecordComponents()) {
                String field = schemaName + "." + component.getName();
                Map<String, Object> property = map(properties.get(component.getName()));
                Class<?> type = component.getType();

                if (type.isRecord()) {
                    assertThat(refs(property)).as(field)
                            .contains(SCHEMA_PREFIX + schemaOf(SCHEMA_RECORDS, type));
                    continue;
                }
                List<String> declaredTypes = declaredTypes(property);
                assertThat(declaredTypes).as(field + " type").contains(jsonType(type));
                if (type.isPrimitive()) {
                    assertThat(declaredTypes).as(field + " is never null").doesNotContain("null");
                }
                if (type == UUID.class) {
                    assertThat(property.get("format")).as(field).isEqualTo("uuid");
                }
                if (type == Instant.class) {
                    assertThat(property.get("format")).as(field).isEqualTo("date-time");
                }
                if (type == LocalDate.class) {
                    assertThat(property.get("format")).as(field).isEqualTo("date");
                }
                if (type == long.class || type == Long.class) {
                    assertThat(property.get("format")).as(field).isEqualTo("int64");
                }
                if (type == List.class) {
                    Class<?> element = (Class<?>) ((ParameterizedType) component.getGenericType())
                            .getActualTypeArguments()[0];
                    Map<String, Object> items = map(property.get("items"));
                    if (element.isRecord()) {
                        assertThat(items.get("$ref")).as(field + " items")
                                .isEqualTo(SCHEMA_PREFIX + schemaOf(SCHEMA_RECORDS, element));
                    } else {
                        assertThat(items.get("format")).as(field + " items").isEqualTo("uuid");
                    }
                }
            }
        });
    }

    @Test
    void enumValuesMatchTheJavaSide() {
        Map<String, Object> schemas = schemas();

        assertThat(enumOf("TradeOrderStatus")).containsExactlyElementsOf(
                Arrays.stream(TradeOrderStatus.values()).map(Enum::name).toList());
        assertThat(enumOf("MerchantKind")).containsExactlyElementsOf(
                Arrays.stream(MallMerchantKind.values()).map(Enum::name).toList());
        assertThat(enumOf("DeliveryMode")).containsExactly("EXPRESS", "PICKUP");
        // 0.2.0：收费活动报名、到馆预约、设备租赁；0.6.0：巡检付款节点；0.8.0：通用服务订单；0.9.0：线上录播课
        // （TradeSourceOrders 的订单来源和 V005–V008、V011、V012 trade_order.source 的取值）。
        assertThat(enumOf("TradeOrderSource")).containsExactly("MALL", "ACTIVITY", "VENUE", "RENTAL", "INSPECTION", "PRO_SERVICE",
                "COURSE");
        assertThat(enumOf("TradeOrderSource").subList(1, 7))
                .containsExactlyInAnyOrderElementsOf(TradeSourceOrders.ORDER_PREFIXES.keySet());
        // 商家类型只在商城订单上有，业务来源的订单为 null；取值仍是 MerchantKind 的那几个。
        assertThat(strings(map(map(map(schemas.get("TradeMerchant")).get("properties")).get("kind")).get("enum")))
                .containsExactlyElementsOf(java.util.stream.Stream.concat(
                        Arrays.stream(MallMerchantKind.values()).map(Enum::name), java.util.stream.Stream.of((String) null))
                        .toList());
        // 每个页签服务都认，契约外的页签服务都不认。
        assertThat(enumOf("TradeOrderTab")).allSatisfy(tab -> assertThat(TradeOrderStatus.ofTab(tab)).isNotNull());
        assertThat(enumOf("TradeOrderTab")).containsExactly("ALL", "TO_PAY", "TO_SHIP", "TO_RECEIVE", "DONE", "REFUND");
        assertThat(map(map(map(schemas.get("TradeAmounts")).get("properties")).get("currency")).get("const"))
                .isEqualTo("CNY");
        assertThat(map(map(map(schemas.get("SubmitOrderRequest")).get("properties")).get("agreementAccepted"))
                .get("const")).isEqualTo(true);
        // 一期只有微信 App 支付；服务只认这一个渠道名。
        assertThat(enumOf("PaymentChannel")).containsExactly(TradePaymentService.WECHAT_APP);
        assertThat(map(map(map(schemas.get("WechatAppCall")).get("properties")).get("packageValue")).get("const"))
                .isEqualTo("Sign=WXPay");
        assertThat(enumOf("PaymentStatus")).containsExactly("CREATED", "PREPAID", "SUCCEEDED", "CLOSED", "MISMATCH");
        // S2-1：自检的模式和每一项的状态就是服务和配置自检用的那几个常量。
        assertThat(strings(map(map(map(schemas.get("ChannelCheck")).get("properties")).get("mode")).get("enum")))
                .containsExactly(PaymentChannelDiagnostics.MODE_WECHAT, PaymentChannelDiagnostics.MODE_SIMULATED,
                        PaymentChannelDiagnostics.MODE_DISABLED);
        assertThat(strings(map(map(map(schemas.get("ChannelCheckItem")).get("properties")).get("status")).get("enum")))
                .containsExactly(TradeChannelCheckService.PASS, TradeChannelCheckService.WARN,
                        TradeChannelCheckService.FAIL, TradeChannelCheckService.SKIPPED);
        // 退款单的状态（V001 trade_refund.status 的取值，服务里用的就是这几个）。
        assertThat(enumOf("RefundStatus")).containsExactly("REQUESTED", "PROCESSING", "SUCCEEDED", "CLOSED", "ABNORMAL");
        // 商家列表按订单状态过滤，取值就是订单状态。
        Map<String, Object> statusParameter = list(map(map(map(root().get("paths")).get("/api/v1/provider/trade/orders"))
                .get("get")).get("parameters")).stream()
                .map(TradeOpenApiContractTest::map)
                .filter(parameter -> "status".equals(parameter.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat(map(statusParameter.get("schema")).get("$ref")).isEqualTo(SCHEMA_PREFIX + "TradeOrderStatus");
        // E06：差异的四种类型就是对账服务写的那四种（V001 trade_reconciliation_difference.kind 的取值）；
        // 导出格式只认 JSON、CSV（控制器只认大写）。
        assertThat(strings(map(map(map(schemas.get("ReconciliationDifference")).get("properties")).get("kind")).get("enum")))
                .containsExactly("ONLY_IN_CHANNEL", "ONLY_IN_PLATFORM", "AMOUNT_MISMATCH", "STATUS_MISMATCH");
        Map<String, Object> formatParameter = list(map(map(map(root().get("paths")).get("/api/v1/platform/trade/reconciliation"))
                .get("get")).get("parameters")).stream()
                .map(TradeOpenApiContractTest::map)
                .filter(parameter -> "format".equals(parameter.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat(strings(map(formatParameter.get("schema")).get("enum"))).containsExactly("JSON", "CSV");
        // 不给 format 时控制器按 JSON；契约写明了默认值和“只认大写”。
        assertThat(map(formatParameter.get("schema"))).containsEntry("default", "JSON");
        assertThat((String) formatParameter.get("description")).contains("只认大写");
        Map<String, Object> redoParameter = list(map(map(map(root().get("paths")).get("/api/v1/platform/trade/reconciliation/runs"))
                .get("post")).get("parameters")).stream()
                .map(TradeOpenApiContractTest::map)
                .filter(parameter -> "redo".equals(parameter.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat(map(redoParameter.get("schema"))).containsEntry("type", "boolean").containsEntry("default", false);
    }

    // 契约写的长度、格式和分页上限就是服务校验的那些。
    @Test
    void limitsInTheContractAreTheOnesTheServiceEnforces() {
        Map<String, Object> submit = map(map(schemas().get("SubmitOrderRequest")).get("properties"));
        Map<String, Object> size = map(map(map(map(root().get("components")).get("parameters")).get("Size"))
                .get("schema"));

        assertThat(map(submit.get("buyerNote")).get("maxLength")).isEqualTo(TradeOrderService.MAX_NOTE_LENGTH);
        assertThat(map(submit.get("cityCode")).get("pattern")).isEqualTo("^[A-Z0-9]{4,16}$");
        assertThat(map(submit.get("quantity"))).containsEntry("minimum", 1).containsEntry("maximum", 99);
        assertThat(map(submit.get("expectedPayableMinor"))).containsEntry("minimum", 1);
        assertThat(size).containsEntry("maximum", TradeOrderService.MAX_PAGE_SIZE).containsEntry("default", 20);
        assertThat(map(map(map(root().get("components")).get("parameters")).get("Page")).get("schema"))
                .isEqualTo(Map.of("type", "integer", "minimum", 1, "maximum", TradeOrderService.MAX_PAGE, "default", 1));
        assertThat(map(map(map(schemas().get("MerchantAccountRequest")).get("properties")).get("subMchid"))
                .get("pattern")).isEqualTo("^[0-9]{8,32}$");

        // E05：物流公司和单号最长 40；取货码 6 位数字；退款至少 1 分、原因最长 80。
        Map<String, Object> ship = map(map(schemas().get("ShipOrderRequest")).get("properties"));
        assertThat(map(ship.get("carrierName")).get("maxLength")).isEqualTo(TradeOrderService.MAX_CARRIER_LENGTH);
        assertThat(map(ship.get("trackingNo")).get("maxLength")).isEqualTo(TradeOrderService.MAX_TRACKING_LENGTH);
        assertThat(map(map(map(schemas().get("PickupRequest")).get("properties")).get("pickupCode")).get("pattern"))
                .isEqualTo("^[0-9]{6}$");
        Map<String, Object> refund = map(map(schemas().get("RefundRequest")).get("properties"));
        assertThat(map(refund.get("amountMinor"))).containsEntry("minimum", 1);
        assertThat(map(refund.get("reason")).get("maxLength")).isEqualTo(TradeRefundService.MAX_REASON);
        // 服务要求物流公司、单号、退款原因去掉首尾空白后至少 1 个字（空白一律 400）。
        assertThat(map(ship.get("carrierName"))).containsEntry("minLength", 1);
        assertThat(map(ship.get("trackingNo"))).containsEntry("minLength", 1);
        assertThat(map(refund.get("reason"))).containsEntry("minLength", 1);
    }

    // 每一种交易拒绝：全局异常处理给出的状态码和错误码，契约里有，并且会给出它的操作在那个状态码下有响应。
    @Test
    void everyTradeRefusalIsDeclaredUnderTheStatusTheHandlerAnswers() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        List<String> tradeCodes = enumOf("TradeErrorCode");
        String launch = "POST /api/v1/trade/orders/{orderId}/payments";
        String check = "POST /api/v1/trade/orders/{orderId}/payment-check";
        String cancel = "POST /api/v1/trade/orders/{orderId}/cancel";
        Map<Error, List<String>> raisedBy = Map.ofEntries(
                // 买家取消已付款订单时，键用在了商家退款上也会冲突。
                Map.entry(Error.IDEMPOTENCY_CONFLICT, List.of("POST /api/v1/trade/orders", cancel, REFUNDS)),
                Map.entry(Error.AMOUNT_CHANGED, List.of("POST /api/v1/trade/orders")),
                Map.entry(Error.ADDRESS_REQUIRED, List.of("POST /api/v1/trade/orders")),
                Map.entry(Error.DELIVERY_UNAVAILABLE, List.of("POST /api/v1/trade/orders")),
                Map.entry(Error.MERCHANT_PAYMENT_UNAVAILABLE, List.of("POST /api/v1/trade/orders")),
                Map.entry(Error.ORDER_STATE_INVALID, List.of(cancel, launch, RECEIPT, SHIPMENT, PICKUP, REFUNDS)),
                Map.entry(Error.VERSION_CONFLICT, List.of(cancel, RECEIPT, SHIPMENT, PICKUP)),
                Map.entry(Error.NOT_FOUND, List.of(
                        "GET /api/v1/trade/orders/{orderId}",
                        cancel,
                        launch,
                        check,
                        "PUT /api/v1/platform/trade/merchant-accounts/{organizationId}",
                        RECEIPT, SHIPMENT, PICKUP, REFUNDS, ENTERPRISE_ORDERS)),
                Map.entry(Error.FORBIDDEN, List.copyOf(IMPLEMENTED.keySet())),
                // 0.6.0：注销冷静期内提交订单和发起支付。
                Map.entry(Error.ACCOUNT_CLOSING, List.of("POST /api/v1/trade/orders", launch)),
                // E06：读对账结果时那天还没全部导入。
                Map.entry(Error.RECONCILIATION_NOT_READY, List.of(RECONCILIATION)),
                // F4：按询价报价下单，这份报价已经有一张没关闭的订单。
                Map.entry(Error.QUOTE_ALREADY_ORDERED, List.of("POST /api/v1/trade/orders")),
                Map.entry(Error.ORDER_EXPIRED, List.of(launch)),
                // 取消有进行中支付单的订单时，微信不通也会 503。
                // 取消已付款订单要退款，渠道没配置也是 503。
                // 跑对账时没配置渠道也是 503。
                Map.entry(Error.CHANNEL_UNAVAILABLE, List.of(launch, check, cancel, REFUNDS, RECONCILIATION_RUNS)),
                Map.entry(Error.PICKUP_CODE_INVALID, List.of(PICKUP)),
                Map.entry(Error.REFUND_EXCEEDS_PAID, List.of(REFUNDS)),
                // 买家取消已付款订单也受 50 次、一年的限制。
                Map.entry(Error.REFUND_LIMIT_REACHED, List.of(REFUNDS, cancel)),
                // 只在模块内部流转；漏出来时按“订单状态不允许”回 409，只可能出在取消。
                Map.entry(Error.PAYMENT_IN_PROGRESS, List.of(cancel)),
                Map.entry(Error.REFUND_REQUIRED, List.of(cancel)));

        assertThat(raisedBy.keySet()).containsExactlyInAnyOrder(Error.values());
        for (Error error : Error.values()) {
            ResponseEntity<ApiError> answer = handler.handleTrade(new TradeException(error), new MockHttpServletRequest());
            String status = Integer.toString(answer.getStatusCode().value());
            String code = answer.getBody().code();

            assertThat(code).as(error.name()).isEqualTo(TradeOrderControllerTest.REJECTIONS.get(error).code());
            if (code.startsWith("TRADE_")) {
                assertThat(tradeCodes).as(error.name()).contains(code);
            }
            for (String key : raisedBy.get(error)) {
                assertThat(responses(key)).as(key + " " + error).containsKey(status);
            }
        }
        assertThat((String) map(map(map(root().get("components")).get("responses")).get("VersionConflict"))
                .get("description")).contains("TRADE_VERSION_CONFLICT");
    }

    // F4：按询价报价下单时，商城的询价拒绝（不是选定的、过期了）也原样透出。
    @Test
    void theInquiryRefusalsASubmissionCanMeetAreDeclared() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        List<String> codes = new ArrayList<>();
        for (com.lowaltitude.inspection.mall.application.MallInquiryException.Error error : List.of(
                com.lowaltitude.inspection.mall.application.MallInquiryException.Error.QUOTE_NOT_CHOSEN,
                com.lowaltitude.inspection.mall.application.MallInquiryException.Error.QUOTE_EXPIRED)) {
            ResponseEntity<ApiError> answer = handler.handleMallInquiry(
                    new com.lowaltitude.inspection.mall.application.MallInquiryException(error),
                    new MockHttpServletRequest());
            assertThat(answer.getStatusCode().value()).isEqualTo(409);
            codes.add(answer.getBody().code());
        }

        assertThat(enumOf("TradeErrorCode")).containsAll(codes);
        assertThat(map(map(map(schemas().get("SubmitOrderRequest")).get("properties")).get("inquiryQuoteId")))
                .containsEntry("format", "uuid");
    }

    // 提交时商城报价的拒绝原样透出，契约的错误码里写着这三个。
    @Test
    void theMallRefusalsASubmissionCanMeetAreDeclared() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        List<String> codes = new ArrayList<>();
        for (MallCartException.Error error : List.of(
                MallCartException.Error.MULTIPLE_MERCHANTS,
                MallCartException.Error.ITEM_UNAVAILABLE,
                MallCartException.Error.NOT_PURCHASABLE)) {
            ResponseEntity<ApiError> answer = handler.handleMallCart(
                    new MallCartException(error), new MockHttpServletRequest());
            assertThat(answer.getStatusCode().value()).isEqualTo(409);
            codes.add(answer.getBody().code());
        }

        assertThat(enumOf("TradeErrorCode")).containsAll(codes);
    }

    // 公开的微信回调：不登录；契约声明的四个微信头、请求体、204 / 401 / 500，失败体是微信要的格式。
    @Test
    void theWechatNotificationMatchesItsController() throws Exception {
        Map<String, Object> declared = map(map(map(root().get("paths"))
                .get("/api/v1/trade/channels/wechat/notifications")).get("post"));

        assertThat(declared.get("security")).isEqualTo(List.of());
        Map<String, Boolean> headers = new LinkedHashMap<>();
        for (Object entry : list(declared.get("parameters"))) {
            Map<String, Object> parameter = resolveParameter(map(entry));
            assertThat(parameter.get("in")).isEqualTo("header");
            headers.put((String) parameter.get("name"), (Boolean) parameter.get("required"));
        }
        assertThat(headers).containsExactly(
                Map.entry("Wechatpay-Serial", true),
                Map.entry("Wechatpay-Signature", true),
                Map.entry("Wechatpay-Timestamp", true),
                Map.entry("Wechatpay-Nonce", true));
        assertThat(jsonSchemaRef(map(declared.get("requestBody")))).isEqualTo(SCHEMA_PREFIX + "WechatNotification");
        Map<String, Object> responses = map(declared.get("responses"));
        assertThat(responses.keySet()).containsExactlyInAnyOrder("204", "401", "413", "500");
        assertThat(map(responses.get("204"))).doesNotContainKey("content");
        for (String code : List.of("401", "413", "500")) {
            assertThat(jsonSchemaRef(map(responses.get(code)))).as(code).isEqualTo(SCHEMA_PREFIX + "WechatFailure");
        }

        // 控制器回的失败体正好是 WechatFailure 的字段，code 是 FAIL。
        Map<String, Object> failure = map(schemas().get("WechatFailure"));
        TradePaymentService payments = org.mockito.Mockito.mock(TradePaymentService.class);
        org.mockito.Mockito.when(payments.handleNotification(
                        org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(NotificationResult.UNTRUSTED);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", NOTIFICATION.split(" ")[1]);
        request.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ResponseEntity<Map<String, String>> answer = new TradeWechatNotificationController(payments).receive(request);

        assertThat(answer.getStatusCode().value()).isEqualTo(401);
        assertThat(answer.getBody().keySet())
                .containsExactlyInAnyOrderElementsOf(map(failure.get("properties")).keySet())
                .containsExactlyInAnyOrderElementsOf(strings(failure.get("required")));
        assertThat(answer.getBody().get("code"))
                .isEqualTo(map(map(failure.get("properties")).get("code")).get("const"));
    }

    // ---- 控制器一侧 ----

    private static String notificationRoute() {
        for (Method method : TradeWechatNotificationController.class.getDeclaredMethods()) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping != null) {
                return mapping.method()[0].name() + " " + mapping.path()[0];
            }
        }
        throw new AssertionError("the notification controller has no route");
    }

    private record Operation(String httpMethod, String path, Method method) {
        String key() {
            return httpMethod + " " + path;
        }
    }

    private static List<Operation> operations() {
        List<Operation> operations = new ArrayList<>();
        for (Class<?> controller : List.of(
                TradeOrderController.class, TradePlatformController.class, TradeMerchantController.class,
                TradeEnterpriseOrderController.class)) {
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping != null) {
                    assertThat(mapping.path()).as(method.getName()).hasSize(1);
                    assertThat(mapping.method()).as(method.getName()).hasSize(1);
                    operations.add(new Operation(mapping.method()[0].name(), mapping.path()[0], method));
                }
            }
        }
        return operations;
    }

    private static String name(String name, String value, Parameter parameter) {
        if (!name.isEmpty()) {
            return name;
        }
        return value.isEmpty() ? parameter.getName() : value;
    }

    private static Class<?> returnedBody(Method method) {
        Type type = method.getGenericReturnType();
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == ResponseEntity.class) {
            return (Class<?>) parameterized.getActualTypeArguments()[0];
        }
        return method.getReturnType();
    }

    private static List<String> fields(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    private static String schemaOf(Map<String, ? extends Class<?>> registered, Class<?> type) {
        return registered.entrySet().stream()
                .filter(entry -> entry.getValue() == type)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new AssertionError(type.getSimpleName() + " has no schema in this test"));
    }

    private static String jsonType(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
            return "integer";
        }
        if (type == String.class || type == UUID.class || type == Instant.class || type == LocalDate.class
                || type.isEnum()) {
            return "string";
        }
        if (type == List.class) {
            return "array";
        }
        throw new AssertionError("no JSON type rule for " + type.getName());
    }

    // ---- 契约一侧 ----

    private static String contractText() {
        try {
            return Files.readString(CONTRACT);
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private static Map<String, Object> root() {
        return new Yaml().load(contractText());
    }

    private static Map<String, Object> schemas() {
        return map(map(root().get("components")).get("schemas"));
    }

    private static List<String> enumOf(String schemaName) {
        return strings(map(schemas().get(schemaName)).get("enum"));
    }

    private static Map<String, Object> declared(Operation operation) {
        Map<String, Object> item = map(map(root().get("paths")).get(operation.path()));
        assertThat(item).as(operation.key()).isNotNull();
        Map<String, Object> declared = map(item.get(operation.httpMethod().toLowerCase()));
        assertThat(declared).as(operation.key()).isNotNull();
        return declared;
    }

    private static Map<String, Object> responses(String key) {
        String[] parts = key.split(" ", 2);
        return map(map(map(map(root().get("paths")).get(parts[1])).get(parts[0].toLowerCase())).get("responses"));
    }

    private static List<String> successCodes(String key) {
        return responses(key).keySet().stream().filter(code -> code.startsWith("2")).toList();
    }

    private static Map<String, Object> resolveParameter(Map<String, Object> parameter) {
        Object ref = parameter.get("$ref");
        if (ref == null) {
            return parameter;
        }
        String name = ((String) ref).substring("#/components/parameters/".length());
        return map(map(map(root().get("components")).get("parameters")).get(name));
    }

    private static String jsonSchemaRef(Map<String, Object> bodyOrResponse) {
        Map<String, Object> content = map(bodyOrResponse.get("content"));
        return (String) map(map(content.get("application/json")).get("schema")).get("$ref");
    }

    /** 属性直接的 $ref，或 oneOf 里的 $ref（可空的对象写成 oneOf: [$ref, null]）。 */
    private static List<String> refs(Map<String, Object> property) {
        List<String> refs = new ArrayList<>();
        if (property.get("$ref") != null) {
            refs.add((String) property.get("$ref"));
        }
        for (Object option : list(property.get("oneOf"))) {
            Object ref = map(option).get("$ref");
            if (ref != null) {
                refs.add((String) ref);
            }
        }
        return refs;
    }

    /** 属性的 JSON 类型；指向枚举 schema 的 $ref 取那个 schema 的类型。 */
    private static List<String> declaredTypes(Map<String, Object> property) {
        Object ref = property.get("$ref");
        if (ref != null) {
            return declaredTypes(map(schemas().get(((String) ref).substring(SCHEMA_PREFIX.length()))));
        }
        Object type = property.get("type");
        return type instanceof String single ? List.of(single) : strings(type);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value == null ? List.of() : (List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return (List<String>) value;
    }
}
