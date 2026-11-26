package com.lowaltitude.inspection.identity.application.account;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.identity.infrastructure.persistence.OpsUserLookupMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OpsUserLookupMapper.UserExtraRow;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OpsUserLookupMapper.UserRow;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营端“用户查询”（契约 contracts/openapi-ops-lookup.yaml 1.0.0）：按手机号、账号 ID、登录名精确查，按显示名包含查。只读；
 * 手机号打码。打开详情记一条查看记录（{@link #recordView}）。角色由控制器检查。
 */
public class OpsUserLookupService {

    public static final int MAX_PAGE_SIZE = 50;
    public static final int MAX_QUERY = 40;
    public static final int MAX_DISPLAY_NAME_QUERY = 20;

    private final OpsUserLookupMapper mapper;
    private final Clock clock;

    public OpsUserLookupService(OpsUserLookupMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 账号状态（契约 OpsUserStatus）。CLOSING 是正常账号已申请注销、还在冷静期。 */
    public enum OpsUserStatus {
        ACTIVE, CLOSING, LOCKED, DISABLED, CLOSED
    }

    /** 查看记录的对象。 */
    public enum ViewTarget {
        USER("OPS_VIEW_USER", "USER"),
        TRADE_ORDER("OPS_VIEW_ORDER", "TRADE_ORDER");

        private final String actionCode;
        private final String targetType;

        ViewTarget(String actionCode, String targetType) {
            this.actionCode = actionCode;
            this.targetType = targetType;
        }
    }

    @Transactional(readOnly = true)
    public OpsUserPageView search(String query, int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
        Query parsed = Query.parse(query);
        long total = mapper.countUsers(parsed.phone(), parsed.userId(), parsed.loginName(), parsed.displayName());
        List<OpsUserSummaryView> items = total == 0 ? List.of() : mapper.findUsers(parsed.phone(), parsed.userId(),
                        parsed.loginName(), parsed.displayName(), size, Math.multiplyExact(page - 1, size)).stream()
                .map(OpsUserLookupService::summary)
                .toList();
        return new OpsUserPageView(page, size, total, (long) page * size < total, items);
    }

    /** orderCount 来自交易模块，由调用方传进来。 */
    @Transactional(readOnly = true)
    public Optional<OpsUserDetailView> detail(UUID userId, java.util.function.ToLongFunction<UUID> orderCount) {
        UserRow row = mapper.findUser(userId);
        if (row == null) {
            return Optional.empty();
        }
        OpsUserSummaryView summary = summary(row);
        // 和 App“登录设备”页同一口径：没退出、近 30 天用过的。
        UserExtraRow extra = mapper.findUserExtra(userId,
                OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).minus(LoginSessionService.LISTED_WINDOW));
        return Optional.of(new OpsUserDetailView(summary.id(), summary.displayName(), summary.loginName(),
                summary.maskedPhone(), summary.status(), summary.registeredVia(), summary.roles(), summary.createdAt(),
                extra.closingDueAt(), extra.closedAt(),
                mapper.findOrganizations(userId).stream()
                        .map(org -> new OpsUserOrganizationView(org.id(), org.organizationName(),
                                org.organizationType()))
                        .toList(),
                mapper.findEnterprises(userId).stream()
                        .map(member -> new OpsUserEnterpriseView(member.id(), member.organizationName(),
                                member.memberRole(), member.status()))
                        .toList(),
                Math.toIntExact(extra.activeDeviceCount()), extra.lastActiveAt(),
                orderCount.applyAsLong(userId)));
    }

    /**
     * 打开详情的查看记录。单独提交：只读查询失败不影响留痕，留痕失败就不给看（调用方先记再读）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordView(UUID actorId, ViewTarget target, UUID targetId) {
        if (actorId == null || target == null || targetId == null) {
            throw new IllegalArgumentException("view record is incomplete");
        }
        mapper.insertView(actorId, target.actionCode, target.targetType, targetId, UUID.randomUUID().toString(),
                OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC));
    }

    private static OpsUserSummaryView summary(UserRow row) {
        OpsUserStatus status = "ACTIVE".equals(row.status()) && row.closing()
                ? OpsUserStatus.CLOSING
                : OpsUserStatus.valueOf(row.status());
        List<String> roles = row.roles() == null || row.roles().isEmpty()
                ? List.of()
                : Arrays.stream(row.roles().split(",")).toList();
        String loginName = PhoneNumbers.isValid(row.loginName()) ? PhoneNumbers.mask(row.loginName()) : row.loginName();
        return new OpsUserSummaryView(row.id(), row.displayName(), loginName, PhoneNumbers.mask(row.mobile()),
                status, row.registeredVia(), roles, row.createdAt());
    }

    /** q 的用法：手机号、账号 ID 精确；其他同时按登录名精确、显示名包含查。 */
    record Query(String phone, UUID userId, String loginName, String displayName) {

        static Query parse(String query) {
            if (query == null || query.isBlank()) {
                return new Query(null, null, null, null);
            }
            String text = query.strip();
            if (text.codePointCount(0, text.length()) > MAX_QUERY) {
                throw new IllegalArgumentException("query is too long");
            }
            if (PhoneNumbers.isValid(text)) {
                return new Query(text, null, null, null);
            }
            if (text.length() == 36) {
                try {
                    return new Query(null, UUID.fromString(text), null, null);
                } catch (IllegalArgumentException notAnId) {
                    // 36 位但不是 UUID 的，按名字查。
                }
            }
            String cleaned = text.replaceAll("[%_\\\\]", "");
            String displayName = cleaned.isEmpty()
                    || cleaned.codePointCount(0, cleaned.length()) > MAX_DISPLAY_NAME_QUERY
                    ? null
                    : "%" + cleaned + "%";
            return new Query(null, null, text, displayName);
        }
    }

    public record OpsUserSummaryView(UUID id, String displayName, String loginName, String maskedPhone,
                                     OpsUserStatus status, String registeredVia, List<String> roles,
                                     OffsetDateTime createdAt) {
    }

    public record OpsUserPageView(int page, int size, long total, boolean hasMore, List<OpsUserSummaryView> items) {
    }

    public record OpsUserDetailView(UUID id, String displayName, String loginName, String maskedPhone,
                                    OpsUserStatus status, String registeredVia, List<String> roles,
                                    OffsetDateTime createdAt, OffsetDateTime closingDueAt, OffsetDateTime closedAt,
                                    List<OpsUserOrganizationView> organizations,
                                    List<OpsUserEnterpriseView> enterprises, int activeDeviceCount,
                                    OffsetDateTime lastActiveAt, long orderCount) {
    }

    public record OpsUserOrganizationView(UUID id, String name, String type) {
    }

    public record OpsUserEnterpriseView(UUID id, String name, String memberRole, String memberStatus) {
    }
}
