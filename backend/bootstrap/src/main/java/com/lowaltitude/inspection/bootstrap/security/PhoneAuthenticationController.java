package com.lowaltitude.inspection.bootstrap.security;

import com.lowaltitude.inspection.identity.application.account.AccountException;
import com.lowaltitude.inspection.identity.application.account.AccountViews.SmsCodeSentView;
import com.lowaltitude.inspection.identity.application.account.PhoneAccountService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 手机号验证码登录（S5-8，契约 openapi-identity-account.yaml 1.0.0）：发码、登录（没注册的号码同时注册）。
 * 令牌和账号密码登录同一套签发（{@link AuthenticationService}）：访问令牌加刷新令牌，续期和退出登录用
 * /api/v1/auth/refresh、/api/v1/auth/logout（AuthenticationController）。
 */
@RestController
public class PhoneAuthenticationController {

    private final PhoneAccountService phones;
    private final AuthenticationService tokens;

    public PhoneAuthenticationController(PhoneAccountService phones, AuthenticationService tokens) {
        this.phones = phones;
        this.tokens = tokens;
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
        return new PhoneLoginResponse(token.accessToken(), "Bearer", token.expiresInSeconds(), token.refreshToken(),
                token.refreshExpiresInSeconds(), login.registered());
    }

    public record LoginCodeRequest(String phone) {
    }

    public record PhoneLoginRequest(String phone, String code, LoginDeviceRequest device) {
    }

    public record PhoneLoginResponse(String accessToken, String tokenType, long expiresInSeconds, String refreshToken,
                                     long refreshExpiresInSeconds, boolean registered) {
    }
}
