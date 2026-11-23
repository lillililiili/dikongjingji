package com.lowaltitude.inspection.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.identity.application.OnboardingException.Error;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OrganizationOnboardingMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OrganizationOnboardingMapper.AccountRoleRow;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OrganizationOnboardingMapper.AccountRow;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OrganizationOnboardingMapper.AttachmentRow;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OrganizationOnboardingMapper.OrganizationRow;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OrganizationOnboardingMapper.QualificationRow;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * 机构入驻与资质审核的规则（S3-1）。mapper 用替身，SQL 文本由 OrganizationOnboardingMapperSqlTest 检查。
 * 示例的统一社会信用代码只符合格式，不对应任何真实主体。
 */
class OrganizationOnboardingServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-22T02:00:00Z");
    private static final OffsetDateTime AT = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final UUID ORG = UUID.fromString("c1000000-0000-4000-8000-000000000099");
    private static final UUID OPERATOR_ID = UUID.fromString("90000000-0000-4000-8000-0000000000b1");
    private static final UUID ACCOUNT = UUID.fromString("c1000000-0000-4000-8000-0000000000c1");
    private static final String LICENSE = "00000000000000000X";
    private static final OrganizationOnboardingService.Operator OPERATOR =
            new OrganizationOnboardingService.Operator(OPERATOR_ID, "平台运营（示例）");

    private final OrganizationOnboardingMapper mapper = mock(OrganizationOnboardingMapper.class);
    private final List<String> hashed = new ArrayList<>();
    private final List<String> stored = new ArrayList<>();
    private boolean storeUp = true;
    private final OnboardingPorts.QualificationFiles files = new OnboardingPorts.QualificationFiles() {
        @Override
        public void put(String key, byte[] content) {
            if (!storeUp) {
                throw new OnboardingPorts.FileStoreUnavailableException("down");
            }
            stored.add(key);
        }

        @Override
        public byte[] read(String key) {
            throw new OnboardingPorts.FileStoreUnavailableException("down");
        }
    };
    private final OrganizationOnboardingService service = new OrganizationOnboardingService(mapper,
            code -> "370500".equals(code) ? Optional.of("东营市") : Optional.empty(),
            raw -> {
                hashed.add(raw);
                return "bcrypt:" + raw.length();
            },
            files, Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());

    private OrganizationRow organization(String type, String status) {
        return new OrganizationRow(ORG, "ORG-20261122-ABCDEF01", "黄河口教育装备（示例）", type, "370500", "ACTIVE", null, AT,
                0, status);
    }

    private void existing(String type, String qualificationStatus) {
        when(mapper.lockOrganization(ORG)).thenReturn(ORG);
        when(mapper.findOrganization(ORG)).thenReturn(organization(type, qualificationStatus));
    }

    private static QualificationRow qualification(String status, int version) {
        return new QualificationRow(ORG, "黄河口教育装备（示例）有限公司", LICENSE, null, null, status, AT,
                "SUBMITTED".equals(status) ? null : AT, "REJECTED".equals(status) ? "照片不清" : null, version);
    }

    // ---- 新建 ----

    @Test
    void createsAnOrganizationOncePerKeyAndRecordsIt() {
        UUID key = UUID.randomUUID();
        when(mapper.insertOrganization(anyString(), eq("黄河口教育装备（示例）"), eq("PROVIDER"), eq("370500"), eq(key),
                eq(AT))).thenReturn(ORG);
        when(mapper.findOrganization(ORG)).thenReturn(organization("PROVIDER", "NOT_SUBMITTED"));

        OpsOrganizationCreated created = service.create(key, "provider", " 黄河口教育装备（示例） ", "370500", OPERATOR);

        assertThat(created.created()).isTrue();
        assertThat(created.detail().organization().cityName()).isEqualTo("东营市");
        assertThat(created.detail().grantableRoles()).containsExactly(OpsGrantableRole.PROVIDER_OPERATOR,
                OpsGrantableRole.LIVE_HOST);
        verify(mapper).insertOperation(ORG, "CREATE", "新建经营机构“黄河口教育装备（示例）”，城市 东营市（370500）", OPERATOR_ID,
                "平台运营（示例）", AT);

        when(mapper.findOrganizationByCreateKey(key)).thenReturn(ORG);
        assertThat(service.create(key, "PROVIDER", "黄河口教育装备（示例）", "370500", OPERATOR).created()).isFalse();
        assertRejected(() -> service.create(key, "PROVIDER", "别的名字", "370500", OPERATOR), Error.IDEMPOTENCY_CONFLICT);
        assertRejected(() -> service.create(UUID.randomUUID(), "PROVIDER", "名", "370100", OPERATOR), Error.CITY_UNKNOWN);
        assertInvalid(() -> service.create(UUID.randomUUID(), "PLATFORM", "名", "370500", OPERATOR));
        assertInvalid(() -> service.create(UUID.randomUUID(), "PROVIDER", "长".repeat(61), "370500", OPERATOR));
        assertInvalid(() -> service.create(null, "PROVIDER", "名", "370500", OPERATOR));
    }

    // ---- 资料与审核 ----

    @Test
    void submittingQualificationPutsItBackToPendingAndWithdrawsTheReview() {
        existing("PROVIDER", "APPROVED");
        when(mapper.findQualification(ORG)).thenReturn(null);

        service.submitQualification(ORG, new OrganizationOnboardingService.QualificationDraft(
                "黄河口教育装备（示例）有限公司", "00000000000000000x", "教育装备销售", null, null), OPERATOR);

        verify(mapper).insertQualification(ORG, "黄河口教育装备（示例）有限公司", LICENSE, "教育装备销售", null,
                OPERATOR_ID, AT);
        verify(mapper).updateQualificationReviewedAt(ORG, null, AT);
        verify(mapper).insertOperation(eq(ORG), eq("QUALIFICATION_SUBMIT"), anyString(), eq(OPERATOR_ID), anyString(),
                eq(AT));

        when(mapper.findQualification(ORG)).thenReturn(qualification("APPROVED", 2));
        assertRejected(() -> service.submitQualification(ORG, new OrganizationOnboardingService.QualificationDraft(
                "主体", LICENSE, null, null, 1), OPERATOR), Error.VERSION_CONFLICT);
        assertRejected(() -> service.submitQualification(ORG, new OrganizationOnboardingService.QualificationDraft(
                "主体", LICENSE, null, null, null), OPERATOR), Error.VERSION_CONFLICT);
        when(mapper.updateQualification(ORG, "主体", LICENSE, null, null, OPERATOR_ID, 2, AT)).thenReturn(1);
        service.submitQualification(ORG, new OrganizationOnboardingService.QualificationDraft("主体", LICENSE, null,
                null, 2), OPERATOR);
        // 统一社会信用代码只认 18 位数字和大写字母（不含 I、O、Z、S、V）。
        for (String bad : List.of("12345", "0000000000000000O0", "00000000000000000Z", "0000000000000000000")) {
            assertInvalid(() -> service.submitQualification(ORG, new OrganizationOnboardingService.QualificationDraft(
                    "主体", bad, null, null, 2), OPERATOR));
        }
    }

    @Test
    void approvingNeedsAnAttachmentAndWritesTheReviewTime() {
        existing("PROVIDER", "SUBMITTED");
        when(mapper.findQualification(ORG)).thenReturn(qualification("SUBMITTED", 3));
        when(mapper.findAttachments(ORG)).thenReturn(List.of());

        assertRejected(() -> service.review(ORG, "APPROVE", null, 3, OPERATOR), Error.QUALIFICATION_INCOMPLETE);
        assertRejected(() -> service.review(ORG, "APPROVE", null, 2, OPERATOR), Error.VERSION_CONFLICT);

        when(mapper.findAttachments(ORG)).thenReturn(List.of(attachment()));
        when(mapper.reviewQualification(ORG, "APPROVED", null, OPERATOR_ID, 3, AT)).thenReturn(1);
        service.review(ORG, "approve", "营业执照与行业资质已核对", 3, OPERATOR);

        verify(mapper).updateQualificationReviewedAt(ORG, AT, AT);
        verify(mapper).insertOperation(ORG, "QUALIFICATION_APPROVE", "资质审核通过：营业执照与行业资质已核对", OPERATOR_ID,
                "平台运营（示例）", AT);
    }

    @Test
    void rejectingNeedsAReasonAndClearsTheReviewTime() {
        existing("PROVIDER", "SUBMITTED");
        when(mapper.findQualification(ORG)).thenReturn(qualification("SUBMITTED", 0));

        assertInvalid(() -> service.review(ORG, "REJECT", " ", 0, OPERATOR));
        assertInvalid(() -> service.review(ORG, "PASS", null, 0, OPERATOR));
        assertInvalid(() -> service.review(ORG, "REJECT", "原因", null, OPERATOR));

        when(mapper.reviewQualification(ORG, "REJECTED", "营业执照照片不清", OPERATOR_ID, 0, AT)).thenReturn(1);
        service.review(ORG, "REJECT", "营业执照照片不清", 0, OPERATOR);
        verify(mapper).updateQualificationReviewedAt(ORG, null, AT);
        verify(mapper).insertOperation(ORG, "QUALIFICATION_REJECT", "资质审核驳回：营业执照照片不清", OPERATOR_ID,
                "平台运营（示例）", AT);

        when(mapper.findQualification(ORG)).thenReturn(qualification("REJECTED", 1));
        assertRejected(() -> service.review(ORG, "APPROVE", null, 1, OPERATOR), Error.QUALIFICATION_NOT_PENDING);
        when(mapper.findQualification(ORG)).thenReturn(null);
        assertRejected(() -> service.review(ORG, "APPROVE", null, 0, OPERATOR), Error.QUALIFICATION_NOT_PENDING);
    }

    // ---- 附件 ----

    @Test
    void attachmentsAreSniffedLimitedAndReopenTheReview() {
        existing("PROVIDER", "APPROVED");
        when(mapper.findAttachments(ORG)).thenReturn(List.of());
        when(mapper.reopenQualification(ORG, OPERATOR_ID, AT)).thenReturn(1);
        byte[] pdf = "%PDF-1.7 示例".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        service.addAttachment(ORG, "C:\\\\资料\\\\营业执照.pdf", pdf, OPERATOR);

        assertThat(stored).singleElement().asString().startsWith("organizations/" + ORG + "/qualification/");
        verify(mapper).insertAttachment(any(), eq(ORG), eq("营业执照.pdf"), eq("application/pdf"), eq((long) pdf.length),
                anyString(), eq(stored.getFirst()), eq(OPERATOR_ID), eq(AT));
        // 已通过的资料因为附件变了回到待审核，撤下“资质已审核”。
        verify(mapper).updateQualificationReviewedAt(ORG, null, AT);

        assertThat(OrganizationOnboardingService.sniff(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}))
                .isEqualTo("image/jpeg");
        assertThat(OrganizationOnboardingService.sniff(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}))
                .isEqualTo("image/png");
        assertInvalid(() -> service.addAttachment(ORG, "a.exe", new byte[] {'M', 'Z', 0, 0}, OPERATOR));
        assertInvalid(() -> service.addAttachment(ORG, "a.pdf", new byte[0], OPERATOR));
        assertInvalid(() -> service.addAttachment(ORG, "  ", pdf, OPERATOR));

        storeUp = false;
        assertRejected(() -> service.addAttachment(ORG, "b.pdf", pdf, OPERATOR), Error.FILE_STORE_UNAVAILABLE);
        storeUp = true;
        List<AttachmentRow> full = new ArrayList<>();
        for (int i = 0; i < OrganizationOnboardingService.MAX_ATTACHMENTS; i++) {
            full.add(attachment());
        }
        when(mapper.findAttachments(ORG)).thenReturn(full);
        assertRejected(() -> service.addAttachment(ORG, "c.pdf", pdf, OPERATOR), Error.ATTACHMENT_LIMIT);
        when(mapper.findAttachment(eq(ORG), any())).thenReturn(null);
        assertRejected(() -> service.removeAttachment(ORG, UUID.randomUUID(), OPERATOR), Error.NOT_FOUND);
    }

    // ---- 账号与角色 ----

    @Test
    void createsAnAccountWithAOneTimePasswordAndOnlyGrantableRoles() {
        existing("PROVIDER", "APPROVED");
        when(mapper.insertAccount(eq("hhk-ops"), eq("黄河口操作员"), anyString(), eq(ORG), eq(AT))).thenReturn(ACCOUNT);
        when(mapper.findAccounts(ORG)).thenReturn(List.of(new AccountRow(ACCOUNT, "hhk-ops", "黄河口操作员", "ACTIVE", AT)));
        when(mapper.findAccountRoles(eq(ORG), eq(List.of(ACCOUNT)), any())).thenReturn(List.of(
                new AccountRoleRow(ACCOUNT, "LIVE_HOST"), new AccountRoleRow(ACCOUNT, "PROVIDER_OPERATOR")));

        OpsAccountCreatedView created = service.createAccount(ORG, "hhk-ops", "黄河口操作员",
                List.of("PROVIDER_OPERATOR", "live_host"), OPERATOR);

        assertThat(created.initialPassword()).hasSize(12).matches("[A-HJ-NP-Za-km-np-z2-9]{12}")
                .containsPattern("[A-Z]").containsPattern("[a-z]").containsPattern("[0-9]");
        assertThat(hashed).containsExactly(created.initialPassword());
        assertThat(created.account().roles()).containsExactly("LIVE_HOST", "PROVIDER_OPERATOR");
        verify(mapper).insertMember(ORG, ACCOUNT, AT);
        verify(mapper).grantRole(ACCOUNT, "PROVIDER_OPERATOR", ORG, AT);
        verify(mapper).grantRole(ACCOUNT, "LIVE_HOST", ORG, AT);
        verify(mapper).insertOperation(eq(ORG), eq("ACCOUNT_CREATE"), eq("新建账号 hhk-ops（黄河口操作员），角色：服务商操作员、直播主办"),
                eq(OPERATOR_ID), anyString(), eq(AT));

        // 经营机构不能授客户角色；平台角色谁都不能授；登录名格式和占用。
        assertRejected(() -> service.createAccount(ORG, "hhk-two", "二号", List.of("CUSTOMER"), OPERATOR),
                Error.ROLE_NOT_GRANTABLE);
        assertRejected(() -> service.createAccount(ORG, "hhk-two", "二号", List.of("PLATFORM_OPERATOR"), OPERATOR),
                Error.ROLE_NOT_GRANTABLE);
        assertInvalid(() -> service.createAccount(ORG, "Ab", "二号", List.of("PROVIDER_OPERATOR"), OPERATOR));
        assertInvalid(() -> service.createAccount(ORG, "hhk-two", "二号", List.of(), OPERATOR));
        when(mapper.insertAccount(eq("hhk-taken"), anyString(), anyString(), eq(ORG), eq(AT))).thenReturn(null);
        assertRejected(() -> service.createAccount(ORG, "hhk-taken", "三号", List.of("PROVIDER_OPERATOR"), OPERATOR),
                Error.LOGIN_NAME_TAKEN);
    }

    @Test
    void customerAccountsGetTheGlobalCustomerRole() {
        existing("CUSTOMER", "NOT_SUBMITTED");
        when(mapper.insertAccount(eq("corp-buyer"), anyString(), anyString(), eq(ORG), eq(AT))).thenReturn(ACCOUNT);
        when(mapper.findAccounts(ORG)).thenReturn(List.of(new AccountRow(ACCOUNT, "corp-buyer", "采购", "ACTIVE", AT)));

        service.createAccount(ORG, "corp-buyer", "采购", List.of("CUSTOMER"), OPERATOR);

        verify(mapper).grantRole(eq(ACCOUNT), eq("CUSTOMER"), isNull(), eq(AT));
    }

    @Test
    void replacingRolesChangesOnlyTheDifferenceAndInvalidatesOldTokens() {
        existing("PROVIDER", "APPROVED");
        when(mapper.lockAccount(ORG, ACCOUNT)).thenReturn(new AccountRow(ACCOUNT, "hhk-ops", "黄河口操作员", "ACTIVE", AT));
        when(mapper.findAccountRoles(eq(ORG), eq(List.of(ACCOUNT)), any()))
                .thenReturn(List.of(new AccountRoleRow(ACCOUNT, "PROVIDER_OPERATOR")));

        service.replaceRoles(ORG, ACCOUNT, List.of("LIVE_HOST"), OPERATOR);

        verify(mapper).grantRole(ACCOUNT, "LIVE_HOST", ORG, AT);
        verify(mapper).revokeRole(ACCOUNT, "PROVIDER_OPERATOR", ORG, AT);
        verify(mapper).bumpTokenVersion(ACCOUNT, AT);
        verify(mapper).insertOperation(ORG, "ROLES_CHANGE", "账号 hhk-ops：授予 直播主办，收回 服务商操作员（需重新登录）",
                OPERATOR_ID, "平台运营（示例）", AT);
    }

    @Test
    void replacingWithTheSameRolesChangesNothing() {
        existing("PROVIDER", "APPROVED");
        when(mapper.lockAccount(ORG, ACCOUNT)).thenReturn(new AccountRow(ACCOUNT, "hhk-ops", "黄河口操作员", "ACTIVE", AT));
        when(mapper.findAccountRoles(eq(ORG), eq(List.of(ACCOUNT)), any()))
                .thenReturn(List.of(new AccountRoleRow(ACCOUNT, "PROVIDER_OPERATOR")));

        service.replaceRoles(ORG, ACCOUNT, List.of("PROVIDER_OPERATOR"), OPERATOR);

        verify(mapper, never()).bumpTokenVersion(any(), any());
        verify(mapper, never()).insertOperation(any(), anyString(), anyString(), any(), anyString(), any());
        when(mapper.lockAccount(ORG, ACCOUNT)).thenReturn(null);
        assertRejected(() -> service.replaceRoles(ORG, ACCOUNT, List.of(), OPERATOR), Error.NOT_FOUND);
    }

    @Test
    void platformOrUnknownOrganizationsAreNotFound() {
        when(mapper.lockOrganization(ORG)).thenReturn(null);
        when(mapper.findOrganization(ORG)).thenReturn(null);

        assertRejected(() -> service.organization(ORG), Error.NOT_FOUND);
        assertRejected(() -> service.review(ORG, "APPROVE", null, 0, OPERATOR), Error.NOT_FOUND);
        assertInvalid(() -> service.organizations(null, null, "WAITING", 1, 20));
        assertInvalid(() -> service.organizations(null, "PLATFORM", null, 1, 20));
        assertInvalid(() -> service.organizations(null, null, null, 1, 51));
        when(mapper.countOrganizations(null, null, "SUBMITTED")).thenReturn(0L);
        assertThat(service.organizations(" % ", null, "submitted", 1, 20).items()).isEmpty();
        verify(mapper).findOrganizations(isNull(), isNull(), eq("SUBMITTED"), eq(20), anyInt());
    }

    private static AttachmentRow attachment() {
        return new AttachmentRow(UUID.randomUUID(), ORG, "营业执照.pdf", "application/pdf", 10, "0".repeat(64),
                "organizations/" + ORG + "/qualification/" + UUID.randomUUID(), AT);
    }

    private static void assertRejected(ThrowingCallable call, Error error) {
        assertThatThrownBy(call).isInstanceOfSatisfying(OnboardingException.class,
                exception -> assertThat(exception.error()).isEqualTo(error));
    }

    private static void assertInvalid(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(IllegalArgumentException.class);
    }
}
