package com.lowaltitude.inspection.mall.application;

import java.time.LocalDate;

import com.lowaltitude.inspection.identity.domain.IdentityProfile;
import com.lowaltitude.inspection.identity.domain.IdentityProfilePort;
import com.lowaltitude.inspection.mall.application.MallMerchantService.ProfileDraft;
import com.lowaltitude.inspection.trade.application.TradeAftersaleSubjects;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家主页（公开）和商家工作台的“商家资料”（S5-2，契约 contracts/openapi-mall-merchant.yaml）。
 * 工作台要 PROVIDER_OPERATOR，机构只从令牌里取；提交后要平台运营审核才对外。
 */
@RestController
public final class MallMerchantController {

    private final MallMerchantService service;
    private final IdentityProfilePort profiles;

    public MallMerchantController(MallMerchantService service, IdentityProfilePort profiles) {
        this.service = service;
        this.profiles = profiles;
    }

    @GetMapping("/api/v1/mall/merchants/{merchantId}")
    public MallMerchantPageView page(@PathVariable java.util.UUID merchantId,
                                     @RequestParam(required = false) String cityCode) {
        return service.page(merchantId, cityCode);
    }

    @GetMapping("/api/v1/provider/mall/merchant-profile")
    public MallMerchantProfileStateView readOwn(Authentication authentication) {
        return service.readOwn(TradeAftersaleSubjects.providerOperator(authentication).organizationId());
    }

    @PutMapping("/api/v1/provider/mall/merchant-profile")
    public MallMerchantProfileStateView submit(Authentication authentication,
                                               @RequestBody MerchantProfileRequest request) {
        TradeAftersaleSubjects.Subject operator = TradeAftersaleSubjects.providerOperator(authentication);
        String name = profiles.findProfile(operator.subjectId())
                .map(IdentityProfile::displayName)
                .filter(displayName -> !displayName.isBlank())
                .orElseThrow(() -> new AccessDeniedException("operator account is not active"));
        return service.submit(operator.organizationId(), operator.subjectId(), name, new ProfileDraft(
                request.businessEntity(), request.intro(), request.saleScopeNote(), request.authorizationUntil(),
                request.categoryNote(), request.invoiceIssuer(), request.aftersaleParty()));
    }

    record MerchantProfileRequest(
            String businessEntity,
            String intro,
            String saleScopeNote,
            LocalDate authorizationUntil,
            String categoryNote,
            String invoiceIssuer,
            String aftersaleParty
    ) {
    }
}
