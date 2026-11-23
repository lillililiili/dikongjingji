package com.lowaltitude.inspection.identity.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.identity.application.OnboardingException.Error;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OrganizationImportMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OrganizationImportMapper.LoginRef;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OrganizationImportMapper.OrganizationRef;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OrganizationOnboardingMapper;
import org.springframework.transaction.annotation.Transactional;

/**
 * 批量导入经营主体和操作员账号（S2-3，contracts/openapi-ops-import.yaml 的 ORGANIZATIONS 类，模板 01、01b）。
 * 校验在导入工具里做完，这里在它的事务里写：
 * <ul>
 *   <li>经营主体按 organization_code 找：没有就新建（经营机构 PROVIDER），有就改名称、状态和资质审核时间；
 *       qualification_reviewed_on 是北京日期，写成当天 0 点的审核时间（App 的“资质已审核”只看它）。</li>
 *   <li>操作员按登录名找：没有就建账号（默认机构是这家），服务端生成一次性初始口令，只在这次返回；已有、并且默认机构就是
 *       这家时改显示名、补上没有的角色（不收回已有角色）。登录名属于别的机构时拒绝。</li>
 *   <li>新建机构、新建账号、补角色各在机构的操作记录里记一条。</li>
 * </ul>
 */
public class OrganizationImportService {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    private final OrganizationImportMapper imports;
    private final OrganizationOnboardingMapper onboarding;
    private final OrganizationOnboardingService passwords;
    private final OnboardingPorts.PasswordHasher hasher;
    private final Clock clock;

    public OrganizationImportService(OrganizationImportMapper imports, OrganizationOnboardingMapper onboarding,
                                     OrganizationOnboardingService passwords, OnboardingPorts.PasswordHasher hasher,
                                     Clock clock) {
        this.imports = imports;
        this.onboarding = onboarding;
        this.passwords = passwords;
        this.hasher = hasher;
        this.clock = clock;
    }

    // ---- 预检用的查询 ----

    /** 这些编码在库里的机构（不认识的不在结果里）。 */
    @Transactional(readOnly = true)
    public Map<String, OrganizationSummary> organizationsByCode(Collection<String> codes) {
        Map<String, OrganizationSummary> found = new HashMap<>();
        List<String> wanted = codes == null ? List.of() : codes.stream().distinct().toList();
        if (wanted.isEmpty()) {
            return found;
        }
        imports.findByCodes(wanted).forEach(row -> found.put(row.organizationCode(),
                new OrganizationSummary(row.id(), row.organizationType(), row.status())));
        return found;
    }

    /** 这些登录名已被哪家机构的账号用了（值是账号的默认机构，可能为空）；没被用的不在结果里。 */
    @Transactional(readOnly = true)
    public Map<String, Optional<UUID>> loginOwners(Collection<String> logins) {
        Map<String, Optional<UUID>> owners = new HashMap<>();
        List<String> wanted = logins == null ? List.of() : logins.stream().distinct().toList();
        if (wanted.isEmpty()) {
            return owners;
        }
        imports.findLogins(wanted).forEach(row -> owners.put(row.loginName(),
                Optional.ofNullable(row.defaultOrganizationId())));
        return owners;
    }

    // ---- 写入 ----

