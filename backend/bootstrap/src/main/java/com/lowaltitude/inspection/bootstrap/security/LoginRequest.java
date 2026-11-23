package com.lowaltitude.inspection.bootstrap.security;

/** Request DTO for the anonymous login endpoint. device 是 2.4.0 加的可选登录设备说明。 */
public record LoginRequest(String loginName, String password, LoginDeviceRequest device) {

    public LoginRequest(String loginName, String password) {
        this(loginName, password, null);
    }
}
