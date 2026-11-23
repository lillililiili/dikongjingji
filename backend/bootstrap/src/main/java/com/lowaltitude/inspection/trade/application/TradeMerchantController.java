package com.lowaltitude.inspection.trade.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import com.lowaltitude.inspection.trade.application.TradeViews.MerchantAccountView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeOrderPageView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeOrderView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeRefundView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家操作员的交易接口（契约 contracts/openapi-trade.yaml，E05）：本机构卖出的订单、发货、自提核销、退款。
 * 0.5.0（S3-2）起给运营端的商家工作台用（apps/admin），并加了按配送方式筛、单张订单和本机构收款账户的读取。
 * 要 PROVIDER_OPERATOR 角色；机构只从令牌里取，别的机构的订单一律当作不存在。
 */
@RestController
public final class TradeMerchantController {

    private static final String PROVIDER_OPERATOR = "PROVIDER_OPERATOR";

    private final TradeOrderService orders;
    private final TradeRefundService refunds;

    public TradeMerchantController(TradeOrderService orders, TradeRefundService refunds) {
        this.orders = orders;
        this.refunds = refunds;
    }

    @GetMapping("/api/v1/provider/trade/orders")
    public TradeOrderPageView list(
            Authentication authentication,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String deliveryMode,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return orders.listForMerchant(operator(authentication).organizationId(), status, deliveryMode, page, size);
    }

    @GetMapping("/api/v1/provider/trade/orders/{orderId}")
    public ResponseEntity<TradeOrderView> read(Authentication authentication, @PathVariable UUID orderId) {
        TradeOrderView order = orders.readForMerchant(operator(authentication).organizationId(), orderId);
        return ResponseEntity.ok().eTag(Integer.toString(order.version())).body(order);
    }

    @GetMapping("/api/v1/provider/trade/merchant-account")
    public MerchantAccountView merchantAccount(Authentication authentication) {
        return orders.readMerchantAccount(operator(authentication).organizationId());
    }

    @PostMapping("/api/v1/provider/trade/orders/{orderId}/shipment")
    public ResponseEntity<TradeOrderView> ship(
            Authentication authentication,
            @PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody ShipOrderRequest request
    ) {
        Operator operator = operator(authentication);
        TradeOrderView order = orders.ship(operator.organizationId(), operator.userId(), orderId,
                TradeOrderController.version(ifMatch), request.carrierName(), request.trackingNo());
        return ResponseEntity.ok().eTag(Integer.toString(order.version())).body(order);
    }

    @PostMapping("/api/v1/provider/trade/orders/{orderId}/pickup")
    public ResponseEntity<TradeOrderView> pickup(
            Authentication authentication,
            @PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody PickupRequest request
    ) {
        Operator operator = operator(authentication);
        TradeOrderView order = orders.completePickup(operator.organizationId(), operator.userId(), orderId,
                TradeOrderController.version(ifMatch), request.pickupCode());
        return ResponseEntity.ok().eTag(Integer.toString(order.version())).body(order);
    }

    @PostMapping("/api/v1/provider/trade/orders/{orderId}/refunds")
    public ResponseEntity<TradeRefundView> refund(
            Authentication authentication,
            @PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody RefundRequest request
    ) {
        Operator operator = operator(authentication);
        TradeRefundView refund = refunds.merchantRefund(operator.organizationId(), operator.userId(), orderId,
                idempotencyKey, request.amountMinor(), request.reason());
        return ResponseEntity.status(HttpStatus.CREATED).body(refund);
    }

    private static Operator operator(Authentication authentication) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        UUID userId;
        Set<String> roles;
        UUID organizationId;
        if (principal instanceof CurrentSubject current) {
            userId = current.subjectId();
            roles = current.roles();
            organizationId = current.organizationId();
        } else if (principal instanceof Jwt jwt) {
            try {
                List<String> claim = jwt.getClaimAsStringList("roles");
                String organization = jwt.getClaimAsString("organizationId");
                userId = UUID.fromString(jwt.getClaimAsString("subjectId"));
                roles = claim == null ? Set.of() : Set.copyOf(claim);
                organizationId = organization == null || organization.isBlank() ? null : UUID.fromString(organization);
            } catch (RuntimeException invalidClaim) {
                throw new AccessDeniedException("authenticated subject is invalid", invalidClaim);
            }
        } else {
            throw new AccessDeniedException("authenticated subject is invalid");
        }
        if (!roles.contains(PROVIDER_OPERATOR) || organizationId == null) {
            throw new AccessDeniedException("provider operator of an organization is required");
        }
        return new Operator(userId, organizationId);
    }

    private record Operator(UUID userId, UUID organizationId) {
    }

    record ShipOrderRequest(String carrierName, String trackingNo) {
    }

    record PickupRequest(String pickupCode) {
    }

    record RefundRequest(Long amountMinor, String reason) {
    }
}
