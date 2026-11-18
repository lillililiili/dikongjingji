package com.lowaltitude.inspection.activity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ActivityTicketCodesTest {

    private final ActivityTicketCodes codes = new ActivityTicketCodes();

    // 核销接口按这个格式校验，凭证表的 ticket_code 是 varchar(24)。
    @Test
    void everyCodeHasTheFormatTheCheckInAccepts() {
        for (int index = 0; index < 2_000; index++) {
            String code = codes.get();

            assertThat(code).matches("^ACT-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}$");
            assertThat(code).hasSize(18);
        }
    }

    // 随机部分去掉了容易看错的 I、L、O、U（前缀 ACT 是固定的，不算在内）。
    @Test
    void theRandomPartNeverUsesLettersThatAreEasyToMisread() {
        for (int index = 0; index < 2_000; index++) {
            assertThat(codes.get().substring("ACT".length())).doesNotContain("I", "L", "O", "U");
        }
    }

    @Test
    void twoThousandCodesAreAllDifferent() {
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < 2_000; index++) {
            seen.add(codes.get());
        }

        assertThat(seen).hasSize(2_000);
    }

    @Test
    void theRandomPartUsesTheWholeAlphabet() {
        Set<Integer> used = new HashSet<>();
        for (int index = 0; index < 2_000; index++) {
            codes.get().substring("ACT-".length()).chars().filter(character -> character != '-').forEach(used::add);
        }

        // 十个数字加二十二个字母。
        assertThat(used).hasSize(32);
    }
}
