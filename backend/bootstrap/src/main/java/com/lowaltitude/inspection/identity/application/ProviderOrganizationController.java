package com.lowaltitude.inspection.identity.application;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.IdentityProfile;
import com.lowaltitude.inspection.identity.domain.IdentityProfilePort;
import com.lowaltitude.inspection.trade.application.TradeAftersaleSubjects;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 机构工作台“入驻资料”（契约 contracts/openapi-organization-signup.yaml 1.0.0 的工作台接口）：机构看自己的资料和资质
 * 审核结果，改资质资料、传或移除附件。要 PROVIDER_OPERATOR，机构只从令牌里取；审核只在运营端做。
 */
@RestController
public final class ProviderOrganizationController {

    private static final String ORGANIZATION = "/api/v1/provider/organization";

    private final OrganizationSignupService service;
    private final IdentityProfilePort profiles;

    public ProviderOrganizationController(OrganizationSignupService service, IdentityProfilePort profiles) {
        this.service = service;
        this.profiles = profiles;
    }

    @GetMapping(ORGANIZATION)
    public OwnOrganizationView read(Authentication authentication) {
        return service.own(TradeAftersaleSubjects.providerOperator(authentication).organizationId());
    }

    @PutMapping(ORGANIZATION + "/qualification")
    public OwnOrganizationView submitQualification(Authentication authentication,
                                                   @RequestBody QualificationRequest request) {
        Member member = member(authentication);
        return service.submitQualification(member.organizationId(), new OrganizationOnboardingService.QualificationDraft(
                request.subjectName(), request.licenseNumber(), request.businessScope(), request.qualificationNote(),
                request.version()), member.operator());
    }

    @PostMapping(path = ORGANIZATION + "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<OwnOrganizationView> addAttachment(Authentication authentication,
                                                             @RequestPart("file") MultipartFile file) throws IOException {
        Member member = member(authentication);
        if (file.isEmpty() || file.getSize() > OrganizationOnboardingService.MAX_FILE_BYTES) {
            throw new IllegalArgumentException("file size is invalid");
        }
        return ResponseEntity.status(201).body(service.addAttachment(member.organizationId(),
                file.getOriginalFilename(), file.getBytes(), member.operator()));
    }

    @DeleteMapping(ORGANIZATION + "/attachments/{attachmentId}")
    public OwnOrganizationView removeAttachment(Authentication authentication, @PathVariable UUID attachmentId) {
        Member member = member(authentication);
        return service.removeAttachment(member.organizationId(), attachmentId, member.operator());
    }

    /** 自己上传的附件：不缓存，按附件下载。 */
    @GetMapping(ORGANIZATION + "/attachments/{attachmentId}/content")
    public ResponseEntity<byte[]> attachmentContent(Authentication authentication, @PathVariable UUID attachmentId) {
        UUID organizationId = TradeAftersaleSubjects.providerOperator(authentication).organizationId();
        OpsAttachmentContent content = service.attachmentContent(organizationId, attachmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''"
                        + URLEncoder.encode(content.fileName(), StandardCharsets.UTF_8).replace("+", "%20"))
                .body(content.content());
    }

    private Member member(Authentication authentication) {
        TradeAftersaleSubjects.Subject subject = TradeAftersaleSubjects.providerOperator(authentication);
        String name = profiles.findProfile(subject.subjectId())
                .map(IdentityProfile::displayName)
                .filter(displayName -> !displayName.isBlank())
                .orElseThrow(() -> new AccessDeniedException("operator account is not active"));
        return new Member(subject.organizationId(),
                new OrganizationOnboardingService.Operator(subject.subjectId(), name));
    }

    private record Member(UUID organizationId, OrganizationOnboardingService.Operator operator) {
    }

    /** version：第一次录入不带，修改时带读到的版本号。 */
    record QualificationRequest(String subjectName, String licenseNumber, String businessScope,
                                String qualificationNote, Integer version) {
    }
}
