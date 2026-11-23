package com.lowaltitude.inspection.demand.application;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务商的需求与报价（F1，受权接口，没有页面；开发脚本 infra/scripts/dev-provider-quote.cjs）。
 * 要服务商操作员角色和令牌里的机构；服务商只看到需求内容，看不到发布人的账号、组织和联系方式。
 */
@RestController
public final class DemandProviderController {

    private final DemandService service;

    public DemandProviderController(DemandService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/provider/demands")
    public ProviderDemandPageView demands(
            Authentication authentication,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.listForProvider(DemandSubjects.providerOperator(authentication).organizationId(), status,
                page, size);
    }

    @GetMapping("/api/v1/provider/demands/{demandId}")
    public ProviderDemandView demand(Authentication authentication, @PathVariable UUID demandId) {
        return service.readForProvider(DemandSubjects.providerOperator(authentication).organizationId(), demandId);
    }

    /** 报价或改价（截止前）。内容和现在的有效报价一样时没有变化。 */
    @PutMapping("/api/v1/provider/demands/{demandId}/quote")
    public ProviderDemandView quote(
            Authentication authentication,
            @PathVariable UUID demandId,
            @RequestBody QuoteRequest request
    ) {
        DemandSubjects.Subject subject = DemandSubjects.providerOperator(authentication);
        return service.submitQuote(subject.organizationId(), subject.subjectId(), demandId, new QuoteDraft(
                request.amountMinor(), request.method(), request.schedule(), request.durationDays(),
                request.deliverables(), request.validUntil()));
    }

    /** 撤回报价（选定之前）。 */
    @DeleteMapping("/api/v1/provider/demands/{demandId}/quote")
    public ProviderDemandView withdraw(Authentication authentication, @PathVariable UUID demandId) {
        DemandSubjects.Subject subject = DemandSubjects.providerOperator(authentication);
        return service.withdrawQuote(subject.organizationId(), subject.subjectId(), demandId);
    }

    /**
     * @param validUntil 报价有效期：晚于现在，不超过 30 天
     */
    record QuoteRequest(
            Long amountMinor,
            String method,
            String schedule,
            Integer durationDays,
            String deliverables,
            OffsetDateTime validUntil
    ) {
    }
}
