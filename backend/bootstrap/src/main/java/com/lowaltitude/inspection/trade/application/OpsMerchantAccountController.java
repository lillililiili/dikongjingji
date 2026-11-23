package com.lowaltitude.inspection.trade.application;

import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.ops.OpsActor;
import com.lowaltitude.inspection.ops.OpsActors;
import com.lowaltitude.inspection.trade.application.OpsMerchantAccountService.OpsMerchantAccountOperationView;
import com.lowaltitude.inspection.trade.application.OpsMerchantAccountService.OpsMerchantAccountPageView;
import com.lowaltitude.inspection.trade.application.OpsMerchantAccountService.OpsMerchantAccountView;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营端“收款账户”（S3-1，契约 contracts/openapi-ops-catalog.yaml 1.0.0）：经营机构的微信二级商户号登记。只给平台运营；
 * 每次登记、修改都记一条记录。只登记，不做进件。
 */
@RestController
public final class OpsMerchantAccountController {

    private static final String ACCOUNTS = "/api/v1/ops/merchant-accounts";

    private final OpsMerchantAccountService service;
    private final OpsActors actors;

    public OpsMerchantAccountController(OpsMerchantAccountService service, OpsActors actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping(ACCOUNTS)
    public OpsMerchantAccountPageView accounts(
            Authentication authentication,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean registered,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        actors.operator(authentication);
        return service.accounts(keyword, registered, page, size);
    }

    @PutMapping(ACCOUNTS + "/{organizationId}")
    public OpsMerchantAccountView save(
            Authentication authentication,
            @PathVariable UUID organizationId,
            @RequestBody SaveRequest request
    ) {
        OpsActor actor = actors.operator(authentication);
        return service.save(organizationId, request.subMchid(), request.active(), request.version(),
                new OpsMerchantAccountService.Operator(actor.accountId(), actor.displayName()));
    }

    @GetMapping(ACCOUNTS + "/{organizationId}/operations")
    public List<OpsMerchantAccountOperationView> operations(Authentication authentication,
                                                            @PathVariable UUID organizationId) {
        actors.operator(authentication);
        return service.operations(organizationId);
    }

    /** version：第一次登记不带，修改时带列表里读到的版本号。 */
    public record SaveRequest(String subMchid, Boolean active, Integer version) {
    }
}
