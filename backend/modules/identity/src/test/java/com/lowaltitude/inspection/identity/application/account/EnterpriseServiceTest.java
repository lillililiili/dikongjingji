package com.lowaltitude.inspection.identity.application.account;

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

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lowaltitude.inspection.identity.application.account.AccountException.Error;
import com.lowaltitude.inspection.identity.domain.CurrentIdentity;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper.ActingRow;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper.EnterpriseRow;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper.InvitationRow;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper.UserRow;
import org.junit.jupiter.api.Test;

/** 企业组织：只有成员看得到、只有管理员能改成员；至少一名管理员；邀请只给被邀请的号码或拿着邀请码的人。 */
class EnterpriseServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-23T02:00:00Z");
    private static final OffsetDateTime AT = NOW.atOffset(ZoneOffset.UTC);
    private static final UUID ME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-1111-1111-1111-111111111111");
    private static final UUID ENT = UUID.fromString("e5000000-0000-4000-8000-000000000001");
    private static final UUID KEY = UUID.fromString("e5000000-0000-4000-8000-0000000000c1");

    private final AccountMapper mapper = mock(AccountMapper.class);
    private final EnterpriseService service = new EnterpriseService(mapper,
            codes -> Map.of("370500", "东营市"), Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());

    private static EnterpriseRow row(String role, long members) {
        return new EnterpriseRow(ENT, "东营港务物流", "91370500EXAMPLE26X", "370500", null, role, members, AT);
    }

    private static UserRow user(UUID id, String mobile) {
        return new UserRow(id, "ACTIVE", "王女士", mobile, null, UUID.randomUUID(), "PHONE");
    }

    private static InvitationRow invitation(String kind, String phone, String status, OffsetDateTime expiresAt) {
        return new InvitationRow(UUID.randomUUID(), ENT, "东营港务物流", null, kind, phone, "MEMBER", OTHER, "李**",
                status, AT.minusDays(1), expiresAt);
    }

    @Test
    void createValidatesNameAndCreditCodeFormat() {
        for (String[] bad : new String[][] {{"x", "91370500EXAMPLE26X"}, {"东营港务", "91370500EXAMPLE26I"},
                {"东营港务", "91370500EXAMPLE26"}, {"东营\u0007港务", "91370500EXAMPLE26X"}}) {
            assertThatThrownBy(() -> service.create(ME, KEY, bad[0], bad[1], null))
                    .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.INVALID);
        }
        assertThatThrownBy(() -> service.create(ME, KEY, "东营港务", "91370500EXAMPLE26X", "999999"))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.INVALID);
        verify(mapper, never()).insertEnterpriseOrganization(anyString(), anyString(), any(), any());
    }

    @Test
    void createMakesTheCreatorAdminAndMasksTheCode() {
        when(mapper.insertEnterpriseOrganization(anyString(), eq("东营港务物流"), eq("370500"), eq(AT))).thenReturn(ENT);
        when(mapper.findMyEnterprise(ME, ENT)).thenReturn(row("ADMIN", 1));

        AccountViews.EnterpriseView view = service.create(ME, KEY, " 东营港务物流 ", "91370500example26x", "370500");

        verify(mapper).insertEnterprise(ENT, "91370500EXAMPLE26X", ME, KEY, AT);
        verify(mapper).insertEnterpriseMember(ENT, ME, "ADMIN", AT);
        assertThat(view.maskedCreditCode()).isEqualTo("9137************6X");
        assertThat(view.cityName()).isEqualTo("东营市");
    }

    @Test
    void aCreditCodeInUseOrTooManyEnterprisesIsRefused() {
        when(mapper.isCreditCodeTaken("91370500EXAMPLE26X")).thenReturn(true);
        assertThatThrownBy(() -> service.create(ME, KEY, "东营港务", "91370500EXAMPLE26X", null))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.ENTERPRISE_CREDIT_CODE_TAKEN);
        when(mapper.countMyMemberships(ME)).thenReturn(5);
        assertThatThrownBy(() -> service.create(ME, KEY, "东营港务", "91370500EXAMPLE26X", null))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.ENTERPRISE_LIMIT);
    }

    @Test
    void replayingTheKeyWithOtherContentIsAConflict() {
        when(mapper.findEnterpriseByCreateKey(ME, KEY)).thenReturn(ENT);
        when(mapper.findEnterpriseCreateFacts(ENT)).thenReturn(
                new AccountMapper.EnterpriseCreateRow("91370500EXAMPLE26X", "东营港务物流"));

        assertThatThrownBy(() -> service.create(ME, KEY, "别的名字", "91370500EXAMPLE26X", null))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void outsidersSeeNothing() {
        assertThatThrownBy(() -> service.read(ME, ENT))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.NOT_FOUND);
        assertThatThrownBy(() -> service.members(ME, ENT))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.NOT_FOUND);
        when(mapper.lockEnterprise(ENT)).thenReturn(ENT);
        assertThatThrownBy(() -> service.invite(ME, ENT, "LINK", null))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.NOT_FOUND);
    }

    @Test
    void onlyAdminsChangeRolesOrRemoveMembers() {
        when(mapper.lockEnterprise(ENT)).thenReturn(ENT);
        when(mapper.findMyEnterprise(ME, ENT)).thenReturn(row("MEMBER", 3));
        when(mapper.findMemberRole(ENT, ME)).thenReturn("MEMBER");

        assertThatThrownBy(() -> service.remove(ME, ENT, OTHER))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.ENTERPRISE_ADMIN_REQUIRED);
        assertThatThrownBy(() -> service.changeRole(ME, ENT, OTHER, "ADMIN"))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.ENTERPRISE_ADMIN_REQUIRED);
        verify(mapper, never()).endMembership(any(), any(), anyString(), any(), any());
    }

    @Test
    void theLastAdminCannotStepDownOrLeaveOthersBehind() {
        when(mapper.lockEnterprise(ENT)).thenReturn(ENT);
        when(mapper.findMyEnterprise(ME, ENT)).thenReturn(row("ADMIN", 2));
        when(mapper.findMemberRole(ENT, ME)).thenReturn("ADMIN");
        when(mapper.countMembers(ENT, "ADMIN")).thenReturn(1);
        when(mapper.countMembers(ENT, null)).thenReturn(2);

        assertThatThrownBy(() -> service.changeRole(ME, ENT, ME, "MEMBER"))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.ENTERPRISE_LAST_ADMIN);
        assertThatThrownBy(() -> service.leave(ME, ENT))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.ENTERPRISE_LAST_ADMIN);
    }

    @Test
    void theOnlyMemberLeavingDissolvesTheEnterprise() {
        when(mapper.lockEnterprise(ENT)).thenReturn(ENT);
        when(mapper.findMyEnterprise(ME, ENT)).thenReturn(row("ADMIN", 1));
        when(mapper.countMembers(ENT, null)).thenReturn(1);
        when(mapper.countMembers(ENT, "ADMIN")).thenReturn(1);

        service.leave(ME, ENT);

        verify(mapper).endMembership(ENT, ME, "LEFT", ME, AT);
        verify(mapper).dissolveEnterprise(ENT, AT);
        verify(mapper).disableOrganization(ENT, AT);
        verify(mapper).revokeAllInvitations(ENT);
        verify(mapper).clearActingOrganization(ENT, null, AT);
    }

    @Test
    void aRemovedMemberFallsBackToPersonal() {
        when(mapper.lockEnterprise(ENT)).thenReturn(ENT);
        when(mapper.findMyEnterprise(ME, ENT)).thenReturn(row("ADMIN", 2));
        when(mapper.findMemberRole(ENT, ME)).thenReturn("ADMIN");
        when(mapper.endMembership(ENT, OTHER, "REMOVED", ME, AT)).thenReturn(1);

        service.remove(ME, ENT, OTHER);

        verify(mapper).clearActingOrganization(ENT, OTHER, AT);
    }

    @Test
    void aPhoneInvitationIsOnlyForThatNumber() {
        InvitationRow invite = invitation("PHONE", "13900002222", "PENDING", AT.plusDays(3));
        when(mapper.lockUser(ME)).thenReturn(user(ME, "13900003333"));
        when(mapper.lockInvitation(invite.id())).thenReturn(invite);

        assertThatThrownBy(() -> service.accept(ME, invite.id(), null))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.NOT_FOUND);
        verify(mapper, never()).insertEnterpriseMember(any(), any(), anyString(), any());
    }

    @Test
    void aLinkInvitationNeedsItsCode() {
        InvitationRow invite = invitation("LINK", null, "PENDING", AT.plusDays(3));
        when(mapper.lockUser(ME)).thenReturn(user(ME, null));
        when(mapper.lockInvitation(invite.id())).thenReturn(invite);

        assertThatThrownBy(() -> service.accept(ME, invite.id(), "wrong-code-wrong-code"))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.NOT_FOUND);
        assertThatThrownBy(() -> service.accept(ME, invite.id(), null))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.NOT_FOUND);
    }

    @Test
    void expiredOrAnsweredInvitationsCannotBeAccepted() {
        InvitationRow expired = invitation("PHONE", "13900002222", "PENDING", AT.minusSeconds(1));
        when(mapper.lockUser(ME)).thenReturn(user(ME, "13900002222"));
        when(mapper.lockInvitation(expired.id())).thenReturn(expired);
        assertThatThrownBy(() -> service.accept(ME, expired.id(), null))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.INVITATION_EXPIRED);

        InvitationRow declined = invitation("PHONE", "13900002222", "DECLINED", AT.plusDays(1));
        when(mapper.lockInvitation(declined.id())).thenReturn(declined);
        assertThatThrownBy(() -> service.accept(ME, declined.id(), null))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.INVITATION_NOT_PENDING);
    }

    @Test
    void acceptingJoinsAsAMember() {
        InvitationRow invite = invitation("PHONE", "13900002222", "PENDING", AT.plusDays(3));
        when(mapper.lockUser(ME)).thenReturn(user(ME, "13900002222"));
        when(mapper.lockInvitation(invite.id())).thenReturn(invite);
        when(mapper.lockEnterprise(ENT)).thenReturn(ENT);
        when(mapper.findMyEnterprise(ME, ENT)).thenReturn(row("MEMBER", 2));

        service.accept(ME, invite.id(), null);

        verify(mapper).respondInvitation(invite.id(), "ACCEPTED", ME, AT);
        verify(mapper).insertEnterpriseMember(ENT, ME, "MEMBER", AT);
    }

    @Test
    void invitingAMemberAgainIsRefusedAndAPendingOneIsReplaced() {
        when(mapper.lockEnterprise(ENT)).thenReturn(ENT);
        when(mapper.findMyEnterprise(ME, ENT)).thenReturn(row("MEMBER", 2));
        when(mapper.isPhoneMember(ENT, "13900002222")).thenReturn(true);
        assertThatThrownBy(() -> service.invite(ME, ENT, "PHONE", "13900002222"))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.ENTERPRISE_ALREADY_MEMBER);

        UUID old = UUID.randomUUID();
        UUID created = UUID.randomUUID();
        when(mapper.findPendingPhoneInvitation(ENT, "13900004444")).thenReturn(old);
        when(mapper.insertInvitation(ENT, "PHONE", "13900004444", null, ME, AT, AT.plusDays(7))).thenReturn(created);
        when(mapper.findInvitation(created)).thenReturn(invitation("PHONE", "13900004444", "PENDING", AT.plusDays(7)));
        AccountViews.CreatedInvitationView view = service.invite(ME, ENT, "PHONE", "13900004444");
        verify(mapper).revokeInvitation(old);
        assertThat(view.inviteCode()).isNull();
        assertThat(view.invitation().maskedPhone()).isEqualTo("139****4444");
    }

    @Test
    void aLinkInvitationReturnsItsCodeOnceAndStoresOnlyTheHash() {
        when(mapper.lockEnterprise(ENT)).thenReturn(ENT);
        when(mapper.findMyEnterprise(ME, ENT)).thenReturn(row("MEMBER", 2));
        UUID created = UUID.randomUUID();
        when(mapper.insertInvitation(eq(ENT), eq("LINK"), isNull(), anyString(), eq(ME), eq(AT), eq(AT.plusDays(7))))
                .thenReturn(created);
        when(mapper.findInvitation(created)).thenReturn(invitation("LINK", null, "PENDING", AT.plusDays(7)));

        AccountViews.CreatedInvitationView view = service.invite(ME, ENT, "LINK", null);

        assertThat(view.inviteCode()).hasSizeGreaterThanOrEqualTo(16);
        verify(mapper).insertInvitation(ENT, "LINK", null, EnterpriseService.codeHash(view.inviteCode()), ME, AT,
                AT.plusDays(7));
    }

    @Test
    void switchingNeedsMembershipAndThePayerFollowsTheIdentity() {
        assertThatThrownBy(() -> service.switchIdentity(ME, ENT))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.NOT_FOUND);
        verify(mapper, never()).updateActingOrganization(any(), any(), any());

        when(mapper.findMyEnterprise(ME, ENT)).thenReturn(row("MEMBER", 2));
        when(mapper.findActingEnterprise(ME)).thenReturn(new ActingRow(ENT, "东营港务物流", "MEMBER"));
        CurrentIdentity identity = service.switchIdentity(ME, ENT);
        verify(mapper).updateActingOrganization(ME, ENT, AT);
        assertThat(identity.isEnterprise()).isTrue();
        assertThat(service.payer(ME, false)).isEqualTo(new AccountViews.Payer("ENTERPRISE", ENT, "东营港务物流", false));

        when(mapper.findActingEnterprise(ME)).thenReturn(null);
        assertThat(service.payer(ME, true)).isEqualTo(new AccountViews.Payer("PERSONAL", null, null, true));
    }

    @Test
    void closureLeavesEveryEnterpriseAndHandsOverTheAdminRole() {
        when(mapper.findMyEnterprises(ME)).thenReturn(List.of(row("ADMIN", 2)));
        when(mapper.countMembers(ENT, null)).thenReturn(2);
        when(mapper.countMembers(ENT, "ADMIN")).thenReturn(1);
        when(mapper.findMembers(ENT)).thenReturn(List.of(
                new AccountMapper.MemberRow(OTHER, "张工", "13900005555", "MEMBER", AT.minusDays(2))));

        service.leaveAllForClosure(ME);

        verify(mapper).endMembership(ENT, ME, "LEFT", ME, AT);
        verify(mapper).updateMemberRole(ENT, OTHER, "ADMIN", AT);
        verify(mapper, never()).dissolveEnterprise(any(), any());
    }
}
