package com.lowaltitude.inspection.trade.application;

import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.account.application.CustomerAccount;
import com.lowaltitude.inspection.trade.application.TradeViews.PaymentLaunchView;
import com.lowaltitude.inspection.trade.application.TradeViews.SubmitResult;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeOrderPageView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeOrderView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 买家的订单接口（契约 contracts/openapi-trade.yaml）：E02 提交、列表、详情、取消；E04 发起支付、查一次单，
 * 取消改由支付服务处理（发起过支付的要先查单）；E05 确认收货，已付款未发货的取消即退款（幂等键用于退款）。
 * 要登录和客户角色；买家只从令牌里取，不是自己的订单一律当作不存在。
 */
@RestController
public final class TradeOrderController {

    private final TradeOrderService service;
    private final TradePaymentService payments;

    public TradeOrderController(TradeOrderService service, TradePaymentService payments) {
        this.service = service;
        this.payments = payments;
    }

    @PostMapping("/api/v1/trade/orders")
    public ResponseEntity<TradeOrderView> submit(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody SubmitOrderRequest request
    ) {
        SubmitResult result = service.submit(
                CustomerAccount.of(authentication),
                idempotencyKey,
                new TradeOrderService.SubmitCommand(
                        request.cityCode(),
                        request.cartItemIds(),
                        request.offerId(),
                        request.quantity(),
                        request.deliveryMode(),
                        request.addressId(),
                        request.buyerNote(),
                        request.expectedPayableMinor(),
                        request.agreementAccepted()));
        return ResponseEntity.status(result.created() ? 201 : 200)
                .eTag(Integer.toString(result.order().version()))
                .body(result.order());
    }

    @GetMapping("/api/v1/trade/orders")
    public TradeOrderPageView list(
            Authentication authentication,
            @RequestParam(required = false) String tab,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.list(CustomerAccount.of(authentication), tab, page, size);
    }

    @GetMapping("/api/v1/trade/orders/{orderId}")
    public ResponseEntity<TradeOrderView> read(Authentication authentication, @PathVariable UUID orderId) {
        TradeOrderView order = service.read(CustomerAccount.of(authentication), orderId);
        return ResponseEntity.ok().eTag(Integer.toString(order.version())).body(order);
    }

    @PostMapping("/api/v1/trade/orders/{orderId}/cancel")
    public ResponseEntity<TradeOrderView> cancel(
            Authentication authentication,
            @PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestHeader("If-Match") String ifMatch
    ) {
        TradeOrderView order = payments.cancel(CustomerAccount.of(authentication), orderId, version(ifMatch), idempotencyKey);
        return ResponseEntity.ok().eTag(Integer.toString(order.version())).body(order);
    }

    /** 买家确认收货：已发货 → 已完成。 */
    @PostMapping("/api/v1/trade/orders/{orderId}/receipt")
    public ResponseEntity<TradeOrderView> confirmReceipt(
            Authentication authentication,
            @PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestHeader("If-Match") String ifMatch
    ) {
        TradeOrderView order = service.confirmReceipt(CustomerAccount.of(authentication), orderId, version(ifMatch));
        return ResponseEntity.ok().eTag(Integer.toString(order.version())).body(order);
    }

    /** 发起微信支付，返回 App 调起要的参数。同一张单已有进行中的支付时沿用它。 */
    @PostMapping("/api/v1/trade/orders/{orderId}/payments")
    public PaymentLaunchView launchPayment(
            Authentication authentication,
            @PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody LaunchPaymentRequest request
    ) {
        return payments.launch(CustomerAccount.of(authentication), orderId, idempotencyKey, request.channel());
    }

    /** App 从微信回来后调：服务端向微信查一次单，按结果推进订单。App 说的支付结果一概不信。 */
    @PostMapping("/api/v1/trade/orders/{orderId}/payment-check")
    public ResponseEntity<TradeOrderView> checkPayment(Authentication authentication, @PathVariable UUID orderId) {
        TradeOrderView order = payments.check(CustomerAccount.of(authentication), orderId);
        return ResponseEntity.ok().eTag(Integer.toString(order.version())).body(order);
    }

    /** If-Match 是详情返回的 ETag：可能带引号或 W/ 前缀。 */
    static int version(String ifMatch) {
        String value = ifMatch == null ? "" : ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        value = value.replace("\"", "");
        try {
            int version = Integer.parseInt(value);
            if (version < 0) {
                throw new IllegalArgumentException("If-Match is invalid");
            }
            return version;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("If-Match is invalid", invalid);
        }
    }

    record LaunchPaymentRequest(String channel) {
    }

    record SubmitOrderRequest(
            String cityCode,
            List<UUID> cartItemIds,
            UUID offerId,
            Integer quantity,
            String deliveryMode,
            UUID addressId,
            String buyerNote,
            Long expectedPayableMinor,
            Boolean agreementAccepted
    ) {
    }
}
