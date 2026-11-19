package com.lowaltitude.inspection.experience.application;

import java.security.SecureRandom;
import java.util.function.Supplier;

/**
 * 到馆核销码：VEN- 加 12 位随机字符（去掉了容易看错的 I、L、O、U），约 60 位随机量，不含任何个人信息。
 * 撞号由 venue_booking.code 的唯一约束兜底。
 */
final class VenueBookingCodes implements Supplier<String> {

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int GROUPS = 3;
    private static final int GROUP_LENGTH = 4;

    private final SecureRandom random = new SecureRandom();

    @Override
    public String get() {
        StringBuilder code = new StringBuilder("VEN");
        for (int group = 0; group < GROUPS; group++) {
            code.append('-');
            for (int index = 0; index < GROUP_LENGTH; index++) {
                code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
        }
        return code.toString();
    }
}
