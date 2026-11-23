package com.lowaltitude.inspection.bootstrap.security;

import com.lowaltitude.inspection.identity.domain.LoginDevice;

/** 登录请求里的设备说明（契约 openapi-identity-account.yaml 的 Device）；都可以不传，不合格式的值当作没传。 */
public record LoginDeviceRequest(String deviceKey, String deviceName, String platform, String cityCode) {

    static LoginDevice toDevice(LoginDeviceRequest request) {
        if (request == null) {
            return LoginDevice.unknown();
        }
        return new LoginDevice(request.deviceKey(), request.deviceName(), request.platform(), request.cityCode());
    }
}
