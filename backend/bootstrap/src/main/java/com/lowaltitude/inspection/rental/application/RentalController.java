package com.lowaltitude.inspection.rental.application;

import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.IdentityProfile;
import com.lowaltitude.inspection.identity.domain.IdentityProfilePort;
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
 * 客户的租赁询价、合同确认和租赁订单（F3，契约 contracts/openapi-rental.yaml 1.0.0）。都要登录和客户角色；
 * 账号只从令牌里取，不是自己的询价单和订单一律当作不存在。返回询价单的接口带 ETag（询价单版本），确认合同时用 If-Match 带回来。
 */
@RestController
public final class RentalController {

    private static final String CUSTOMER_ORGANIZATION = "CUSTOMER";

    private final RentalService service;
    private final IdentityProfilePort profiles;

    public RentalController(RentalService service, IdentityProfilePort profiles) {
        this.service = service;
        this.profiles = profiles;
    }

    /** 提交询价单。第一次 201，同一个幂等键重放 200。 */
    @PostMapping("/api/v1/rental-inquiries")
    public ResponseEntity<RentalInquiryView> submit(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody InquiryRequest request
    ) {
        RentalSubjects.Subject subject = RentalSubjects.customer(authentication);
        RentalInquiryCreated created = service.submit(subject.subjectId(), lessee(subject), idempotencyKey,
                new RentalInquiryDraft(request.offerId(), request.quantity(), request.startDate(), request.days(),
                        request.handoverMode(), request.returnMode(), request.addressId(), request.note()));
        return withVersion(ResponseEntity.status(created.created() ? 201 : 200), created.inquiry());
    }

    @GetMapping("/api/v1/rental-inquiries")
    public RentalInquiryPageView inquiries(
            Authentication authentication,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.listInquiries(RentalSubjects.customer(authentication).subjectId(), status, page, size);
    }

    @GetMapping("/api/v1/rental-inquiries/{inquiryId}")
    public ResponseEntity<RentalInquiryView> inquiry(Authentication authentication, @PathVariable UUID inquiryId) {
        return withVersion(ResponseEntity.ok(),
                service.readInquiry(RentalSubjects.customer(authentication).subjectId(), inquiryId));
    }

    /** 确认合同之前关闭询价。 */
    @PostMapping("/api/v1/rental-inquiries/{inquiryId}/close")
    public ResponseEntity<RentalInquiryView> close(Authentication authentication, @PathVariable UUID inquiryId) {
        return withVersion(ResponseEntity.ok(),
                service.closeInquiry(RentalSubjects.customer(authentication).subjectId(), inquiryId));
    }

    /** 确认合同：If-Match 是读询价单时的 ETag，quoteVersion 是页面上这版报价的版本。返回去收银台要的交易订单。 */
    @PostMapping("/api/v1/rental-inquiries/{inquiryId}/confirm")
    public RentalConfirmationView confirm(
            Authentication authentication,
            @PathVariable UUID inquiryId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody ConfirmRequest request
    ) {
        return service.confirm(RentalSubjects.customer(authentication).subjectId(), inquiryId,
                RentalSubjects.version(ifMatch), request.quoteVersion(), request.agreed());
    }

    @GetMapping("/api/v1/rental-orders")
    public RentalOrderPageView orders(
            Authentication authentication,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.listOrders(RentalSubjects.customer(authentication).subjectId(), status, page, size);
    }

    @GetMapping("/api/v1/rental-orders/{orderId}")
    public RentalOrderView order(Authentication authentication, @PathVariable UUID orderId) {
        return service.readOrder(RentalSubjects.customer(authentication).subjectId(), orderId);
    }

    @PostMapping("/api/v1/rental-orders/{orderId}/handover-confirmation")
    public RentalOrderView confirmHandover(Authentication authentication, @PathVariable UUID orderId) {
        return service.confirmHandover(RentalSubjects.customer(authentication).subjectId(), orderId);
    }

    @PostMapping("/api/v1/rental-orders/{orderId}/return-confirmation")
    public RentalOrderView confirmReturn(Authentication authentication, @PathVariable UUID orderId) {
        return service.confirmReturn(RentalSubjects.customer(authentication).subjectId(), orderId);
    }

    /** 询价身份：账号的默认组织是客户组织时记在组织名下（页面显示“企业 · 组织名”）；经办人是账号的显示名。 */
    private RentalLessee lessee(RentalSubjects.Subject subject) {
        IdentityProfile profile = profiles.findProfile(subject.subjectId())
                .orElseThrow(() -> new RentalException(RentalException.Error.FORBIDDEN));
        boolean organization = subject.organizationId() != null
                && CUSTOMER_ORGANIZATION.equals(profile.organizationType())
                && profile.organizationName() != null && !profile.organizationName().isBlank();
        return new RentalLessee(
                organization ? subject.organizationId() : null,
                organization ? profile.organizationName() : null,
                profile.displayName());
    }

    private static ResponseEntity<RentalInquiryView> withVersion(ResponseEntity.BodyBuilder builder,
                                                                 RentalInquiryView inquiry) {
        return builder.eTag(Integer.toString(inquiry.version())).body(inquiry);
    }

    /**
     * @param startDate 期望起租日 YYYY-MM-DD（北京时间），不早于明天
     */
    record InquiryRequest(
            UUID offerId,
            Integer quantity,
            String startDate,
            Integer days,
            String handoverMode,
            String returnMode,
            UUID addressId,
            String note
    ) {
    }

    /** agreed：勾选了“我已核对租期、费用与合同条款”。 */
    record ConfirmRequest(Integer quoteVersion, Boolean agreed) {
    }
}
