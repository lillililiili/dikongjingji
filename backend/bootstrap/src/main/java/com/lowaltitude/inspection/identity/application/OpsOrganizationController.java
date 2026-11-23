package com.lowaltitude.inspection.identity.application;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.ops.OpsActor;
import com.lowaltitude.inspection.ops.OpsActors;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 运营端“机构入驻与资质审核”（S3-1，契约 contracts/openapi-ops-organization.yaml 1.1.0：S6-01 加了开通服务商资料）。只给平台运营；
 * 每个改动都由服务写一条操作记录（操作人取自令牌和身份资料）。附件只经这里读，不给存储地址。
 */
@RestController
public final class OpsOrganizationController {

    private static final String ORGANIZATIONS = "/api/v1/ops/organizations";
    private static final String ORGANIZATION = ORGANIZATIONS + "/{organizationId}";

    private final OrganizationOnboardingService service;
    private final OpsActors actors;

    public OpsOrganizationController(OrganizationOnboardingService service, OpsActors actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping(ORGANIZATIONS)
    public OpsOrganizationPageView organizations(
            Authentication authentication,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String qualificationStatus,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        actors.operator(authentication);
        return service.organizations(keyword, type, qualificationStatus, page, size);
    }

    /** 新建：第一次 201，同一个幂等键、同样内容重放 200。 */
    @PostMapping(ORGANIZATIONS)
    public ResponseEntity<OpsOrganizationDetailView> create(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody CreateRequest request
    ) {
        OrganizationOnboardingService.Operator operator = operator(authentication);
        OpsOrganizationCreated created = service.create(idempotencyKey, request.type(), request.name(),
                request.cityCode(), operator);
        return ResponseEntity.status(created.created() ? 201 : 200).body(created.detail());
    }

    @GetMapping(ORGANIZATION)
    public OpsOrganizationDetailView organization(Authentication authentication, @PathVariable UUID organizationId) {
        actors.operator(authentication);
        return service.organization(organizationId);
    }

    @PutMapping(ORGANIZATION + "/qualification")
    public OpsOrganizationDetailView submitQualification(
            Authentication authentication,
            @PathVariable UUID organizationId,
            @RequestBody QualificationRequest request
    ) {
        return service.submitQualification(organizationId, new OrganizationOnboardingService.QualificationDraft(
                request.subjectName(), request.licenseNumber(), request.businessScope(), request.qualificationNote(),
                request.version()), operator(authentication));
    }

    @PostMapping(ORGANIZATION + "/qualification/review")
    public OpsOrganizationDetailView review(
            Authentication authentication,
            @PathVariable UUID organizationId,
            @RequestBody ReviewRequest request
    ) {
        return service.review(organizationId, request.decision(), request.reason(), request.version(),
                operator(authentication));
    }

    @PostMapping(path = ORGANIZATION + "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<OpsOrganizationDetailView> addAttachment(
            Authentication authentication,
            @PathVariable UUID organizationId,
            @RequestPart("file") MultipartFile file
    ) throws IOException {
        OrganizationOnboardingService.Operator operator = operator(authentication);
        if (file.isEmpty() || file.getSize() > OrganizationOnboardingService.MAX_FILE_BYTES) {
            throw new IllegalArgumentException("file size is invalid");
        }
        return ResponseEntity.status(201).body(service.addAttachment(organizationId, file.getOriginalFilename(),
                file.getBytes(), operator));
    }

    @DeleteMapping(ORGANIZATION + "/attachments/{attachmentId}")
    public OpsOrganizationDetailView removeAttachment(
            Authentication authentication,
            @PathVariable UUID organizationId,
            @PathVariable UUID attachmentId
    ) {
        return service.removeAttachment(organizationId, attachmentId, operator(authentication));
    }

    /** 附件内容：只给登录的平台运营，不缓存，按附件下载（不在页面里直接渲染）。 */
    @GetMapping(ORGANIZATION + "/attachments/{attachmentId}/content")
    public ResponseEntity<byte[]> attachmentContent(
            Authentication authentication,
            @PathVariable UUID organizationId,
            @PathVariable UUID attachmentId
    ) {
        actors.operator(authentication);
        OpsAttachmentContent content = service.attachmentContent(organizationId, attachmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''"
                        + URLEncoder.encode(content.fileName(), StandardCharsets.UTF_8).replace("+", "%20"))
                .body(content.content());
    }

    /** 开通服务商资料（1.1.0，S6-01）：幂等，已开通直接返回机构详情。 */
    @PostMapping(ORGANIZATION + "/provider-profile")
    public OpsOrganizationDetailView openProviderProfile(Authentication authentication,
                                                         @PathVariable UUID organizationId) {
        return service.openProviderProfile(organizationId, operator(authentication));
    }

    @PostMapping(ORGANIZATION + "/accounts")
    public ResponseEntity<OpsAccountCreatedView> createAccount(
            Authentication authentication,
            @PathVariable UUID organizationId,
            @RequestBody AccountRequest request
    ) {
        OrganizationOnboardingService.Operator operator = operator(authentication);
        return ResponseEntity.status(201)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(service.createAccount(organizationId, request.loginName(), request.displayName(),
                        request.roles(), operator));
    }

    @PutMapping(ORGANIZATION + "/accounts/{accountId}/roles")
    public OpsOrganizationDetailView replaceRoles(
            Authentication authentication,
            @PathVariable UUID organizationId,
            @PathVariable UUID accountId,
            @RequestBody RolesRequest request
    ) {
        return service.replaceRoles(organizationId, accountId, request.roles(), operator(authentication));
    }

    private OrganizationOnboardingService.Operator operator(Authentication authentication) {
        OpsActor actor = actors.operator(authentication);
        return new OrganizationOnboardingService.Operator(actor.accountId(), actor.displayName());
    }

    /**
     * @param type     PROVIDER / CUSTOMER
     * @param name     机构名称（1–60 字）
     * @param cityCode 所在城市（平台城市目录的编码）
     */
    record CreateRequest(String type, String name, String cityCode) {
    }

    /** version：第一次录入不带，修改时带详情里读到的版本号。 */
    record QualificationRequest(String subjectName, String licenseNumber, String businessScope,
                                String qualificationNote, Integer version) {
    }

    /** decision：APPROVE / REJECT；驳回时 reason 必填。 */
    record ReviewRequest(String decision, String reason, Integer version) {
    }

    record AccountRequest(String loginName, String displayName, List<String> roles) {
    }

    record RolesRequest(List<String> roles) {
    }
}
