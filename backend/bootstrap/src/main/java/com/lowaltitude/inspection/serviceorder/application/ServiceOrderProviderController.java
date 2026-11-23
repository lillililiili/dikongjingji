package com.lowaltitude.inspection.serviceorder.application;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.PlanDraft;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderFileView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderPageView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderView;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 服务商的服务订单（S4-3，工作台 apps/admin）：出方案、开工、上传交付文件、交付、开工前取消、开工后协商终止。
 * 要服务商操作员角色和令牌里的机构；只看、只改服务商是本机构的订单。客户只有组织名和经办人显示名，没有手机号。
 */
@RestController
public final class ServiceOrderProviderController {

    private final ServiceOrderService service;

    public ServiceOrderProviderController(ServiceOrderService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/provider/service-orders")
    public ServiceOrderPageView orders(
            Authentication authentication,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.listForProvider(ServiceOrderSubjects.providerOperator(authentication).organizationId(), status,
                page, size);
    }

    @GetMapping("/api/v1/provider/service-orders/{orderId}")
    public ResponseEntity<ServiceOrderView> order(Authentication authentication, @PathVariable UUID orderId) {
        return ServiceOrderController.withVersion(service.readForProvider(
                ServiceOrderSubjects.providerOperator(authentication).organizationId(), orderId));
    }

    @PutMapping("/api/v1/provider/service-orders/{orderId}/plan")
    public ResponseEntity<ServiceOrderView> plan(
            Authentication authentication,
            @PathVariable UUID orderId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody PlanRequest request
    ) {
        ServiceOrderSubjects.Subject subject = ServiceOrderSubjects.providerOperator(authentication);
        return ServiceOrderController.withVersion(service.issuePlan(subject.organizationId(), subject.subjectId(),
                orderId, ServiceOrderSubjects.version(ifMatch),
                new PlanDraft(request.schedule(), request.terms(), request.note())));
    }

    @PostMapping("/api/v1/provider/service-orders/{orderId}/start")
    public ResponseEntity<ServiceOrderView> start(
            Authentication authentication,
            @PathVariable UUID orderId,
            @RequestHeader("If-Match") String ifMatch
    ) {
        ServiceOrderSubjects.Subject subject = ServiceOrderSubjects.providerOperator(authentication);
        return ServiceOrderController.withVersion(service.start(subject.organizationId(), subject.subjectId(),
                orderId, ServiceOrderSubjects.version(ifMatch)));
    }

    @PostMapping(path = "/api/v1/provider/service-orders/{orderId}/files",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ServiceOrderFileView upload(
            Authentication authentication,
            @PathVariable UUID orderId,
            @RequestPart("file") MultipartFile file
    ) throws IOException {
        ServiceOrderSubjects.Subject subject = ServiceOrderSubjects.providerOperator(authentication);
        if (file.getSize() > ServiceOrderService.MAX_FILE_BYTES) {
            throw new ServiceOrderException(ServiceOrderException.Error.FILE_INVALID);
        }
        return service.uploadFile(subject.organizationId(), subject.subjectId(), orderId, file.getOriginalFilename(),
                file.getBytes());
    }

    @DeleteMapping("/api/v1/provider/service-orders/{orderId}/files/{fileId}")
    public ResponseEntity<Void> remove(
            Authentication authentication,
            @PathVariable UUID orderId,
            @PathVariable UUID fileId
    ) {
        service.removeFile(ServiceOrderSubjects.providerOperator(authentication).organizationId(), orderId, fileId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/provider/service-orders/{orderId}/files/{fileId}/content")
    public ResponseEntity<byte[]> content(
            Authentication authentication,
            @PathVariable UUID orderId,
            @PathVariable UUID fileId
    ) {
        return ServiceOrderController.attachment(service.readProviderFile(
                ServiceOrderSubjects.providerOperator(authentication).organizationId(), orderId, fileId));
    }

    @PostMapping("/api/v1/provider/service-orders/{orderId}/delivery")
    public ResponseEntity<ServiceOrderView> deliver(
            Authentication authentication,
            @PathVariable UUID orderId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody DeliveryRequest request
    ) {
        ServiceOrderSubjects.Subject subject = ServiceOrderSubjects.providerOperator(authentication);
        return ServiceOrderController.withVersion(service.deliver(subject.organizationId(), subject.subjectId(),
                orderId, ServiceOrderSubjects.version(ifMatch), request.note(), request.fileIds()));
    }

    @PostMapping("/api/v1/provider/service-orders/{orderId}/cancel")
    public ResponseEntity<ServiceOrderView> cancel(
            Authentication authentication,
            @PathVariable UUID orderId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody ReasonRequest request
    ) {
        ServiceOrderSubjects.Subject subject = ServiceOrderSubjects.providerOperator(authentication);
        return ServiceOrderController.withVersion(service.cancelByProvider(subject.organizationId(),
                subject.subjectId(), orderId, ServiceOrderSubjects.version(ifMatch), request.reason()));
    }

    @PostMapping("/api/v1/provider/service-orders/{orderId}/termination")
    public ResponseEntity<ServiceOrderView> terminate(
            Authentication authentication,
            @PathVariable UUID orderId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody TerminateRequest request
    ) {
        ServiceOrderSubjects.Subject subject = ServiceOrderSubjects.providerOperator(authentication);
        return ServiceOrderController.withVersion(service.terminate(subject.organizationId(), subject.subjectId(),
                orderId, ServiceOrderSubjects.version(ifMatch), request.refundAmountMinor(), request.reason()));
    }

    record PlanRequest(String schedule, List<String> terms, String note) {
    }

    record DeliveryRequest(String note, List<UUID> fileIds) {
    }

    record ReasonRequest(String reason) {
    }

    /** refundAmountMinor：退给客户的金额（分），0 到实付。 */
    record TerminateRequest(Long refundAmountMinor, String reason) {
    }
}
