package com.lowaltitude.inspection.mall.application;

import java.util.UUID;

import com.lowaltitude.inspection.ops.OpsActor;
import com.lowaltitude.inspection.ops.OpsActors;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营端“商家资料审核”和商品的交易条款（S5-2、S4-4，契约 contracts/openapi-ops-merchant-profile.yaml）。只给平台运营。
 * 审核记录在提交单上（谁、什么时候、通过还是驳回）；交易条款的改动记在“商品与供给”的操作记录里。
 */
@RestController
public final class OpsMerchantProfileController {

    private static final String SUBMISSIONS = "/api/v1/ops/mall/merchant-profiles";
    private static final String TERMS = "/api/v1/ops/catalog/products/{productId}/trade-terms";

    private final MallMerchantService service;
    private final OpsActors actors;

    public OpsMerchantProfileController(MallMerchantService service, OpsActors actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping(SUBMISSIONS)
    public MallMerchantSubmissionPageView list(
            Authentication authentication,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        actors.operator(authentication);
        return service.listSubmissions(status, keyword, page, size);
    }

    @GetMapping(SUBMISSIONS + "/{submissionId}")
    public MallMerchantSubmissionDetailView read(Authentication authentication, @PathVariable UUID submissionId) {
        actors.operator(authentication);
        return service.readSubmission(submissionId);
    }

    @PostMapping(SUBMISSIONS + "/{submissionId}/approval")
    public MallMerchantSubmissionDetailView approve(Authentication authentication, @PathVariable UUID submissionId,
                                                    @RequestBody ReviewRequest request) {
        OpsActor actor = actors.operator(authentication);
        return service.approve(submissionId, version(request.version()), actor.accountId(), actor.displayName());
    }

    @PostMapping(SUBMISSIONS + "/{submissionId}/rejection")
    public MallMerchantSubmissionDetailView reject(Authentication authentication, @PathVariable UUID submissionId,
                                                   @RequestBody ReviewRequest request) {
        OpsActor actor = actors.operator(authentication);
        return service.reject(submissionId, version(request.version()), actor.accountId(), actor.displayName(),
                request.reason());
    }

    @GetMapping(TERMS)
    public MallTradeTermsDetailView tradeTerms(Authentication authentication, @PathVariable UUID productId) {
        actors.operator(authentication);
        return service.tradeTerms(productId);
    }

    @PutMapping(TERMS)
    public MallTradeTermsDetailView setProductTerms(Authentication authentication, @PathVariable UUID productId,
                                                    @RequestBody ProductTermsRequest request) {
        OpsActor actor = actors.operator(authentication);
        if (request.noReasonReturn() == null) {
            throw new IllegalArgumentException("noReasonReturn is required");
        }
        return service.setNoReasonReturn(productId, request.noReasonReturn(), actor.accountId(), actor.displayName());
    }

    @PutMapping("/api/v1/ops/catalog/products/{productId}/offers/{offerId}/trade-terms")
    public MallTradeTermsDetailView setOfferTerms(Authentication authentication, @PathVariable UUID productId,
                                                  @PathVariable UUID offerId, @RequestBody OfferTermsRequest request) {
        OpsActor actor = actors.operator(authentication);
        return service.setOfferTerms(productId, offerId, request.invoiceIssuer(), request.aftersaleParty(),
                actor.accountId(), actor.displayName());
    }

    private static int version(Integer version) {
        if (version == null || version < 0) {
            throw new IllegalArgumentException("version is required");
        }
        return version;
    }

    record ReviewRequest(Integer version, String reason) {
    }

    record ProductTermsRequest(Boolean noReasonReturn) {
    }

    record OfferTermsRequest(String invoiceIssuer, String aftersaleParty) {
    }
}
