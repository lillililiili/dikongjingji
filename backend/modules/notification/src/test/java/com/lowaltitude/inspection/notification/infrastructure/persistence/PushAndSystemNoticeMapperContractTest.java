package com.lowaltitude.inspection.notification.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/** S5-9 的两个 mapper：只用绑定参数；账号自己的偏好和设备都以账号为条件。 */
class PushAndSystemNoticeMapperContractTest {

    @Test
    void everyStatementUsesBoundParametersOnly() {
        for (Class<?> mapper : List.of(PushDeviceMapper.class, SystemNoticeMapper.class)) {
            assertThat(Arrays.stream(mapper.getDeclaredMethods()).map(PushAndSystemNoticeMapperContractTest::sql))
                    .allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
        }
    }

    @Test
    void preferencesAndDevicesAreScopedToTheAccount() {
        for (String name : List.of("findPreference", "activeDevicesOf", "revokeDevice", "findFakeDeliveries")) {
            assertThat(sql(method(PushDeviceMapper.class, name))).containsPattern("account_id = #\\{accountId}");
        }
        assertThat(sql(method(PushDeviceMapper.class, "savePreference"))).contains("ON CONFLICT (account_id) DO UPDATE");
        assertThat(sql(method(PushDeviceMapper.class, "registerDevice")))
                .contains("ON CONFLICT (platform, push_client_id) DO UPDATE")
                .contains("SET account_id = EXCLUDED.account_id")
                .contains("RETURNING id");
        assertThat(sql(method(PushDeviceMapper.class, "activeDevicesPage")))
                .contains("ORDER BY id")
                .contains("LIMIT #{limit}");
    }

    @Test
    void publishingIsIdempotentAndWithdrawingHidesDeliveredCopies() {
        assertThat(sql(method(SystemNoticeMapper.class, "insert"))).contains("ON CONFLICT (publish_key) DO NOTHING");
        assertThat(sql(method(SystemNoticeMapper.class, "withdraw"))).contains("AND status = 'PUBLISHED'");
        assertThat(sql(method(SystemNoticeMapper.class, "hideDelivered")).replaceAll("\\s+", " "))
                .contains("SET status = 'DELETED'")
                .contains("WHERE category = 'SYSTEM_NOTICE' AND target_type = 'SYSTEM_NOTICE' AND target_id = #{id}");
        assertThat(sql(method(SystemNoticeMapper.class, "latestAbout")))
                .contains("audience_scope = 'ALL'")
                .contains("status = 'PUBLISHED'");
    }

    private static Method method(Class<?> mapper, String name) {
        return Arrays.stream(mapper.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static String sql(Method method) {
        for (Class<? extends Annotation> type : List.of(Select.class, Insert.class, Update.class)) {
            Annotation annotation = method.getAnnotation(type);
            if (annotation instanceof Select select) {
                return String.join("\n", select.value());
            }
            if (annotation instanceof Insert insert) {
                return String.join("\n", insert.value());
            }
            if (annotation instanceof Update update) {
                return String.join("\n", update.value());
            }
        }
        throw new IllegalStateException(method.getName() + " has no SQL annotation");
    }
}
