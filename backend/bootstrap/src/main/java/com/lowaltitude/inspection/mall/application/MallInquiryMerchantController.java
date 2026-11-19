package com.lowaltitude.inspection.mall.application;

import java.time.OffsetDateTime;
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
 * 商家的询价单和报价（F4，受权接口，没有页面；开发脚本 infra/scripts/dev-inquiry-merchant.cjs）。
 * 要服务商操作员角色和令牌里的机构；只看发给本机构的询价单，看不到客户的身份和联系方式。
 */
@RestController
public final class MallInquiryMerchantController {

    private final MallInquiryService service;

    public MallInquiryMerchantController(MallInquiryService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/provider/mall/inquiries")
    public MallMerchantInquiryPageView inquiries(
            Authentication authentication,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.merchantInquiries(MallInquirySubjects.providerOperator(authentication).organizationId(),
                status, page, size);
    }

    @GetMapping("/api/v1/provider/mall/inquiries/{inquiryId}")
    public MallMerchantInquiryView inquiry(Authentication authentication, @PathVariable UUID inquiryId) {
        return service.merchantInquiry(MallInquirySubjects.providerOperator(authentication).organizationId(),
                inquiryId);
    }

    /** 报价或改价（截止之前，每次是新的一版）。 */
    @PutMapping("/api/v1/provider/mall/inquiries/{inquiryId}/quote")
    public MallMerchantInquiryView quote(
            Authentication authentication,
            @PathVariable UUID inquiryId,
            @RequestBody QuoteRequest request
    ) {
        MallInquirySubjects.Subject subject = MallInquirySubjects.providerOperator(authentication);
        return service.quote(subject.organizationId(), subject.subjectId(), inquiryId, new MallInquiryQuoteDraft(
                request.unitAmountMinor(), request.model(), request.leadDays(), request.includes(),
                request.validUntil()));
    }

    @PostMapping("/api/v1/provider/mall/inquiries/{inquiryId}/decline")
    public MallMerchantInquiryView decline(
            Authentication authentication,
            @PathVariable UUID inquiryId,
            @RequestBody DeclineRequest request
    ) {
        MallInquirySubjects.Subject subject = MallInquirySubjects.providerOperator(authentication);
        return service.decline(subject.organizationId(), subject.subjectId(), inquiryId, request.reason());
    }

    /**
     * @param validUntil 报价有效期：晚于现在，不超过 7 天
     */
    record QuoteRequest(Long unitAmountMinor, String model, Integer leadDays, String includes,
                        OffsetDateTime validUntil) {
    }

    record DeclineRequest(String reason) {
    }
}
