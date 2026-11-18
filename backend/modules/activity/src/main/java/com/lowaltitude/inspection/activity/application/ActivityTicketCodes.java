package com.lowaltitude.inspection.activity.application;

import java.security.SecureRandom;
import java.util.function.Supplier;

/**
 * 凭证号：ACT- 加 12 位随机字符（去掉了容易看错的 I、L、O、U），约 60 位随机量，不含任何个人信息。
 * 字符都在二维码的字母数字集合内。撞号由 ticket_code 的唯一约束兜底。
 */
final class ActivityTicketCodes implements Supplier<String> {

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int GROUPS = 3;
    private static final int GROUP_LENGTH = 4;

    private final SecureRandom random = new SecureRandom();

    @Override
    public String get() {
        StringBuilder code = new StringBuilder("ACT");
        for (int group = 0; group < GROUPS; group++) {
            code.append('-');
            for (int index = 0; index < GROUP_LENGTH; index++) {
                code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
        }
        return code.toString();
    }
}
