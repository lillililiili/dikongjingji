package com.lowaltitude.inspection.bootstrap.security;

import com.lowaltitude.inspection.identity.application.account.AccountViews.SmsCodeSentView;
import com.lowaltitude.inspection.identity.application.account.SignedInAccount;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class PersonalPasswordController {
    private final PersonalPasswordService passwords;

    public PersonalPasswordController(PersonalPasswordService passwords) {
        this.passwords = passwords;
    }

    @GetMapping("/api/v1/account/password")
    public PersonalPasswordService.PasswordState state(Authentication authentication) {
        return passwords.state(SignedInAccount.of(authentication));
    }

    @PostMapping("/api/v1/account/password/setup-codes")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SmsCodeSentView sendSetupCode(Authentication authentication) {
        return passwords.sendSetupCode(SignedInAccount.of(authentication));
    }

    @PostMapping("/api/v1/account/password/setup")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setup(@RequestBody SetupRequest request, Authentication authentication) {
        passwords.setup(SignedInAccount.of(authentication), request.newPassword(), request.code());
    }

    @PostMapping("/api/v1/auth/phone-password-login")
    public LoginResponse login(@RequestBody PhonePasswordRequest request) {
        return AuthenticationController.response(passwords.login(request.phone(), request.password(),
                LoginDeviceRequest.toDevice(request.device())));
    }

    @PostMapping("/api/v1/account/password/change")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void change(@RequestBody ChangeRequest request, Authentication authentication) {
        passwords.change(SignedInAccount.of(authentication), request.currentPassword(), request.newPassword());
    }

    @PostMapping("/api/v1/auth/password-reset-codes")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SmsCodeSentView sendResetCode(@RequestBody ResetCodeRequest request) {
        return passwords.sendResetCode(request.phone());
    }

    @PostMapping("/api/v1/auth/password-reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@RequestBody ResetRequest request) {
        passwords.reset(request.phone(), request.code(), request.newPassword());
    }

    public record PhonePasswordRequest(String phone, String password, LoginDeviceRequest device) {}
    public record SetupRequest(String newPassword, String code) {}
    public record ChangeRequest(String currentPassword, String newPassword) {}
    public record ResetCodeRequest(String phone) {}
    public record ResetRequest(String phone, String code, String newPassword) {}
}
