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
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.identity.application.OrganizationSignupService.SignupDraft;
import com.lowaltitude.inspection.identity.application.account.AccountException;
import com.lowaltitude.inspection.identity.application.account.SmsCodeService;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OrganizationOnboardingMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OrganizationOnboardingMapper.AccountRow;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OrganizationOnboardingMapper.OrganizationRow;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OrganizationOnboardingMapper.QualificationRow;
import org.junit.jupiter.api.Test;

/** 商家自助入驻的规则。mapper、短信验证码、机构入驻服务都是替身。示例的统一社会信用代码只符合格式。 */
class OrganizationSignupServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-24T02:00:00Z");
    private static final OffsetDateTime AT = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final UUID KEY = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final UUID ORG = UUID.fromString("c1000000-0000-4000-8000-000000000077");
    private static final UUID ACCOUNT = UUID.fromString("c1000000-0000-4000-8000-0000000000d1");
    private static final String LICENSE = "00000000000000000X";

    private final OrganizationOnboardingMapper mapper = mock(OrganizationOnboardingMapper.class);
    private final OrganizationOnboardingService onboarding = mock(OrganizationOnboardingService.class);
    private final SmsCodeService smsCodes = mock(SmsCodeService.class);
    private final OrganizationSignupService service = new OrganizationSignupService(mapper, onboarding,
            city -> "370500".equals(city) ? Optional.of("东营市") : Optional.empty(),
            raw -> "hash:" + raw, smsCodes, Clock.fixed(NOW, ZoneOffset.UTC));

    private static SignupDraft draft() {
        return new SignupDraft("东营翼飞科技", "370500", "东营翼飞科技有限公司", LICENSE.toLowerCase(), "无人机销售",
                "王芳", "13800001234", "123456", "yifei-shop", "Fly2026ok");
    }

    private void organizationExists() {
        when(mapper.findOrganization(ORG)).thenReturn(new OrganizationRow(ORG, "ORG-20261124-ABCD1234", "东营翼飞科技",
                "PROVIDER", "370500", "ACTIVE", null, AT, 0, "SUBMITTED"));
        when(mapper.findAccounts(ORG)).thenReturn(List.of(new AccountRow(ACCOUNT, "yifei-shop", "王芳", "ACTIVE", AT)));
        when(mapper.findQualification(ORG)).thenReturn(new QualificationRow(ORG, "东营翼飞科技有限公司", LICENSE,
                "无人机销售", null, "SUBMITTED", AT, null, null, 0));
    }

    @Test
    void signUpBuildsTheOrganizationQualificationAccountAndRoleInOneGo() {
        when(onboarding.organizationCode(any())).thenReturn("ORG-20261124-ABCD1234");
        when(mapper.insertOrganization(eq("ORG-20261124-ABCD1234"), eq("东营翼飞科技"), eq("PROVIDER"), eq("370500"),
                eq(KEY), eq(AT))).thenReturn(ORG);
        when(mapper.insertSignupAccount("yifei-shop", "王芳", "hash:Fly2026ok", "13800001234", ORG, AT))
                .thenReturn(ACCOUNT);
        organizationExists();

        OrganizationSignupCreated created = service.signUp(KEY, draft());

        assertThat(created.created()).isTrue();
        assertThat(created.result().loginName()).isEqualTo("yifei-shop");
        assertThat(created.result().qualificationStatus()).isEqualTo(OpsQualificationStatus.SUBMITTED);
        verify(smsCodes).verify("13800001234", SmsCodeService.SIGNUP, "123456", null);
        verify(mapper).insertMember(ORG, ACCOUNT, AT);
        verify(mapper).grantRole(ACCOUNT, "PROVIDER_OPERATOR", ORG, AT);
        // 统一社会信用代码转成大写；资质由申请人自己的账号提交。
        verify(mapper).insertQualification(ORG, "东营翼飞科技有限公司", LICENSE, "无人机销售", null, ACCOUNT, AT);
        verify(mapper).insertOperation(eq(ORG), eq("SIGNUP"), anyString(), eq(ACCOUNT), eq("王芳"), eq(AT));
        verify(mapper).insertOperation(eq(ORG), eq("QUALIFICATION_SUBMIT"), anyString(), eq(ACCOUNT), eq("王芳"),
                eq(AT));
    }

    @Test
    void takenLoginNameOrPhoneIsRefusedBeforeTheCodeIsSpent() {
        when(mapper.countAccountsByLoginName("yifei-shop")).thenReturn(1L);
        assertThatThrownBy(() -> service.signUp(KEY, draft()))
                .isInstanceOf(OnboardingException.class)
                .extracting(e -> ((OnboardingException) e).error())
                .isEqualTo(OnboardingException.Error.LOGIN_NAME_TAKEN);

        when(mapper.countAccountsByLoginName("yifei-shop")).thenReturn(0L);
        when(mapper.countAccountsByMobile("13800001234")).thenReturn(1L);
        assertThatThrownBy(() -> service.signUp(KEY, draft()))
                .isInstanceOf(AccountException.class)
                .extracting(e -> ((AccountException) e).error())
                .isEqualTo(AccountException.Error.PHONE_TAKEN);
        verify(smsCodes, never()).verify(anyString(), anyString(), anyString(), isNull());
    }

    @Test
    void replayWithTheSameKeyReturnsTheFirstOrganizationWithoutWriting() {
        when(mapper.findOrganizationByCreateKey(KEY)).thenReturn(ORG);
        organizationExists();

        OrganizationSignupCreated created = service.signUp(KEY, draft());

        assertThat(created.created()).isFalse();
        assertThat(created.result().organizationId()).isEqualTo(ORG);
        verify(mapper, never()).insertOrganization(any(), any(), any(), any(), any(), any());
        verify(smsCodes, never()).verify(anyString(), anyString(), anyString(), isNull());
    }

    @Test
    void weakPasswordsUnknownCitiesAndBadLicensesAreRefused() {
        SignupDraft weak = new SignupDraft("东营翼飞科技", "370500", "东营翼飞科技有限公司", LICENSE, null, "王芳",
                "13800001234", "123456", "yifei-shop", "onlyletters");
        assertThatThrownBy(() -> service.signUp(KEY, weak)).isInstanceOf(IllegalArgumentException.class);

        SignupDraft otherCity = new SignupDraft("东营翼飞科技", "370100", "东营翼飞科技有限公司", LICENSE, null, "王芳",
                "13800001234", "123456", "yifei-shop", "Fly2026ok");
        assertThatThrownBy(() -> service.signUp(KEY, otherCity))
                .isInstanceOf(OnboardingException.class)
                .extracting(e -> ((OnboardingException) e).error())
                .isEqualTo(OnboardingException.Error.CITY_UNKNOWN);

        SignupDraft badLicense = new SignupDraft("东营翼飞科技", "370500", "东营翼飞科技有限公司", "1234", null, "王芳",
                "13800001234", "123456", "yifei-shop", "Fly2026ok");
        assertThatThrownBy(() -> service.signUp(KEY, badLicense)).isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).insertOrganization(any(), any(), any(), any(), any(), any());
    }

    @Test
    void sendCodeUsesTheSignupPurpose() {
        service.sendCode("13800001234");
        verify(smsCodes).send("13800001234", SmsCodeService.SIGNUP, null);
    }
}
