package com.lowaltitude.inspection.bootstrap.infrastructure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

import com.lowaltitude.inspection.identity.application.PlatformOperatorBootstrapService;
import com.lowaltitude.inspection.identity.application.PlatformOperatorBootstrapService.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * 首个平台运营账号的一次性引导（S6，docs/ops/部署与回滚.md §3.1）。只在设了 PLATFORM_BOOTSTRAP_OPERATOR_LOGIN
 * （platform.bootstrap.operator.login）时做事：经身份模块的 {@link PlatformOperatorBootstrapService} 建账号并授平台运营角色；
 * 账号已存在时不改任何东西，只记一行日志。
 *
 * <p>口令来自 PLATFORM_BOOTSTRAP_OPERATOR_PASSWORD_FILE（推荐，文件里只有口令一行）或 PLATFORM_BOOTSTRAP_OPERATOR_PASSWORD，
 * 只能给一个；只在真要建账号时才读。配置不对、口令不合要求时抛异常，后端不启动；日志和异常里没有口令，也没有口令文件路径。
 */
public final class PlatformOperatorBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformOperatorBootstrapRunner.class);

    /** 引导配置。toString 不输出口令。 */
    public record Settings(String login, String password, Path passwordFile, String displayName) {

        boolean requested() {
            return login != null && !login.isBlank();
        }

        @Override
        public String toString() {
            return "Settings[login=" + login + ", password=<redacted>, passwordFile=" + (passwordFile != null)
                    + ", displayName=" + displayName + "]";
        }
    }

    private final PlatformOperatorBootstrapService service;
    private final Settings settings;

    public PlatformOperatorBootstrapRunner(PlatformOperatorBootstrapService service, Settings settings) {
        this.service = service;
        this.settings = settings;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!settings.requested()) {
            return;
        }
        boolean hasPassword = settings.password() != null && !settings.password().isEmpty();
        if (hasPassword && settings.passwordFile() != null) {
            throw new IllegalStateException("platform operator bootstrap: 口令只能给一个，"
                    + "PLATFORM_BOOTSTRAP_OPERATOR_PASSWORD 和 PLATFORM_BOOTSTRAP_OPERATOR_PASSWORD_FILE 二选一");
        }
        String login = settings.login().strip();
        Outcome outcome;
        try {
            outcome = service.ensurePlatformOperator(login, settings.displayName(), password(hasPassword));
        } catch (PlatformOperatorBootstrapService.RejectedException rejected) {
            throw new IllegalStateException("platform operator bootstrap: " + rejected.getMessage());
        }
        if (outcome == Outcome.CREATED) {
            log.warn("platform operator bootstrap: created platform operator account {}; remove the "
                    + "PLATFORM_BOOTSTRAP_OPERATOR_* settings and the password file now", login);
        } else {
            log.info("platform operator bootstrap: account {} already exists, nothing changed; "
                    + "remove the PLATFORM_BOOTSTRAP_OPERATOR_* settings", login);
        }
    }

    private Supplier<String> password(boolean hasPassword) {
        return () -> {
            if (settings.passwordFile() != null) {
                try {
                    return stripLineEnd(Files.readString(settings.passwordFile(), StandardCharsets.UTF_8));
                } catch (IOException | RuntimeException unreadable) {
                    throw new IllegalStateException("platform operator bootstrap: 口令文件读不到"
                            + "（不存在、不是 UTF-8 文本，或运行后端的用户没有读权限）");
                }
            }
            if (hasPassword) {
                return settings.password();
            }
            throw new IllegalStateException("platform operator bootstrap: 没有口令，设 "
                    + "PLATFORM_BOOTSTRAP_OPERATOR_PASSWORD_FILE（推荐）或 PLATFORM_BOOTSTRAP_OPERATOR_PASSWORD");
        };
    }

    /** 文件末尾的一个换行（echo、编辑器会加）不算口令的一部分；别的空白照原样交给口令规则判断。 */
    static String stripLineEnd(String content) {
        if (content.endsWith("\r\n")) {
            return content.substring(0, content.length() - 2);
        }
        if (content.endsWith("\n")) {
            return content.substring(0, content.length() - 1);
        }
        return content;
    }
}
