package com.lowaltitude.inspection.account.application;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 收货地址簿（契约 contracts/openapi-account.yaml）。都要登录，且要客户角色。
 * 账号只从令牌里取；不是自己的地址一律当作不存在。
 */
@RestController
public final class AccountAddressController {

    private final AccountAddressService service;

    public AccountAddressController(AccountAddressService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/account/addresses")
    public AddressListView list(Authentication authentication) {
        return service.list(CustomerAccount.of(authentication));
    }

    @PostMapping("/api/v1/account/addresses")
    public ResponseEntity<AddressView> create(
            Authentication authentication,
            @RequestBody AddressForm form
    ) {
        return ResponseEntity.status(201).body(service.create(CustomerAccount.of(authentication), form));
    }

    @PutMapping("/api/v1/account/addresses/{addressId}")
    public AddressView update(
            Authentication authentication,
            @PathVariable UUID addressId,
            @RequestBody AddressForm form
    ) {
        return service.update(CustomerAccount.of(authentication), addressId, form);
    }

    @DeleteMapping("/api/v1/account/addresses/{addressId}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable UUID addressId) {
        service.delete(CustomerAccount.of(authentication), addressId);
        return ResponseEntity.noContent().build();
    }
}
