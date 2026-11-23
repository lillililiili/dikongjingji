package com.lowaltitude.inspection.application.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lowaltitude.inspection.infrastructure.persistence.InspectionProviderProfileMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/** 服务商资料的开通（S6-01）：没有就建、停用的恢复、在用的不动；SQL 只碰 inspection_provider_profile、全用绑定参数。 */
class InspectionProviderProfilesTest {

    private static final UUID PROVIDER = UUID.fromString("f2000000-0000-4000-8000-0000000000a1");

    private final FakeMapper mapper = new FakeMapper();
    private final InspectionProviderProfiles profiles = new InspectionProviderProfiles(mapper);

    @Test
    void opensOnceAndReportsTheStatusBefore() {
        assertThat(profiles.status(PROVIDER)).isEqualTo(InspectionProviderProfiles.Status.NOT_OPENED);

        assertThat(profiles.open(PROVIDER)).isEqualTo(InspectionProviderProfiles.Status.NOT_OPENED);
        assertThat(profiles.status(PROVIDER)).isEqualTo(InspectionProviderProfiles.Status.ACTIVE);

        assertThat(profiles.open(PROVIDER)).isEqualTo(InspectionProviderProfiles.Status.ACTIVE);
        assertThat(mapper.calls).containsExactly("lock", "insert", "lock");
    }

    @Test
    void aDisabledProfileComesBack() {
        mapper.rows.put(PROVIDER, "DISABLED");

        assertThat(profiles.open(PROVIDER)).isEqualTo(InspectionProviderProfiles.Status.DISABLED);
        assertThat(mapper.rows).containsEntry(PROVIDER, "ACTIVE");
        assertThat(mapper.calls).containsExactly("lock", "reactivate");
    }

    @Test
    void aRowInsertedByAnotherRequestIsTreatedAsAlreadyOpen() {
        mapper.raceOnInsert = true;

        assertThat(profiles.open(PROVIDER)).isEqualTo(InspectionProviderProfiles.Status.ACTIVE);
        assertThat(mapper.calls).containsExactly("lock", "insert", "lock");
    }

    @Test
    void rejectsAMissingProviderAndUnknownStatuses() {
        assertThatThrownBy(() -> profiles.open(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profiles.status(null)).isInstanceOf(IllegalArgumentException.class);
        mapper.rows.put(PROVIDER, "PAUSED");
        assertThatThrownBy(() -> profiles.status(PROVIDER)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sqlOnlyTouchesTheProfileTableWithBoundParameters() {
        for (Method method : InspectionProviderProfileMapper.class.getDeclaredMethods()) {
            String sql = sql(method);
            assertThat(sql).as(method.getName())
                    .doesNotContain("${")
                    .contains("inspection_provider_profile")
                    .contains("#{providerId}")
                    .doesNotContain("identity_");
        }
        assertThat(sql("lockStatus")).contains("FOR UPDATE");
        assertThat(sql("insertActive"))
                .contains("'PROVIDER_INITIAL', 'ACTIVE'")
                .contains("ON CONFLICT (provider_id) DO NOTHING");
        assertThat(sql("reactivate"))
                .contains("SET status = 'ACTIVE'")
                .contains("version = version + 1")
                .contains("AND status = 'DISABLED'");
    }

    private static String sql(String name) {
        return sql(Arrays.stream(InspectionProviderProfileMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name)).findFirst().orElseThrow());
    }

    private static String sql(Method method) {
        if (method.isAnnotationPresent(Select.class)) {
            return String.join(" ", method.getAnnotation(Select.class).value());
        }
        if (method.isAnnotationPresent(Insert.class)) {
            return String.join(" ", method.getAnnotation(Insert.class).value());
        }
        return String.join(" ", method.getAnnotation(Update.class).value());
    }

    private static final class FakeMapper implements InspectionProviderProfileMapper {

        private final Map<UUID, String> rows = new HashMap<>();
        private final List<String> calls = new ArrayList<>();
        private boolean raceOnInsert;

        @Override
        public String findStatus(UUID providerId) {
            return rows.get(providerId);
        }

        @Override
        public String lockStatus(UUID providerId) {
            calls.add("lock");
            return rows.get(providerId);
        }

        @Override
        public int insertActive(UUID providerId) {
            calls.add("insert");
            rows.put(providerId, "ACTIVE");
            return raceOnInsert ? 0 : 1;
        }

        @Override
        public int reactivate(UUID providerId) {
            calls.add("reactivate");
            rows.put(providerId, "ACTIVE");
            return 1;
        }
    }
}
