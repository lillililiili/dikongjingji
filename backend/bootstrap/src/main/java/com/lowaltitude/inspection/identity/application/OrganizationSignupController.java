package com.lowaltitude.inspection.identity.application;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家自助入驻的公开接口（契约 contracts/openapi-organization-signup.yaml 1.0.0）：发联系人手机号验证码、提交申请。
 * 不登录（SecurityConfiguration permitAll）；验证码的频率、每日上限和错误锁定由身份模块的短信验证码服务管。
 * 返回里没有口令，也不缓存。
 */
@RestController
public final class OrganizationSignupController {

    private static final String SIGNUPS = "/api/v1/organization-signups";

    private final OrganizationSignupService service;

    public OrganizationSignupController(OrganizationSignupService service) {
        this.service = service;
    }

    @PostMapping(SIGNUPS + "/sms-codes")
    public ResponseEntity<Void> sendCode(@RequestBody SmsCodeRequest request) {
        service.sendCode(request.phone());
        return ResponseEntity.accepted().build();
    }

    /** 申请：第一次 201，同一个幂等键重放 200。 */
    @PostMapping(SIGNUPS)
    public ResponseEntity<OrganizationSignupResult> signUp(
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody SignupRequest request
    ) {
        OrganizationSignupCreated created = service.signUp(idempotencyKey, new OrganizationSignupService.SignupDraft(
                request.organizationName(), request.cityCode(), request.subjectName(), request.licenseNumber(),
                request.businessScope(), request.contactName(), request.phone(), request.smsCode(),
                request.loginName(), request.password()));
        return ResponseEntity.status(created.created() ? 201 : 200)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(created.result());
    }

    record SmsCodeRequest(String phone) {
    }

    record SignupRequest(String organizationName, String cityCode, String subjectName, String licenseNumber,
                         String businessScope, String contactName, String phone, String smsCode, String loginName,
                         String password) {
    }
}
