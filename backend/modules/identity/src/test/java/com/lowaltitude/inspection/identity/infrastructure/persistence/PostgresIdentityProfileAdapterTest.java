package com.lowaltitude.inspection.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.IdentityProfile;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class PostgresIdentityProfileAdapterTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void mapsTheStoredProfile() {
        PostgresIdentityProfileAdapter adapter = new PostgresIdentityProfileAdapter(subjectId ->
                Optional.of(new PersistedIdentityProfile(subjectId, "示例客户", "示例单位", "CUSTOMER")));

        assertThat(adapter.findProfile(USER_ID))
                .contains(new IdentityProfile(USER_ID, "示例客户", "示例单位", "CUSTOMER"));
    }

    @Test
    void anAccountWithoutAnActiveOrganizationStillHasAProfile() {
        PostgresIdentityProfileAdapter adapter = new PostgresIdentityProfileAdapter(subjectId ->
                Optional.of(new PersistedIdentityProfile(subjectId, "示例客户", null, " ")));

        assertThat(adapter.findProfile(USER_ID))
                .contains(new IdentityProfile(USER_ID, "示例客户", null, null));
    }

    @Test
    void unknownOrMissingSubjectHasNoProfile() {
        PostgresIdentityProfileAdapter adapter = new PostgresIdentityProfileAdapter(subjectId -> Optional.empty());

        assertThat(adapter.findProfile(USER_ID)).isEmpty();
        assertThat(adapter.findProfile(null)).isEmpty();
    }

    // The query is the privacy boundary: it must not read credentials or contact data, and it
    // must only answer for active accounts.
    @Test
    void profileQueryReadsNoCredentialOrContactColumns() throws Exception {
        Method query = IdentityPersistenceMapper.class.getMethod("findActiveProfileById", UUID.class);
        String sql = String.join("\n", query.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("u.status = 'ACTIVE'")
                .contains("o.status = 'ACTIVE'")
                .contains("WHERE u.id = #{subjectId}")
                .doesNotContain("password_hash")
                .doesNotContain("mobile")
                .doesNotContain("login_name")
                .doesNotContain("${");
    }
}
