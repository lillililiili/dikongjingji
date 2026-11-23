package com.lowaltitude.inspection.identity.application;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.lowaltitude.inspection.identity.infrastructure.persistence.OrganizationOnboardingMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.PlatformOperatorBootstrapMapper;
import org.springframework.transaction.annotation.Transactional;

/**
 * 首个平台运营账号的一次性引导（S6）。生产库里没有任何平台运营账号时运营端登录不了，运营端也不能授平台角色；
 * 这里在启动时（只在运维设了登录名时）经身份模块建一个账号：挂在平台机构下（没有平台机构就建一个），授全局的
 * PLATFORM_OPERATOR。账号体系、口令摘要（BCrypt）和授角色的写法都沿用机构入驻，不另起一套。
 *
 * <p>登录名已存在时什么都不改（不改口令、不加角色），所以变量忘了删、重启多少次都不会覆盖口令。口令只在要建账号时才读，
 * 只拿来算摘要，不进日志、异常和审计。
 */
public class PlatformOperatorBootstrapService {

    public static final String ROLE = "PLATFORM_OPERATOR";
    public static final String PLATFORM_ORGANIZATION_CODE = "PLATFORM";
    public static final String PLATFORM_ORGANIZATION_NAME = "平台";
    public static final String DEFAULT_DISPLAY_NAME = "平台运营";
    public static final int MIN_PASSWORD_LENGTH = 12;
    /** BCrypt 只用前 72 个字节；只收可打印 ASCII，64 个字符以内不会被截断。 */
    public static final int MAX_PASSWORD_LENGTH = 64;

    public enum Outcome {
        CREATED,
        ALREADY_EXISTS
    }

    /** 登录名、显示名或口令不合要求。消息只说哪条规则，不带口令。 */
    public static final class RejectedException extends RuntimeException {

        public RejectedException(String message) {
            super(message);
        }
    }

    private final OrganizationOnboardingMapper accounts;
    private final PlatformOperatorBootstrapMapper mapper;
    private final OnboardingPorts.PasswordHasher hasher;
    private final Clock clock;

    public PlatformOperatorBootstrapService(OrganizationOnboardingMapper accounts, PlatformOperatorBootstrapMapper mapper,
                                            OnboardingPorts.PasswordHasher hasher, Clock clock) {
        this.accounts = accounts;
        this.mapper = mapper;
        this.hasher = hasher;
        this.clock = clock;
    }

    @Transactional
    public Outcome ensurePlatformOperator(String loginName, String displayName, Supplier<String> password) {
        String login = loginName == null ? "" : loginName.strip();
        if (!OrganizationOnboardingService.LOGIN_NAME.matcher(login).matches()) {
            throw new RejectedException("登录名要以小写字母开头，4–32 位小写字母、数字、下划线或短横线");
        }
        String name = displayName == null || displayName.isBlank() ? DEFAULT_DISPLAY_NAME : displayName.strip();
        if (name.length() > OrganizationOnboardingService.MAX_DISPLAY_NAME) {
            throw new RejectedException("显示名最多 " + OrganizationOnboardingService.MAX_DISPLAY_NAME + " 个字");
        }
        if (mapper.loginNameTaken(login)) {
            return Outcome.ALREADY_EXISTS;
        }
        String raw = password.get();
        passwordProblem(raw, login).ifPresent(problem -> {
            throw new RejectedException(problem);
        });

        OffsetDateTime at = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        UUID organizationId = platformOrganization(at);
        UUID accountId = accounts.insertAccount(login, name, hasher.hash(raw), organizationId, at);
        if (accountId == null) {
            // 同时有另一台后端用同一个登录名建好了。
            return Outcome.ALREADY_EXISTS;
        }
        accounts.insertMember(organizationId, accountId, at);
        if (accounts.grantRole(accountId, ROLE, null, at) != 1) {
            throw new IllegalStateException("role " + ROLE + " is missing or inactive; identity migrations did not run");
        }
        mapper.recordBootstrap(accountId, organizationId, at);
        return Outcome.CREATED;
    }

    /**
     * 口令规则：12–64 个可打印 ASCII 字符（不含空格），大写、小写、数字、符号四类里至少三类，不包含登录名。
     * 返回第一条不满足的规则；满足时为空。
     */
    public static Optional<String> passwordProblem(String password, String login) {
        if (password == null || password.isEmpty()) {
            return Optional.of("没有给口令");
        }
        if (password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            return Optional.of("口令长度要在 " + MIN_PASSWORD_LENGTH + "–" + MAX_PASSWORD_LENGTH + " 个字符之间");
        }
        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        boolean symbol = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (c <= 0x20 || c >= 0x7f) {
                return Optional.of("口令只能用英文字母、数字和英文符号，不能有空格或其他字符");
            }
            if (c >= 'A' && c <= 'Z') {
                upper = true;
            } else if (c >= 'a' && c <= 'z') {
                lower = true;
            } else if (c >= '0' && c <= '9') {
                digit = true;
            } else {
                symbol = true;
            }
        }
        int kinds = (upper ? 1 : 0) + (lower ? 1 : 0) + (digit ? 1 : 0) + (symbol ? 1 : 0);
        if (kinds < 3) {
            return Optional.of("口令要包含大写字母、小写字母、数字、符号中的至少三类");
        }
        if (login != null && !login.isEmpty()
                && password.toLowerCase(Locale.ROOT).contains(login.toLowerCase(Locale.ROOT))) {
            return Optional.of("口令不能包含登录名");
        }
        return Optional.empty();
    }

    private UUID platformOrganization(OffsetDateTime at) {
        UUID existing = mapper.findActivePlatformOrganization(PLATFORM_ORGANIZATION_CODE);
        if (existing != null) {
            return existing;
        }
        UUID created = mapper.insertPlatformOrganization(PLATFORM_ORGANIZATION_CODE, PLATFORM_ORGANIZATION_NAME, at);
        if (created != null) {
            return created;
        }
        // 另一台后端刚建好，或者编码 PLATFORM 被一家停用的机构占着。
        UUID raced = mapper.findActivePlatformOrganization(PLATFORM_ORGANIZATION_CODE);
        if (raced == null) {
            throw new IllegalStateException("organization code " + PLATFORM_ORGANIZATION_CODE
                    + " is taken by an organization that is not an active platform organization");
        }
        return raced;
    }
}
