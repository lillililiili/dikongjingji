package com.lowaltitude.inspection.identity.application.account;

import java.util.regex.Pattern;

/** 中国大陆手机号：校验和打码。 */
public final class PhoneNumbers {

    private static final Pattern PHONE = Pattern.compile("^1[3-9][0-9]{9}$");

    private PhoneNumbers() {
    }

    public static boolean isValid(String phone) {
        return phone != null && PHONE.matcher(phone).matches();
    }

    /** 不合格式时按参数错误拒绝。 */
    public static String require(String phone) {
        if (!isValid(phone)) {
            throw new AccountException(AccountException.Error.INVALID);
        }
        return phone;
    }

    /** 前 3 位和后 4 位，中间 4 个星号；空值返回 null。 */
    public static String mask(String phone) {
        if (phone == null || phone.length() != 11) {
            return null;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
