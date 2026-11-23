package com.lowaltitude.inspection.identity.domain;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 登录时客户端说明的设备（契约 openapi-identity-account.yaml 的 Device），只用来在“登录设备”里认出它。
 * 都可以不传；不合格式的值当作没传，不因此拒绝登录。
 */
public record LoginDevice(String deviceKey, String deviceName, String platform, String cityCode) {

    public static final Set<String> PLATFORMS = Set.of("ANDROID", "IOS", "H5", "MP_WEIXIN", "OTHER");
    private static final Pattern DEVICE_KEY = Pattern.compile("^[A-Za-z0-9-]{8,64}$");
    private static final Pattern CITY_CODE = Pattern.compile("^[0-9]{6}$");
    private static final int MAX_DEVICE_NAME = 40;

    public static LoginDevice unknown() {
        return new LoginDevice(null, null, null, null);
    }

    /** 去掉不合格式的值；设备名去控制字符、截到 40 字，空了就按平台给默认名。 */
    public LoginDevice normalized() {
        String key = deviceKey != null && DEVICE_KEY.matcher(deviceKey).matches() ? deviceKey : null;
        String normalizedPlatform = platform != null && PLATFORMS.contains(platform) ? platform : "OTHER";
        String city = cityCode != null && CITY_CODE.matcher(cityCode).matches() ? cityCode : null;
        String name = deviceName == null ? "" : deviceName.replaceAll("\\p{Cntrl}", "").strip();
        if (name.length() > MAX_DEVICE_NAME) {
            name = name.substring(0, MAX_DEVICE_NAME);
        }
        if (name.isEmpty()) {
            name = switch (normalizedPlatform) {
                case "ANDROID" -> "Android 手机";
                case "IOS" -> "iPhone";
                case "H5" -> "网页浏览器";
                case "MP_WEIXIN" -> "微信小程序";
                default -> "未知设备";
            };
        }
        return new LoginDevice(key, name, normalizedPlatform, city);
    }
}
