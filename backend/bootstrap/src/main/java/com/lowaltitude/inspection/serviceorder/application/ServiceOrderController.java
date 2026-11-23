package com.lowaltitude.inspection.serviceorder.application;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.DownloadIntentView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderFileContent;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderPageView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderPaymentStartView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderView;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
 * 客户的服务订单（S4-3，契约 openapi-service-order.yaml 1.0.0）：订单、确认方案、付款、取消、验收、退回整改、下载交付文件。
 * 要客户角色；账号只从令牌里取。返回单张订单的接口都带 ETag（订单版本），写操作带 If-Match。
 * 交付文件的短时链接 /api/v1/service-order-files/{token} 不要登录（令牌就是授权）。
 */
@RestController
public final class ServiceOrderController {

    private final ServiceOrderService service;

    public ServiceOrderController(ServiceOrderService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/service-orders")
    public ServiceOrderPageView orders(
            Authentication authentication,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.listMine(ServiceOrderSubjects.customer(authentication).subjectId(), status, page, size);
    }

    @GetMapping("/api/v1/service-orders/{orderId}")
    public ResponseEntity<ServiceOrderView> order(Authentication authentication, @PathVariable UUID orderId) {
        return withVersion(service.readMine(ServiceOrderSubjects.customer(authentication).subjectId(), orderId));
    }

    @PostMapping("/api/v1/service-orders/{orderId}/plan-confirmation")
    public ResponseEntity<ServiceOrderView> confirmPlan(
            Authentication authentication,
            @PathVariable UUID orderId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody PlanConfirmRequest request
    ) {
        return withVersion(service.confirmPlan(ServiceOrderSubjects.customer(authentication).subjectId(), orderId,
                ServiceOrderSubjects.version(ifMatch), request.planVersion(), request.agreed()));
    }

    @PostMapping("/api/v1/service-orders/{orderId}/payments")
    public ServiceOrderPaymentStartView pay(Authentication authentication, @PathVariable UUID orderId) {
        return service.startPayment(ServiceOrderSubjects.customer(authentication).subjectId(), orderId);
    }

    @PostMapping("/api/v1/service-orders/{orderId}/cancel")
    public ResponseEntity<ServiceOrderView> cancel(
            Authentication authentication,
            @PathVariable UUID orderId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody CustomerCancelRequest request
    ) {
        return withVersion(service.cancelByCustomer(ServiceOrderSubjects.customer(authentication).subjectId(),
                orderId, ServiceOrderSubjects.version(ifMatch), request.reason()));
    }

    @PostMapping("/api/v1/service-orders/{orderId}/acceptance")
    public ResponseEntity<ServiceOrderView> accept(
            Authentication authentication,
            @PathVariable UUID orderId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody AcceptRequest request
    ) {
        return withVersion(service.accept(ServiceOrderSubjects.customer(authentication).subjectId(), orderId,
                ServiceOrderSubjects.version(ifMatch), request.round()));
    }

    @PostMapping("/api/v1/service-orders/{orderId}/rework")
    public ResponseEntity<ServiceOrderView> rework(
            Authentication authentication,
            @PathVariable UUID orderId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody ReworkRequest request
    ) {
        return withVersion(service.rework(ServiceOrderSubjects.customer(authentication).subjectId(), orderId,
                ServiceOrderSubjects.version(ifMatch), request.round(), request.reason()));
    }

    @PostMapping("/api/v1/service-orders/{orderId}/files/{fileId}/download-intents")
    public DownloadIntentView downloadIntent(
            Authentication authentication,
            @PathVariable UUID orderId,
            @PathVariable UUID fileId
    ) {
        return service.createDownload(ServiceOrderSubjects.customer(authentication).subjectId(), orderId, fileId);
    }

    /** 不要登录：令牌 10 分钟内有效。 */
    @GetMapping("/api/v1/service-order-files/{token}")
    public ResponseEntity<byte[]> download(@PathVariable UUID token) {
        return attachment(service.download(token));
    }

    static ResponseEntity<byte[]> attachment(ServiceOrderFileContent content) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(content.name(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(content.content());
    }

    static ResponseEntity<ServiceOrderView> withVersion(ServiceOrderView order) {
        return ResponseEntity.ok().eTag(Integer.toString(order.version())).body(order);
    }

    /** agreed：勾选了“我已核对方案的服务范围、金额与交付约定”。 */
    record PlanConfirmRequest(Integer planVersion, Boolean agreed) {
    }

    record CustomerCancelRequest(String reason) {
    }

    record AcceptRequest(Integer round) {
    }

    record ReworkRequest(Integer round, String reason) {
    }
}
