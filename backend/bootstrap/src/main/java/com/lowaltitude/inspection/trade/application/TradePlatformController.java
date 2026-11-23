package com.lowaltitude.inspection.trade.application;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import com.lowaltitude.inspection.trade.application.TradeViews.ChannelCheckView;
import com.lowaltitude.inspection.trade.application.TradeViews.MerchantAccountView;
import com.lowaltitude.inspection.trade.application.TradeViews.ReconciliationRunView;
import com.lowaltitude.inspection.trade.application.TradeViews.ReconciliationView;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台运营的交易接口（契约 contracts/openapi-trade.yaml）：商家收款账户登记、对账（E06）、支付通道自检（S2-1）。
 * 要 PLATFORM_OPERATOR 角色。
 */
@RestController
public final class TradePlatformController {

    private static final String PLATFORM_OPERATOR = "PLATFORM_OPERATOR";

    private final TradeOrderService service;
    private final TradeReconciliationService reconciliation;
    private final TradeChannelCheckService channelCheck;

    public TradePlatformController(
            TradeOrderService service,
            TradeReconciliationService reconciliation,
            TradeChannelCheckService channelCheck
    ) {
        this.service = service;
        this.reconciliation = reconciliation;
        this.channelCheck = channelCheck;
    }

    /** 某一天的对账结果；format=CSV 时下载差异表（E06）。 */
    @GetMapping("/api/v1/platform/trade/reconciliation")
    public ResponseEntity<?> reconciliation(
            Authentication authentication,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "JSON") String format
    ) {
        requirePlatformOperator(authentication);
        if (!"JSON".equals(format) && !"CSV".equals(format)) {
            throw new IllegalArgumentException("format must be JSON or CSV");
        }
        ReconciliationView view = reconciliation.read(date);
        if ("JSON".equals(format)) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(view);
        }
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("reconciliation-" + date + ".csv").build().toString())
                .body(TradeReconciliationService.csv(view));
    }

    /** 立刻对某一天跑一次对账（账单导入失败后重试，或差异处理后重新核对）。redo=true 时已导入的也重新下载。 */
    @PostMapping("/api/v1/platform/trade/reconciliation/runs")
    public ReconciliationRunView runReconciliation(
            Authentication authentication,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "false") boolean redo
    ) {
        requirePlatformOperator(authentication);
        return reconciliation.reconcile(date, redo);
    }

    /**
     * 支付通道自检（S2-1）：配置逐项检查，再按不存在的单号向渠道查一次单、查一次退款。只读，不改任何数据；
     * 结果有 FAIL 也回 200（自检本身成功了），由 passed 和各项的 status 说明。
     */
    @PostMapping("/api/v1/platform/trade/channel-checks")
    public ChannelCheckView checkChannel(
            Authentication authentication,
            @RequestParam(required = false) String subMchid
    ) {
        requirePlatformOperator(authentication);
        return channelCheck.run(subMchid);
    }

    @PutMapping("/api/v1/platform/trade/merchant-accounts/{organizationId}")
    public MerchantAccountView registerMerchantAccount(
            Authentication authentication,
            @PathVariable UUID organizationId,
            @RequestBody MerchantAccountRequest request
    ) {
        requirePlatformOperator(authentication);
        return service.saveMerchantAccount(organizationId, request.subMchid(), request.active());
    }

    private static void requirePlatformOperator(Authentication authentication) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        Set<String> roles;
        if (principal instanceof CurrentSubject current) {
            roles = current.roles();
        } else if (principal instanceof Jwt jwt) {
            List<String> claim = jwt.getClaimAsStringList("roles");
            roles = claim == null ? Set.of() : Set.copyOf(claim);
        } else {
            roles = Set.of();
        }
        if (!roles.contains(PLATFORM_OPERATOR)) {
            throw new AccessDeniedException("platform operator role is required");
        }
    }

    record MerchantAccountRequest(String subMchid, Boolean active) {
    }
}
