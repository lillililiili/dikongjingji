package com.lowaltitude.inspection.notification.application;

import java.util.UUID;

import com.lowaltitude.inspection.notification.application.SystemNotices.AgreementUpdateView;
import com.lowaltitude.inspection.notification.application.SystemNotices.SystemNoticeView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统通知的公开读取（契约 contracts/openapi-notification.yaml 1.5.0）：不登录也能读。
 * 公告是平台发给大家的，不含任何个人信息；协议页在登录前也要能显示“有更新”。
 */
@RestController
public final class SystemNoticeController {

    private final SystemNoticeService service;

    public SystemNoticeController(SystemNoticeService service) {
        this.service = service;
    }

    /** 最近一份说某协议更新的公告；没有时 latest 为 null。 */
    @GetMapping("/api/v1/system-notices/agreement-updates")
    public AgreementUpdateResult agreementUpdate(@RequestParam String agreement) {
        return new AgreementUpdateResult(service.latestAbout(agreement).orElse(null));
    }

    @GetMapping("/api/v1/system-notices/{noticeId}")
    public SystemNoticeView read(@PathVariable UUID noticeId) {
        return service.read(noticeId);
    }

    record AgreementUpdateResult(AgreementUpdateView latest) {
    }
}
