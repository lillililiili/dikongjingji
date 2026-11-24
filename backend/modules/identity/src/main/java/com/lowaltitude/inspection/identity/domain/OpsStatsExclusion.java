package com.lowaltitude.inspection.identity.domain;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 运营端“经营概览”不计入的账号和机构（演示数据、测试账号）。由身份模块按配置解析，各模块的统计按这两组 ID 排除。
 *
 * <p>两组 ID 以 PostgreSQL 数组字面量交给 SQL：{@code x <> ALL (CAST(#{excluded.userArray} AS uuid[]))}。
 * 数组为空时这个条件对任何值（包括 NULL）都成立，结果和不排除一样；数组不为空时 NULL 会被判成不成立，
 * 所以可空的列要写成 {@code (x IS NULL OR x <> ALL (...))}。
 */
public final class OpsStatsExclusion {

    public static final OpsStatsExclusion NONE = new OpsStatsExclusion(Set.of(), Set.of());

    private final Set<UUID> userIds;
    private final Set<UUID> organizationIds;
    private final String userArray;
    private final String organizationArray;

    public OpsStatsExclusion(Collection<UUID> userIds, Collection<UUID> organizationIds) {
        this.userIds = Set.copyOf(userIds);
        this.organizationIds = Set.copyOf(organizationIds);
        this.userArray = literal(this.userIds);
        this.organizationArray = literal(this.organizationIds);
    }

    public Set<UUID> getUserIds() {
        return userIds;
    }

    public Set<UUID> getOrganizationIds() {
        return organizationIds;
    }

    /** 如 {@code {uuid,uuid}}；没有要排除的是 {@code {}}。 */
    public String getUserArray() {
        return userArray;
    }

    public String getOrganizationArray() {
        return organizationArray;
    }

    private static String literal(Set<UUID> ids) {
        return ids.stream().map(UUID::toString).sorted().collect(Collectors.joining(",", "{", "}"));
    }
}
