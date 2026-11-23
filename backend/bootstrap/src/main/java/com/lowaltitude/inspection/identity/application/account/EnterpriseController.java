package com.lowaltitude.inspection.identity.application.account;

import java.util.UUID;

import com.lowaltitude.inspection.bootstrap.security.AuthenticationService;
import com.lowaltitude.inspection.identity.application.account.AccountViews.CreatedInvitationView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.CurrentIdentityView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.EnterpriseListView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.EnterpriseMemberView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.EnterpriseMembersView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.EnterpriseView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.PendingInvitationView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.ReceivedInvitationView;
import com.lowaltitude.inspection.identity.domain.CurrentIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 企业组织与成员、切换身份（S5-7，契约 openapi-identity-account.yaml 1.0.0）。只给客户角色；账号只来自令牌。
 */
@RestController
public class EnterpriseController {

    private final EnterpriseService enterprises;
    private final AuthenticationService tokens;

    public EnterpriseController(EnterpriseService enterprises, AuthenticationService tokens) {
        this.enterprises = enterprises;
        this.tokens = tokens;
    }

    @GetMapping("/api/v1/account/enterprises")
    public EnterpriseListView listMine(Authentication authentication) {
        return enterprises.listMine(SignedInAccount.customer(authentication).subjectId());
    }

    @PostMapping("/api/v1/account/enterprises")
    @ResponseStatus(HttpStatus.CREATED)
    public EnterpriseView create(@RequestHeader("Idempotency-Key") UUID idempotencyKey,
                                 @RequestBody EnterpriseForm form, Authentication authentication) {
        return enterprises.create(SignedInAccount.customer(authentication).subjectId(), idempotencyKey, form.name(),
                form.creditCode(), form.cityCode());
    }

    @GetMapping("/api/v1/account/enterprises/{enterpriseId}")
    public EnterpriseView read(@PathVariable UUID enterpriseId, Authentication authentication) {
        return enterprises.read(SignedInAccount.customer(authentication).subjectId(), enterpriseId);
    }

    @GetMapping("/api/v1/account/enterprises/{enterpriseId}/members")
    public EnterpriseMembersView members(@PathVariable UUID enterpriseId, Authentication authentication) {
        return enterprises.members(SignedInAccount.customer(authentication).subjectId(), enterpriseId);
    }

    @PutMapping("/api/v1/account/enterprises/{enterpriseId}/members/{memberId}")
    public EnterpriseMemberView changeRole(@PathVariable UUID enterpriseId, @PathVariable UUID memberId,
                                           @RequestBody MemberRoleForm form, Authentication authentication) {
        return enterprises.changeRole(SignedInAccount.customer(authentication).subjectId(), enterpriseId, memberId,
                form.role());
    }

    @DeleteMapping("/api/v1/account/enterprises/{enterpriseId}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID enterpriseId, @PathVariable UUID memberId, Authentication authentication) {
        enterprises.remove(SignedInAccount.customer(authentication).subjectId(), enterpriseId, memberId);
    }

    @DeleteMapping("/api/v1/account/enterprises/{enterpriseId}/membership")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@PathVariable UUID enterpriseId, Authentication authentication) {
        enterprises.leave(SignedInAccount.customer(authentication).subjectId(), enterpriseId);
    }

    @PostMapping("/api/v1/account/enterprises/{enterpriseId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedInvitationView invite(@PathVariable UUID enterpriseId, @RequestBody InvitationForm form,
                                        Authentication authentication) {
        return enterprises.invite(SignedInAccount.customer(authentication).subjectId(), enterpriseId, form.kind(),
                form.phone());
    }

    @DeleteMapping("/api/v1/account/enterprises/{enterpriseId}/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeInvitation(@PathVariable UUID enterpriseId, @PathVariable UUID invitationId,
                                 Authentication authentication) {
        enterprises.revokeInvitation(SignedInAccount.customer(authentication).subjectId(), enterpriseId, invitationId);
    }

    @PostMapping("/api/v1/account/enterprises/{enterpriseId}/invitations/{invitationId}/renewals")
    public PendingInvitationView renewInvitation(@PathVariable UUID enterpriseId, @PathVariable UUID invitationId,
                                                 Authentication authentication) {
        return enterprises.renewInvitation(SignedInAccount.customer(authentication).subjectId(), enterpriseId,
                invitationId);
    }

    @GetMapping("/api/v1/account/enterprise-invitations/{invitationId}")
    public ReceivedInvitationView readInvitation(@PathVariable UUID invitationId,
                                                 @RequestParam(value = "code", required = false) String code,
                                                 Authentication authentication) {
        return enterprises.readInvitation(SignedInAccount.customer(authentication).subjectId(), invitationId, code);
    }

    @PostMapping("/api/v1/account/enterprise-invitations/{invitationId}/acceptance")
    public EnterpriseView accept(@PathVariable UUID invitationId,
                                 @RequestBody(required = false) InvitationResponseForm form,
                                 Authentication authentication) {
        return enterprises.accept(SignedInAccount.customer(authentication).subjectId(), invitationId,
                form == null ? null : form.code());
    }

    @PostMapping("/api/v1/account/enterprise-invitations/{invitationId}/decline")
    public ReceivedInvitationView decline(@PathVariable UUID invitationId,
                                          @RequestBody(required = false) InvitationResponseForm form,
                                          Authentication authentication) {
        return enterprises.decline(SignedInAccount.customer(authentication).subjectId(), invitationId,
                form == null ? null : form.code());
    }

    /** 切换身份后用同一个会话重新签发令牌；会话已退出时按未登录回答。 */
    @PutMapping("/api/v1/account/identity")
    public IdentitySwitchResponse switchIdentity(@RequestBody IdentitySwitchRequest request,
                                                 Authentication authentication) {
        SignedInAccount account = SignedInAccount.customer(authentication);
        CurrentIdentity identity = enterprises.switchIdentity(account.subjectId(), request.enterpriseId());
        AuthenticationService.LoginToken token = tokens.reissue(account.subjectId(), account.sessionId())
                .orElseThrow(() -> new AccountException(AccountException.Error.UNAUTHORIZED));
        return new IdentitySwitchResponse(token.accessToken(), "Bearer", token.expiresInSeconds(),
                new CurrentIdentityView(identity.type(), identity.enterpriseId(), identity.enterpriseName(),
                        identity.role()));
    }

    public record EnterpriseForm(String name, String creditCode, String cityCode) {
    }

    public record MemberRoleForm(String role) {
    }

    public record InvitationForm(String kind, String phone) {
    }

    public record InvitationResponseForm(String code) {
    }

    public record IdentitySwitchRequest(UUID enterpriseId) {
    }

    public record IdentitySwitchResponse(String accessToken, String tokenType, long expiresInSeconds,
                                         CurrentIdentityView identity) {
    }
}
