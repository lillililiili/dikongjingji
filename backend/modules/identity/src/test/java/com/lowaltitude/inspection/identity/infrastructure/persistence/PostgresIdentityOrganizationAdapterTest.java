package com.lowaltitude.inspection.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.IdentityOrganization;
import com.lowaltitude.inspection.identity.infrastructure.FailClosedIdentityOrganizationPort;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

/** G1：按 ID 读在用机构的名称和类型。 */
class PostgresIdentityOrganizationAdapterTest {

    private static final UUID ORGANIZATION = UUID.fromString("c1000000-0000-4000-8000-000000000001");

    @Test
    void mapsTheStoredOrganization() {
        PostgresIdentityOrganizationAdapter adapter = new PostgresIdentityOrganizationAdapter(id ->
                Optional.of(new PersistedIdentityOrganization(id, "示例机器人商家", "PROVIDER")));

        assertThat(adapter.findActiveOrganization(ORGANIZATION))
                .contains(new IdentityOrganization(ORGANIZATION, "示例机器人商家", "PROVIDER"));
    }

    @Test
    void anUnknownOrMissingOrganizationIsEmptyAndTheFallbackFindsNothing() {
        PostgresIdentityOrganizationAdapter adapter = new PostgresIdentityOrganizationAdapter(id -> Optional.empty());

        assertThat(adapter.findActiveOrganization(ORGANIZATION)).isEmpty();
        assertThat(adapter.findActiveOrganization(null)).isEmpty();
        assertThat(new FailClosedIdentityOrganizationPort().findActiveOrganization(ORGANIZATION)).isEmpty();
    }

    // 只回答在用的机构，只读名称和类型。
    @Test
    void theQueryReadsOnlyActiveOrganizationsAndNoOtherColumns() throws Exception {
        Method query = IdentityPersistenceMapper.class.getMethod("findActiveOrganizationById", UUID.class);
        String sql = String.join("\n", query.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("o.id AS organization_id")
                .contains("o.organization_name")
                .contains("o.organization_type")
                .contains("WHERE o.id = #{organizationId}")
                .contains("o.status = 'ACTIVE'")
                .doesNotContain("organization_code")
                .doesNotContain("identity_user")
                .doesNotContain("${");
    }
}