    @Transactional
    public Result importOrganizations(List<OrganizationInput> organizations, List<OperatorInput> operators,
                                      String batchLabel, OrganizationOnboardingService.Operator operator) {
        if (operator == null || operator.accountId() == null || operator.displayName() == null
                || operator.displayName().isBlank()) {
            throw new IllegalArgumentException("operator is required");
        }
        OffsetDateTime at = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<Written> written = new ArrayList<>();
        Map<String, UUID> ids = new LinkedHashMap<>();
        for (OrganizationInput input : organizations) {
            OffsetDateTime reviewedAt = input.qualificationReviewedOn() == null ? null
                    : input.qualificationReviewedOn().atStartOfDay(BEIJING).toOffsetDateTime()
                            .withOffsetSameInstant(ZoneOffset.UTC);
            OrganizationRef existing = imports.findByCode(input.code());
            if (existing == null) {
                UUID id = imports.insertOrganization(input.code(), input.name(), "PROVIDER", input.status(),
                        reviewedAt, at);
                ids.put(input.code(), id);
                written.add(new Written(WrittenKind.ORGANIZATION, input.code(), id, true));
                record(id, "CREATE", "批量导入（" + batchLabel + "）新建经营机构“" + input.name() + "”（"
                        + input.code() + "）", operator, at);
            } else {
                if (!"PROVIDER".equals(existing.organizationType())) {
                    // 预检拦过；库在两步之间被改了时整批回滚。
                    throw new OnboardingException(Error.ROLE_NOT_GRANTABLE);
                }
                imports.updateOrganization(existing.id(), input.name(), input.status(), reviewedAt, at);
                ids.put(input.code(), existing.id());
                written.add(new Written(WrittenKind.ORGANIZATION, input.code(), existing.id(), false));
            }
        }

        // 同一个登录名的几行（一行一个角色）合在一起。
        Map<String, OperatorInput> merged = new LinkedHashMap<>();
        Map<String, Set<String>> roles = new LinkedHashMap<>();
        for (OperatorInput input : operators) {
            merged.putIfAbsent(input.loginName(), input);
            roles.computeIfAbsent(input.loginName(), ignored -> new LinkedHashSet<>()).addAll(input.roles());
        }
        List<CreatedAccount> created = new ArrayList<>();
        for (OperatorInput input : merged.values()) {
            UUID organizationId = ids.get(input.organizationCode());
            if (organizationId == null) {
                OrganizationRef existing = imports.findByCode(input.organizationCode());
                if (existing == null) {
                    throw new OnboardingException(Error.NOT_FOUND);
                }
                organizationId = existing.id();
            }
            Set<OpsGrantableRole> wanted = new LinkedHashSet<>();
            for (String code : roles.get(input.loginName())) {
                OpsGrantableRole role = OpsGrantableRole.valueOf(code);
                if (role.organizationType() != OpsOrganizationType.PROVIDER) {
                    throw new OnboardingException(Error.ROLE_NOT_GRANTABLE);
                }
                wanted.add(role);
            }
            LoginRef login = imports.findLogin(input.loginName());
            if (login == null) {
                String password = passwords.initialPassword();
                UUID accountId = onboarding.insertAccount(input.loginName(), input.displayName(),
                        hasher.hash(password), organizationId, at);
                if (accountId == null) {
                    throw new OnboardingException(Error.LOGIN_NAME_TAKEN);
                }
                onboarding.insertMember(organizationId, accountId, at);
                for (OpsGrantableRole role : wanted) {
                    onboarding.grantRole(accountId, role.name(), organizationId, at);
                }
                written.add(new Written(WrittenKind.OPERATOR, input.loginName(), accountId, true));
                created.add(new CreatedAccount(input.organizationCode(), input.loginName(), password));
                record(organizationId, "ACCOUNT_CREATE", "批量导入（" + batchLabel + "）新建账号 " + input.loginName()
                        + "（" + input.displayName() + "），角色：" + roleLabels(wanted), operator, at);
                continue;
            }
            if (!organizationId.equals(login.defaultOrganizationId())) {
                throw new OnboardingException(Error.LOGIN_NAME_TAKEN);
            }
            imports.updateDisplayName(login.accountId(), input.displayName(), at);
            Set<String> current = Set.copyOf(imports.findRoles(login.accountId(), organizationId, at));
            List<OpsGrantableRole> granted = new ArrayList<>();
            for (OpsGrantableRole role : wanted) {
                if (!current.contains(role.name())) {
                    onboarding.grantRole(login.accountId(), role.name(), organizationId, at);
                    granted.add(role);
                }
            }
            if (!granted.isEmpty()) {
                onboarding.bumpTokenVersion(login.accountId(), at);
                record(organizationId, "ROLES_CHANGE", "批量导入（" + batchLabel + "）账号 " + input.loginName()
                        + "：授予 " + roleLabels(granted) + "（需重新登录）", operator, at);
            }
            written.add(new Written(WrittenKind.OPERATOR, input.loginName(), login.accountId(), false));
        }
        return new Result(written, created);
    }

    private void record(UUID organizationId, String action, String detail,
                        OrganizationOnboardingService.Operator operator, OffsetDateTime at) {
        String text = detail.codePointCount(0, detail.length()) <= 500
                ? detail : detail.substring(0, detail.offsetByCodePoints(0, 500));
        String name = operator.displayName().codePointCount(0, operator.displayName().length()) <= 80
                ? operator.displayName() : operator.displayName().substring(0, operator.displayName().offsetByCodePoints(0, 80));
        onboarding.insertOperation(organizationId, action, text, operator.accountId(), name, at);
    }

    private static String roleLabels(Collection<OpsGrantableRole> roles) {
        return String.join("、", roles.stream()
                .map(role -> role == OpsGrantableRole.LIVE_HOST ? "直播主办" : "服务商操作员")
                .toList());
    }

    /** 一家经营主体（模板 01）。status：ACTIVE / DISABLED；qualificationReviewedOn 可空。 */
    public record OrganizationInput(String code, String name, String status, LocalDate qualificationReviewedOn) {
    }

    /** 一行操作员（模板 01b），一行一个角色；同一个登录名的几行会合在一起。 */
    public record OperatorInput(String organizationCode, String loginName, String displayName, List<String> roles) {
    }

    public record OrganizationSummary(UUID id, String organizationType, String status) {
    }

    public enum WrittenKind {
        ORGANIZATION,
        OPERATOR
    }

    public record Written(WrittenKind kind, String reference, UUID targetId, boolean created) {
    }

    /** 新账号的一次性初始口令，只在这一次返回。 */
    public record CreatedAccount(String organizationCode, String loginName, String initialPassword) {
    }

    public record Result(List<Written> written, List<CreatedAccount> createdAccounts) {
    }
}
