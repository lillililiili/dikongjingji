package com.lowaltitude.inspection.identity.application.account;

import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.identity.application.account.AccountViews.ClosureView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.DataExportOverviewView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.DataExportView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.DownloadIntentView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.ExportFile;
import com.lowaltitude.inspection.identity.application.account.AccountViews.PhoneBindingView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.PhoneChangeTicketView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.RevokedCountView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.SessionListView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.SmsCodeSentView;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 已登录账号自己的手机号、登录设备、注销和个人信息导出（S5-8，契约 openapi-identity-account.yaml 1.0.0）。
 * 控制器只取令牌里的账号和会话、转参数；规则都在身份模块的服务里。
 */
@RestController
public class AccountController {

    private final PhoneAccountService phones;
    private final LoginSessionService sessions;
    private final AccountClosureService closures;
    private final PersonalDataExportService exports;

    public AccountController(PhoneAccountService phones, LoginSessionService sessions,
                             AccountClosureService closures, PersonalDataExportService exports) {
        this.phones = phones;
        this.sessions = sessions;
        this.closures = closures;
        this.exports = exports;
    }

    @PostMapping("/api/v1/account/sms-codes")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SmsCodeSentView sendCode(@RequestBody AccountCodeRequest request, Authentication authentication) {
        SignedInAccount account = SignedInAccount.of(authentication);
        return phones.sendAccountCode(account.subjectId(), request.purpose(), request.phone());
    }

    @PostMapping("/api/v1/account/phone/verifications")
    public PhoneChangeTicketView verifyCurrentPhone(@RequestBody CodeOnly request, Authentication authentication) {
        return phones.verifyCurrent(SignedInAccount.of(authentication).subjectId(), request.code());
    }

    @PutMapping("/api/v1/account/phone")
    public PhoneBindingView changePhone(@RequestBody PhoneChangeRequest request, Authentication authentication) {
        SignedInAccount account = SignedInAccount.of(authentication);
        return phones.changePhone(account.subjectId(), account.sessionId(), request.newPhone(), request.code(),
                request.changeTicket());
    }

    @GetMapping("/api/v1/account/sessions")
    public SessionListView listSessions(Authentication authentication) {
        SignedInAccount account = SignedInAccount.of(authentication);
        return sessions.list(account.subjectId(), account.sessionId());
    }

    @DeleteMapping("/api/v1/account/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeSession(@PathVariable UUID sessionId, Authentication authentication) {
        SignedInAccount account = SignedInAccount.of(authentication);
        sessions.revoke(account.subjectId(), account.sessionId(), sessionId);
    }

    @PostMapping("/api/v1/account/sessions/revocations")
    public RevokedCountView revokeOtherSessions(Authentication authentication) {
        SignedInAccount account = SignedInAccount.of(authentication);
        return sessions.revokeOthers(account.subjectId(), account.sessionId());
    }

    @GetMapping("/api/v1/account/closure")
    public ClosureView readClosure(Authentication authentication) {
        return closures.read(SignedInAccount.of(authentication).subjectId());
    }

    @PostMapping("/api/v1/account/closure")
    @ResponseStatus(HttpStatus.CREATED)
    public ClosureView requestClosure(Authentication authentication) {
        return closures.request(SignedInAccount.of(authentication).subjectId());
    }

    @DeleteMapping("/api/v1/account/closure")
    public ClosureView withdrawClosure(Authentication authentication) {
        return closures.withdraw(SignedInAccount.of(authentication).subjectId());
    }

    @GetMapping("/api/v1/account/data-exports")
    public DataExportOverviewView listExports(Authentication authentication) {
        return exports.overview(SignedInAccount.of(authentication).subjectId());
    }

    @PostMapping("/api/v1/account/data-exports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DataExportView requestExport(@RequestBody DataExportRequest request, Authentication authentication) {
        return exports.request(SignedInAccount.of(authentication).subjectId(), request.sections(), request.code());
    }

    @PostMapping("/api/v1/account/data-exports/{exportId}/download-intents")
    @ResponseStatus(HttpStatus.CREATED)
    public DownloadIntentView createDownload(@PathVariable UUID exportId, Authentication authentication) {
        return exports.createDownload(SignedInAccount.of(authentication).subjectId(), exportId);
    }

    /** 不要登录头：凭据就是授权，用过即失效；不缓存。 */
    @GetMapping("/api/v1/account/data-exports/{exportId}/file")
    public ResponseEntity<byte[]> download(@PathVariable UUID exportId, @RequestParam("token") String token) {
        ExportFile file = exports.download(exportId, token);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .body(file.content());
    }

    public record AccountCodeRequest(String purpose, String phone) {
    }

    public record CodeOnly(String code) {
    }

    public record PhoneChangeRequest(String newPhone, String code, String changeTicket) {
    }

    public record DataExportRequest(List<String> sections, String code) {
    }
}
