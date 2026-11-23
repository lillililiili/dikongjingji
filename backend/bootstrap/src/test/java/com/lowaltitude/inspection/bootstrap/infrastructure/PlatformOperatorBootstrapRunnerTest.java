package com.lowaltitude.inspection.bootstrap.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.identity.application.PlatformOperatorBootstrapService;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OrganizationOnboardingMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.PlatformOperatorBootstrapMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 首个平台运营账号引导的启动入口（S6）：没设登录名什么都不做；口令从文件或环境变量来、二选一；口令不合要求时后端不启动；
 * 口令和口令文件路径不进日志和异常。身份模块的服务是真的，mapper 用替身。测试口令是现编的。
 */
@ExtendWith(OutputCaptureExtension.class)
class PlatformOperatorBootstrapRunnerTest {

    private static final String STRONG = "Runner-Test-Pass-77";
    private static final UUID PLATFORM_ORG = UUID.fromString("c1000000-0000-4000-8000-0000000000aa");
    private static final UUID ACCOUNT = UUID.fromString("c1000000-0000-4000-8000-0000000000c1");

    @TempDir
    Path secrets;

    private final OrganizationOnboardingMapper accounts = mock(OrganizationOnboardingMapper.class);
    private final PlatformOperatorBootstrapMapper mapper = mock(PlatformOperatorBootstrapMapper.class);
    private final List<String> hashed = new ArrayList<>();
    private final PlatformOperatorBootstrapService service = new PlatformOperatorBootstrapService(accounts, mapper,
            raw -> {
                hashed.add(raw);
                return "bcrypt-digest";
            }, Clock.fixed(Instant.parse("2026-11-23T02:00:00Z"), ZoneOffset.UTC));

    private void run(String login, String password, Path file) {
        new PlatformOperatorBootstrapRunner(service,
                new PlatformOperatorBootstrapRunner.Settings(login, password, file, null))
                .run(new DefaultApplicationArguments());
    }

    private void freshDatabase() {
        when(mapper.loginNameTaken("ops-admin")).thenReturn(false);
        when(mapper.findActivePlatformOrganization("PLATFORM")).thenReturn(PLATFORM_ORG);
        when(accounts.insertAccount(eq("ops-admin"), anyString(), anyString(), eq(PLATFORM_ORG), any()))
                .thenReturn(ACCOUNT);
        when(accounts.grantRole(eq(ACCOUNT), eq("PLATFORM_OPERATOR"), eq(null), any())).thenReturn(1);
    }

    @Test
    void doesNothingWithoutALoginName() {
        run(null, STRONG, null);
        run("  ", STRONG, null);

        verifyNoInteractions(accounts, mapper);
    }

    @Test
    void createsTheAccountFromAPasswordFileAndKeepsThePasswordOutOfTheLog(CapturedOutput output) throws IOException {
        freshDatabase();
        Path file = Files.writeString(secrets.resolve("operator-password"), STRONG + "\n");

        run("ops-admin", null, file);

        assertThat(hashed).containsExactly(STRONG);
        verify(accounts).grantRole(eq(ACCOUNT), eq("PLATFORM_OPERATOR"), eq(null), any());
        assertThat(output).contains("created platform operator account ops-admin")
                .doesNotContain(STRONG).doesNotContain(secrets.toString());
    }

    @Test
    void createsTheAccountFromAnEnvironmentPassword() {
        freshDatabase();

        run("ops-admin", STRONG, null);

        assertThat(hashed).containsExactly(STRONG);
    }

    @Test
    void anExistingAccountOnlyLogsALine(CapturedOutput output) {
        when(mapper.loginNameTaken("ops-admin")).thenReturn(true);

        run("ops-admin", null, null);

        assertThat(output).contains("account ops-admin already exists, nothing changed");
        verifyNoInteractions(accounts);
    }

    @Test
    void refusesToStartOnConflictingMissingUnreadableOrWeakPasswords() throws IOException {
        Path file = Files.writeString(secrets.resolve("operator-password"), STRONG);
        assertThatThrownBy(() -> run("ops-admin", STRONG, file))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("二选一");
        verifyNoInteractions(mapper);

        when(mapper.loginNameTaken("ops-admin")).thenReturn(false);
        assertThatThrownBy(() -> run("ops-admin", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PLATFORM_BOOTSTRAP_OPERATOR_PASSWORD_FILE");

        Path missing = secrets.resolve("no-such-file");
        assertThatThrownBy(() -> run("ops-admin", null, missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("口令文件读不到")
                .hasMessageNotContaining(secrets.toString());

        String weak = "weakpassword";
        assertThatThrownBy(() -> run("ops-admin", weak, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("至少三类")
                .hasMessageNotContaining(weak);
        assertThatThrownBy(() -> run("Bad Login", STRONG, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("登录名");
        verify(accounts, never()).insertAccount(anyString(), anyString(), anyString(), any(), any());
        assertThat(hashed).isEmpty();
    }

    @Test
    void onlyTheFinalLineEndOfAPasswordFileIsDropped() {
        assertThat(PlatformOperatorBootstrapRunner.stripLineEnd("abc\r\n")).isEqualTo("abc");
        assertThat(PlatformOperatorBootstrapRunner.stripLineEnd("abc\n")).isEqualTo("abc");
        assertThat(PlatformOperatorBootstrapRunner.stripLineEnd("abc \n\n")).isEqualTo("abc \n");
        assertThat(new PlatformOperatorBootstrapRunner.Settings("ops-admin", STRONG, null, null).toString())
                .doesNotContain(STRONG);
    }
}
