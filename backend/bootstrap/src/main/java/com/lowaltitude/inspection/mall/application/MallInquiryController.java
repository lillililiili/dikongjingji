package com.lowaltitude.inspection.mall.application;

import java.util.List;
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
 * 客户的询价单和报价比选（F4，契约 contracts/openapi-mall-inquiry.yaml 1.0.0）。都要登录和客户角色；账号只从令牌里取，
 * 不是自己的询价单一律当作不存在。返回询价单的接口带 ETag（询价单版本），选定报价时用 If-Match 带回来。
 * 按报价下单走交易模块的提交订单（inquiryQuoteId）。
 */
@RestController
public final class MallInquiryController {

    private static final String CUSTOMER_ORGANIZATION = "CUSTOMER";

    private final MallInquiryService service;
    private final IdentityProfilePort profiles;

    public MallInquiryController(MallInquiryService service, IdentityProfilePort profiles) {
        this.service = service;
        this.profiles = profiles;
    }

    /** 询价商品在这个城市可以发询价的商家（填写询价单时选）。 */
    @GetMapping("/api/v1/mall/inquiry-offers")
    public MallInquiryOfferListView offers(
            Authentication authentication,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) String cityCode
    ) {
        MallInquirySubjects.customer(authentication);
        return service.offers(productId, cityCode);
    }

    /** 提交询价单。第一次 201，同一个幂等键重放 200。 */
    @PostMapping("/api/v1/mall/inquiries")
    public ResponseEntity<MallInquiryView> submit(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody InquiryRequest request
    ) {
        MallInquirySubjects.Subject subject = MallInquirySubjects.customer(authentication);
        MallInquiryCreated created = service.submit(subject.subjectId(), buyer(subject), idempotencyKey,
                new MallInquiryDraft(request.productId(), request.requirement(), request.quantity(), request.cityCode(),
                        request.expectedDate(), request.offerIds()));
        return withVersion(ResponseEntity.status(created.created() ? 201 : 200), created.inquiry());
    }

    @GetMapping("/api/v1/mall/inquiries")
    public MallInquiryPageView inquiries(
            Authentication authentication,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.list(MallInquirySubjects.customer(authentication).subjectId(), status, page, size);
    }

    /** 询价单和各家报价（sort：ALL 综合、PRICE 总价、LEAD_TIME 交期）。 */
    @GetMapping("/api/v1/mall/inquiries/{inquiryId}")
    public ResponseEntity<MallInquiryDetailView> inquiry(
            Authentication authentication,
            @PathVariable UUID inquiryId,
            @RequestParam(required = false) String sort
    ) {
        MallInquiryDetailView detail = service.detail(MallInquirySubjects.customer(authentication).subjectId(),
                inquiryId, sort);
        return ResponseEntity.ok().eTag(Integer.toString(detail.inquiry().version())).body(detail);
    }

    /** 选定一份报价：If-Match 是读询价单时的 ETag，quoteVersion 是比选页上这份报价的版本。 */
    @PostMapping("/api/v1/mall/inquiries/{inquiryId}/choice")
    public ResponseEntity<MallInquiryView> choose(
            Authentication authentication,
            @PathVariable UUID inquiryId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody ChoiceRequest request
    ) {
        return withVersion(ResponseEntity.ok(), service.choose(
                MallInquirySubjects.customer(authentication).subjectId(), inquiryId,
                MallInquirySubjects.version(ifMatch), request.quoteId(), request.quoteVersion()));
    }

    @PostMapping("/api/v1/mall/inquiries/{inquiryId}/close")
    public ResponseEntity<MallInquiryView> close(Authentication authentication, @PathVariable UUID inquiryId) {
        return withVersion(ResponseEntity.ok(),
                service.close(MallInquirySubjects.customer(authentication).subjectId(), inquiryId));
    }

    /** 询价身份（只给客户自己看）：账号的默认组织是客户组织时记在组织名下；经办人是账号的显示名。 */
    private MallInquiryBuyer buyer(MallInquirySubjects.Subject subject) {
        IdentityProfile profile = profiles.findProfile(subject.subjectId())
                .orElseThrow(() -> new MallInquiryException(MallInquiryException.Error.FORBIDDEN));
        boolean organization = subject.organizationId() != null
                && CUSTOMER_ORGANIZATION.equals(profile.organizationType())
                && profile.organizationName() != null && !profile.organizationName().isBlank();
        return new MallInquiryBuyer(organization ? subject.organizationId() : null,
                organization ? profile.organizationName() : null, profile.displayName());
    }

    private static ResponseEntity<MallInquiryView> withVersion(ResponseEntity.BodyBuilder builder,
                                                               MallInquiryView inquiry) {
        return builder.eTag(Integer.toString(inquiry.version())).body(inquiry);
    }

    /**
     * @param expectedDate 期望交付日期 YYYY-MM-DD（北京时间），可以不填，填了要晚于今天
     * @param offerIds     发给哪些商家：这件商品在这个城市的询价供给，1–3 家
     */
    record InquiryRequest(
            UUID productId,
            String requirement,
            Integer quantity,
            String cityCode,
            String expectedDate,
            List<UUID> offerIds
    ) {
    }

    record ChoiceRequest(UUID quoteId, Integer quoteVersion) {
    }
}
