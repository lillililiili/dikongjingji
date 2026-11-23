package com.lowaltitude.inspection.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.lowaltitude.inspection.identity.application.PlatformOperatorBootstrapService.Outcome;
import com.lowaltitude.inspection.identity.application.PlatformOperatorBootstrapService.RejectedException;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OrganizationOnboardingMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.PlatformOperatorBootstrapMapper;
import org.junit.jupiter.api.Test;

/**
 * 首个平台运营账号的一次性引导（S6）。mapper 用替身，SQL 文本由 PlatformOperatorBootstrapMapperSqlTest 检查。
 * 测试口令是现编的。
 */
class PlatformOperatorBootstrapServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-23T02:00:00Z");
    private static final OffsetDateTime AT = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final UUID PLATFORM_ORG = UUID.fromString("c1000000-0000-4000-8000-0000000000aa");
    private static final UUID ACCOUNT = UUID.fromString("c1000000-0000-4000-8000-0000000000c1");
    private static final String STRONG = "Unit-Test-Pass-42";

    private final OrganizationOnboardingMapper accounts = mock(OrganizationOnboardingMapper.class);
    private final PlatformOperatorBootstrapMapper mapper = mock(PlatformOperatorBootstrapMapper.class);
    private final List<String> hashed = new ArrayList<>();
    private final PlatformOperatorBootstrapService service = new PlatformOperatorBootstrapService(accounts, mapper,
            raw -> {
                hashed.add(raw);
                return "bcrypt-digest";
            }, Clock.fixed(NOW, ZoneOffset.UTC));

    private void freshDatabaseWithPlatformOrganization() {
        when(mapper.loginNameTaken("ops-admin")).thenReturn(false);
        when(mapper.findActivePlatformOrganization("PLATFORM")).thenReturn(PLATFORM_ORG);
        when(accounts.insertAccount(eq("ops-admin"), anyString(), anyString(), eq(PLATFORM_ORG), eq(AT)))
                .thenReturn(ACCOUNT);
        when(accounts.grantRole(ACCOUNT, "PLATFORM_OPERATOR", null, AT)).thenReturn(1);
    }

    @Test
    void createsTheAccountUnderThePlatformOrganizationWithTheGlobalOperatorRole() {
        freshDatabaseWithPlatformOrganization();

        Outcome outcome = service.ensurePlatformOperator(" ops-admin ", null, () -> STRONG);

        assertThat(outcome).isEqualTo(Outcome.CREATED);
        assertThat(hashed).containsExactly(STRONG);
        verify(accounts).insertAccount("ops-admin", "平台运营", "bcrypt-digest", PLATFORM_ORG, AT);
        verify(accounts).insertMember(PLATFORM_ORG, ACCOUNT, AT);
        verify(accounts).grantRole(ACCOUNT, "PLATFORM_OPERATOR", null, AT);
        verify(mapper).recordBootstrap(ACCOUNT, PLATFORM_ORG, AT);
        verify(mapper, never()).insertPlatformOrganization(anyString(), anyString(), any());
    }

    @Test
    void createsThePlatformOrganizationWhenThereIsNone() {
        when(mapper.loginNameTaken("ops-admin")).thenReturn(false);
        when(mapper.findActivePlatformOrganization("PLATFORM")).thenReturn(null);
        when(mapper.insertPlatformOrganization("PLATFORM", "平台", AT)).thenReturn(PLATFORM_ORG);
        when(accounts.insertAccount("ops-admin", "运营负责人", "bcrypt-digest", PLATFORM_ORG, AT)).thenReturn(ACCOUNT);
        when(accounts.grantRole(ACCOUNT, "PLATFORM_OPERATOR", null, AT)).thenReturn(1);

        assertThat(service.ensurePlatformOperator("ops-admin", "运营负责人", () -> STRONG)).isEqualTo(Outcome.CREATED);

        verify(mapper).insertPlatformOrganization("PLATFORM", "平台", AT);
        verify(accounts).insertMember(PLATFORM_ORG, ACCOUNT, AT);
    }

    @Test
    void anExistingAccountIsLeftAloneAndThePasswordIsNotEvenRead() {
        when(mapper.loginNameTaken("ops-admin")).thenReturn(true);
        AtomicInteger reads = new AtomicInteger();
        Supplier<String> password = () -> {
            reads.incrementAndGet();
            return STRONG;
        };

        assertThat(service.ensurePlatformOperator("ops-admin", null, password)).isEqualTo(Outcome.ALREADY_EXISTS);

        assertThat(reads).hasValue(0);
        verifyNoInteractions(accounts);
        verify(mapper, never()).recordBootstrap(any(), any(), any());
    }

    @Test
    void aConcurrentCreateCountsAsExisting() {
        freshDatabaseWithPlatformOrganization();
        when(accounts.insertAccount(eq("ops-admin"), anyString(), anyString(), eq(PLATFORM_ORG), eq(AT)))
                .thenReturn(null);

        assertThat(service.ensurePlatformOperator("ops-admin", null, () -> STRONG)).isEqualTo(Outcome.ALREADY_EXISTS);

        verify(accounts, never()).insertMember(any(), any(), any());
        verify(accounts, never()).grantRole(any(), anyString(), any(), any());
    }

    @Test
    void weakPasswordsAreRejectedBeforeAnythingIsWritten() {
        when(mapper.loginNameTaken("ops-admin")).thenReturn(false);
        for (String weak : List.of("", "Short-1a", "alllowercaseletters", "NoDigitsOrSymbolsHere",
                "Has space 12345A", "中文口令Abcdef12345", "Ops-Admin-2026!x", "A".repeat(40) + "b1" + "c".repeat(30))) {
            assertThatThrownBy(() -> service.ensurePlatformOperator("ops-admin", null, () -> weak))
                    .as(weak)
                    .isInstanceOf(RejectedException.class)
                    .satisfies(rejected -> {
                        if (!weak.isEmpty()) {
                            assertThat(rejected.getMessage()).doesNotContain(weak);
                        }
                    });
        }
        verify(accounts, never()).insertAccount(anyString(), anyString(), anyString(), any(), any());
        verify(mapper, never()).insertPlatformOrganization(anyString(), anyString(), any());
        assertThat(hashed).isEmpty();
    }

    @Test
    void passwordRulesAcceptThreeKindsOfCharacters() {
        assertThat(PlatformOperatorBootstrapService.passwordProblem("lower-and-1234", "ops-admin")).isEmpty();
        assertThat(PlatformOperatorBootstrapService.passwordProblem("UPPERlower1234", "ops-admin")).isEmpty();
        assertThat(PlatformOperatorBootstrapService.passwordProblem("upperlower1234", "ops-admin"))
                .contains("口令要包含大写字母、小写字母、数字、符号中的至少三类");
        assertThat(PlatformOperatorBootstrapService.passwordProblem("xOPS-ADMIN-123", "ops-admin"))
                .contains("口令不能包含登录名");
    }

    @Test
    void rejectsBadLoginNamesAndDisplayNames() {
        for (String login : List.of("", "Ops", "1ops", "ops admin", "ops.admin")) {
            assertThatThrownBy(() -> service.ensurePlatformOperator(login, null, () -> STRONG))
                    .as(login).isInstanceOf(RejectedException.class);
        }
        assertThatThrownBy(() -> service.ensurePlatformOperator("ops-admin", "名".repeat(41), () -> STRONG))
                .isInstanceOf(RejectedException.class);
        verifyNoInteractions(accounts);
    }

    @Test
    void failsLoudlyWhenTheRoleIsMissingOrThePlatformCodeIsTaken() {
        freshDatabaseWithPlatformOrganization();
        when(accounts.grantRole(ACCOUNT, "PLATFORM_OPERATOR", null, AT)).thenReturn(0);
        assertThatThrownBy(() -> service.ensurePlatformOperator("ops-admin", null, () -> STRONG))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PLATFORM_OPERATOR");
        verify(mapper, never()).recordBootstrap(any(), any(), any());

        when(mapper.loginNameTaken("ops-root")).thenReturn(false);
        when(mapper.findActivePlatformOrganization("PLATFORM")).thenReturn(null);
        when(mapper.insertPlatformOrganization("PLATFORM", "平台", AT)).thenReturn(null);
        assertThatThrownBy(() -> service.ensurePlatformOperator("ops-root", null, () -> STRONG))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("organization code PLATFORM");
        verify(accounts, never()).insertAccount(eq("ops-root"), anyString(), anyString(), isNull(), any());
    }
}
