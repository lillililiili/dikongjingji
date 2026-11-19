package com.lowaltitude.inspection.rental.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 出租方（商家）的询价单、报价和租赁订单（F3，受权接口，没有页面；开发脚本 infra/scripts/dev-rental-merchant.cjs）。
 * 要服务商操作员角色和令牌里的机构；只看、只改出租方是本机构的。客户手机号在付款之前打码。
 */
@RestController
public final class RentalMerchantController {

    private final RentalService service;

    public RentalMerchantController(RentalService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/provider/rental-inquiries")
    public RentalInquiryPageView inquiries(
            Authentication authentication,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.merchantInquiries(RentalSubjects.providerOperator(authentication).organizationId(), status,
                page, size);
    }

    @GetMapping("/api/v1/provider/rental-inquiries/{inquiryId}")
    public RentalInquiryView inquiry(Authentication authentication, @PathVariable UUID inquiryId) {
        return service.merchantInquiry(RentalSubjects.providerOperator(authentication).organizationId(), inquiryId);
    }

    /** 报价或改价（每次是新的一版）。 */
    @PutMapping("/api/v1/provider/rental-inquiries/{inquiryId}/quote")
    public RentalInquiryView quote(
            Authentication authentication,
            @PathVariable UUID inquiryId,
            @RequestBody QuoteRequest request
    ) {
        RentalSubjects.Subject subject = RentalSubjects.providerOperator(authentication);
        List<RentalFeeDraft> fees = request.fees() == null ? null : request.fees().stream()
                .map(fee -> fee == null ? null : new RentalFeeDraft(fee.name(), fee.note(), fee.amountMinor()))
                .toList();
        return service.quote(subject.organizationId(), subject.subjectId(), inquiryId, new RentalQuoteDraft(
                request.dailyRentMinor(), request.days(), request.quantity(), fees, request.scheduleAvailable(),
                request.validUntil(), request.terms()));
    }

    @PostMapping("/api/v1/provider/rental-inquiries/{inquiryId}/decline")
    public RentalInquiryView decline(
            Authentication authentication,
            @PathVariable UUID inquiryId,
            @RequestBody ReasonRequest request
    ) {
        RentalSubjects.Subject subject = RentalSubjects.providerOperator(authentication);
        return service.decline(subject.organizationId(), subject.subjectId(), inquiryId, request.reason());
    }

    @GetMapping("/api/v1/provider/rental-orders")
    public RentalOrderPageView orders(
            Authentication authentication,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.merchantOrders(RentalSubjects.providerOperator(authentication).organizationId(), status,
                page, size);
    }

    @GetMapping("/api/v1/provider/rental-orders/{orderId}")
    public RentalOrderView order(Authentication authentication, @PathVariable UUID orderId) {
        return service.merchantOrder(RentalSubjects.providerOperator(authentication).organizationId(), orderId);
    }

    @PostMapping("/api/v1/provider/rental-orders/{orderId}/handover")
    public RentalOrderView handover(
            Authentication authentication,
            @PathVariable UUID orderId,
            @RequestBody RecordRequest request
    ) {
        RentalSubjects.Subject subject = RentalSubjects.providerOperator(authentication);
        return service.recordHandover(subject.organizationId(), subject.subjectId(), orderId,
                new RentalRecordDraft(request.occurredAt(), request.note()));
    }

    @PostMapping("/api/v1/provider/rental-orders/{orderId}/return")
    public RentalOrderView giveBack(
            Authentication authentication,
            @PathVariable UUID orderId,
            @RequestBody RecordRequest request
    ) {
        RentalSubjects.Subject subject = RentalSubjects.providerOperator(authentication);
        return service.recordReturn(subject.organizationId(), subject.subjectId(), orderId,
                new RentalRecordDraft(request.occurredAt(), request.note()));
    }

    /** 交接之前取消订单，整单原路退款。 */
    @PostMapping("/api/v1/provider/rental-orders/{orderId}/cancel")
    public RentalOrderView cancel(
            Authentication authentication,
            @PathVariable UUID orderId,
            @RequestBody ReasonRequest request
    ) {
        RentalSubjects.Subject subject = RentalSubjects.providerOperator(authentication);
        return service.cancel(subject.organizationId(), subject.subjectId(), orderId, request.reason());
    }

    /**
     * @param validUntil 报价有效期：晚于现在，不超过 7 天
     */
    record QuoteRequest(
            Long dailyRentMinor,
            Integer days,
            Integer quantity,
            List<FeeRequest> fees,
            Boolean scheduleAvailable,
            OffsetDateTime validUntil,
            String terms
    ) {
    }

    record FeeRequest(String name, String note, Long amountMinor) {
    }

    record ReasonRequest(String reason) {
    }

    /** occurredAt 不填时是现在。 */
    record RecordRequest(OffsetDateTime occurredAt, String note) {
    }
}
