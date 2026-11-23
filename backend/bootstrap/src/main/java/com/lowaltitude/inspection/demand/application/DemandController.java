package com.lowaltitude.inspection.demand.application;

import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.IdentityProfile;
import com.lowaltitude.inspection.identity.domain.IdentityProfilePort;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户的需求与报价比选（F1，契约 contracts/openapi-demand.yaml 1.0.0）。都要登录和客户角色（可邀请的服务商只要登录）。
 * 账号只从令牌里取；不是自己的需求一律当作不存在。返回需求的接口都带 ETag（需求版本），选定时用 If-Match 带回来。
 */
@RestController
public final class DemandController {

    private static final String CUSTOMER_ORGANIZATION = "CUSTOMER";

    private final DemandService service;
    private final IdentityProfilePort profiles;

    public DemandController(DemandService service, IdentityProfilePort profiles) {
        this.service = service;
        this.profiles = profiles;
    }

    /** 新建需求（草稿）；submit 为 true 时同一步提交。第一次 201，同一个幂等键重放 200。 */
    @PostMapping("/api/v1/demands")
    public ResponseEntity<DemandView> create(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody CreateDemandRequest request
    ) {
        DemandSubjects.Subject subject = DemandSubjects.customer(authentication);
        DemandCreated result = service.create(
                subject.subjectId(),
                publisher(subject),
                idempotencyKey,
                new DemandDraft(request.type(), request.cityCode(), request.address(), request.scope(),
                        request.dueDate(), request.budgetMinMinor(), request.budgetMaxMinor(), request.deliverables(),
                        request.note(), request.quoteScope(), request.invitedProviderIds()),
                Boolean.TRUE.equals(request.submit()));
        return withVersion(ResponseEntity.status(result.created() ? 201 : 200), result.demand());
    }

    /** 改草稿（整份替换）。 */
    @PutMapping("/api/v1/demands/{demandId}")
    public ResponseEntity<DemandView> update(
            Authentication authentication,
            @PathVariable UUID demandId,
            @RequestBody UpdateDemandRequest request
    ) {
        DemandView demand = service.updateDraft(
                DemandSubjects.customer(authentication).subjectId(),
                demandId,
                new DemandDraft(request.type(), request.cityCode(), request.address(), request.scope(),
                        request.dueDate(), request.budgetMinMinor(), request.budgetMaxMinor(), request.deliverables(),
                        request.note(), request.quoteScope(), request.invitedProviderIds()));
        return withVersion(ResponseEntity.ok(), demand);
    }

    @PostMapping("/api/v1/demands/{demandId}/submit")
    public ResponseEntity<DemandView> submit(Authentication authentication, @PathVariable UUID demandId) {
        return withVersion(ResponseEntity.ok(),
                service.submit(DemandSubjects.customer(authentication).subjectId(), demandId));
    }

    @GetMapping("/api/v1/demands")
    public DemandPageView demands(
            Authentication authentication,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.list(DemandSubjects.customer(authentication).subjectId(), status, page, size);
    }

    /** 1.3.0：“需求与报价”页签上的计数。 */
    @GetMapping("/api/v1/demand-counts")
    public DemandCountsView counts(Authentication authentication) {
        return service.counts(DemandSubjects.customer(authentication).subjectId());
    }

    @GetMapping("/api/v1/demands/{demandId}")
    public ResponseEntity<DemandView> demand(Authentication authentication, @PathVariable UUID demandId) {
        return withVersion(ResponseEntity.ok(),
                service.read(DemandSubjects.customer(authentication).subjectId(), demandId));
    }

    @PostMapping("/api/v1/demands/{demandId}/cancel")
    public ResponseEntity<DemandView> cancel(Authentication authentication, @PathVariable UUID demandId) {
        return withVersion(ResponseEntity.ok(),
                service.cancel(DemandSubjects.customer(authentication).subjectId(), demandId));
    }

    /** 删除草稿。 */
    @DeleteMapping("/api/v1/demands/{demandId}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable UUID demandId) {
        service.deleteDraft(DemandSubjects.customer(authentication).subjectId(), demandId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/demands/{demandId}/quotes")
    public DemandQuoteListView quotes(
            Authentication authentication,
            @PathVariable UUID demandId,
            @RequestParam(required = false) String sort
    ) {
        return service.quotes(DemandSubjects.customer(authentication).subjectId(), demandId, sort);
    }

    /** 选定一份报价。If-Match 是读需求时的 ETag（需求版本）。 */
    @PostMapping("/api/v1/demands/{demandId}/choice")
    public ResponseEntity<DemandView> choose(
            Authentication authentication,
            @PathVariable UUID demandId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody ChoiceRequest request
    ) {
        DemandView demand = service.choose(DemandSubjects.customer(authentication).subjectId(), demandId,
                DemandSubjects.version(ifMatch), request.quoteId(), request.quoteVersion());
        return withVersion(ResponseEntity.ok(), demand);
    }

    /** 某个城市里可以邀请的服务商。只要登录。 */
    @GetMapping("/api/v1/demand-providers")
    public DemandProviderListView providers(
            Authentication authentication,
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) String type
    ) {
        DemandSubjects.of(authentication);
        return service.invitableProviders(cityCode, type);
    }

    /** 账号的默认组织是客户组织时，需求记在组织名下（页面显示“企业 · 组织名”）；否则是个人。 */
    private DemandPublisher publisher(DemandSubjects.Subject subject) {
        if (subject.organizationId() == null) {
            return DemandPublisher.personal();
        }
        return profiles.findProfile(subject.subjectId())
                .filter(profile -> CUSTOMER_ORGANIZATION.equals(profile.organizationType()))
                .map(IdentityProfile::organizationName)
                .filter(name -> name != null && !name.isBlank())
                .map(name -> new DemandPublisher(subject.organizationId(), name))
                .orElse(DemandPublisher.personal());
    }

    private static ResponseEntity<DemandView> withVersion(ResponseEntity.BodyBuilder builder, DemandView demand) {
        return builder.eTag(Integer.toString(demand.version())).body(demand);
    }

    /**
     * @param dueDate 期望完成日期 YYYY-MM-DD（北京时间）；不填表示时间可协商
     * @param submit  true 时同一步提交（校验必填项）；不填或 false 只存草稿
     */
    record CreateDemandRequest(
            String type,
            String cityCode,
            String address,
            String scope,
            String dueDate,
            Long budgetMinMinor,
            Long budgetMaxMinor,
            List<String> deliverables,
            String note,
            String quoteScope,
            List<UUID> invitedProviderIds,
            Boolean submit
    ) {
    }

    record UpdateDemandRequest(
            String type,
            String cityCode,
            String address,
            String scope,
            String dueDate,
            Long budgetMinMinor,
            Long budgetMaxMinor,
            List<String> deliverables,
            String note,
            String quoteScope,
            List<UUID> invitedProviderIds
    ) {
    }

    record ChoiceRequest(UUID quoteId, Integer quoteVersion) {
    }
}
