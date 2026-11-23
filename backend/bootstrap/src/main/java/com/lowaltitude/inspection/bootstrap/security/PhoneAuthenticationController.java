package com.lowaltitude.inspection.bootstrap.security;

import com.lowaltitude.inspection.identity.application.account.AccountException;
import com.lowaltitude.inspection.identity.application.account.AccountViews.SmsCodeSentView;
import com.lowaltitude.inspection.identity.application.account.PhoneAccountService;
import com.lowaltitude.inspection.identity.application.account.SignedInAccount;
import com.lowaltitude.inspection.identity.domain.AccountStatePort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 手机号验证码登录（S5-8，契约 openapi-identity-account.yaml 1.0.0）：发码、登录（没注册的号码同时注册），
 * 以及本机退出登录。令牌和账号密码登录同一套签发（{@link AuthenticationService}）。
 */
@RestController
public class PhoneAuthenticationController {

    private final PhoneAccountService phones;
    private final AuthenticationService tokens;
    private final AccountStatePort accountState;

    public PhoneAuthenticationController(PhoneAccountService phones, AuthenticationService tokens,
                                         AccountStatePort accountState) {
        this.phones = phones;
        this.tokens = tokens;
        this.accountState = accountState;
    }

    @PostMapping("/api/v1/auth/sms-codes")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SmsCodeSentView sendLoginCode(@RequestBody LoginCodeRequest request) {
        return phones.sendLoginCode(request.phone());
    }

    @PostMapping("/api/v1/auth/phone-login")
    public PhoneLoginResponse login(@RequestBody PhoneLoginRequest request) {
        PhoneAccountService.PhoneLogin login = phones.login(request.phone(), request.code());
        AuthenticationService.LoginToken token = tokens.issueForVerifiedAccount(login.userId(),
                        LoginDeviceRequest.toDevice(request.device()))
                .orElseThrow(() -> new AccountException(AccountException.Error.UNAUTHORIZED));
        return new PhoneLoginResponse(token.accessToken(), "Bearer", token.expiresInSeconds(), login.registered());
    }

    @PostMapping("/api/v1/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(Authentication authentication) {
        SignedInAccount account = SignedInAccount.of(authentication);
        if (account.sessionId() != null) {
            accountState.signOut(account.sessionId(), account.subjectId());
        }
    }

    public record LoginCodeRequest(String phone) {
    }

    public record PhoneLoginRequest(String phone, String code, LoginDeviceRequest device) {
    }

    public record PhoneLoginResponse(String accessToken, String tokenType, long expiresInSeconds, boolean registered) {
    }
}
