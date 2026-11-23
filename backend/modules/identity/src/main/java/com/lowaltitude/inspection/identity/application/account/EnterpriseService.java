package com.lowaltitude.inspection.identity.application.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.identity.application.account.AccountException.Error;
import com.lowaltitude.inspection.identity.application.account.AccountViews.CreatedInvitationView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.CurrentIdentityView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.EnterpriseListView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.EnterpriseMemberView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.EnterpriseMembersView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.EnterpriseView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.Payer;
import com.lowaltitude.inspection.identity.application.account.AccountViews.PendingInvitationView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.ReceivedInvitationView;
import com.lowaltitude.inspection.identity.domain.CurrentIdentity;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper.ActingRow;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper.EnterpriseCreateRow;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper.EnterpriseRow;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper.InvitationRow;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper.MemberRow;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper.UserRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/**
 * 企业组织与成员（S5-7，docs/redesign/08-后续开发计划.md §5“企业组织”）。
 *
 * <ul>
 *   <li>客户创建企业（名称、统一社会信用代码格式校验，同一代码只能一家），创建者是管理员；同一个幂等键只建一家。</li>
 *   <li>角色：ADMIN 管理员（调整角色、移除成员）、MEMBER 成员；两者都能邀请，加入后一律是成员。至少保留一名管理员。</li>
 *   <li>邀请：手机号（对方用这个号码登录后确认）或链接（邀请码只返回一次，用一次失效），7 天有效，可再发一次（延期）。</li>
 *   <li>退出、被移除：成员关系只改状态；当前身份是这家企业的回到个人；企业只剩本人时退出即解散。</li>
 *   <li>切换身份：个人或本人所在的企业；下单时交易模块经端口取付款主体（{@link #payer}），不做企业结算。</li>
 * </ul>
 * 不在其中的企业、不是发给本人的邀请都按不存在回答。只给客户角色用，角色由控制器检查。
 */
public class EnterpriseService {

    public static final int MAX_ENTERPRISES_PER_ACCOUNT = 5;
    public static final int MAX_MEMBERS = 50;
    public static final int MIN_NAME = 2;
    public static final int MAX_NAME = 40;
    public static final Duration INVITATION_TTL = Duration.ofDays(7);
    public static final String ADMIN = "ADMIN";
    public static final String MEMBER = "MEMBER";
    static final Pattern CREDIT_CODE = Pattern.compile("^[0-9A-HJ-NPQRTUWXY]{18}$");
    private static final Pattern CITY_CODE = Pattern.compile("^[0-9]{6}$");
    private static final String ORG_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final AccountMapper mapper;
    private final AccountPorts.CityNames cities;
    private final Clock clock;
    private final SecureRandom random;

    public EnterpriseService(AccountMapper mapper, AccountPorts.CityNames cities, Clock clock, SecureRandom random) {
        this.mapper = mapper;
        this.cities = cities;
        this.clock = clock;
        this.random = random;
    }

    // ---- 我的企业 ----

    @Transactional(readOnly = true)
    public EnterpriseListView listMine(UUID userId) {
        List<EnterpriseView> items = mapper.findMyEnterprises(userId).stream().map(this::view).toList();
        UserRow user = mapper.findUser(userId);
        List<ReceivedInvitationView> invitations = List.of();
        if (user != null && user.mobile() != null) {
            invitations = mapper.findInvitationsForPhone(user.mobile(), now()).stream()
                    .filter(row -> mapper.findMemberRole(row.organizationId(), userId) == null)
                    .map(row -> received(row, user.mobile()))
                    .toList();
        }
        return new EnterpriseListView(identityView(currentIdentity(userId)), items, invitations);
    }

    @Transactional(readOnly = true)
    public EnterpriseView read(UUID userId, UUID enterpriseId) {
        return view(requireMine(userId, enterpriseId));
    }

    @Transactional
    public EnterpriseView create(UUID userId, UUID createKey, String rawName, String rawCreditCode, String cityCode) {
        if (createKey == null) {
            throw new AccountException(Error.INVALID);
        }
        String name = rawName == null ? "" : rawName.strip();
        String creditCode = rawCreditCode == null ? "" : rawCreditCode.strip().toUpperCase(java.util.Locale.ROOT);
        if (name.length() < MIN_NAME || name.length() > MAX_NAME || name.chars().anyMatch(Character::isISOControl)
                || !CREDIT_CODE.matcher(creditCode).matches()
                || (cityCode != null && !CITY_CODE.matcher(cityCode).matches())) {
            throw new AccountException(Error.INVALID);
        }
        if (cityCode != null && cities.names(List.of(cityCode)).get(cityCode) == null) {
            throw new AccountException(Error.INVALID);
        }
        mapper.lockUser(userId);
        UUID replayed = mapper.findEnterpriseByCreateKey(userId, createKey);
        if (replayed != null) {
            EnterpriseCreateRow facts = mapper.findEnterpriseCreateFacts(replayed);
            if (facts == null || !facts.creditCode().equals(creditCode) || !facts.organizationName().equals(name)) {
                throw new AccountException(Error.IDEMPOTENCY_CONFLICT);
            }
            EnterpriseRow row = mapper.findMyEnterprise(userId, replayed);
            if (row == null) {
                throw new AccountException(Error.IDEMPOTENCY_CONFLICT);
            }
            return view(row);
        }
        if (mapper.countMyMemberships(userId) >= MAX_ENTERPRISES_PER_ACCOUNT) {
            throw new AccountException(Error.ENTERPRISE_LIMIT);
        }
        if (mapper.isCreditCodeTaken(creditCode)) {
            throw new AccountException(Error.ENTERPRISE_CREDIT_CODE_TAKEN);
        }
        OffsetDateTime now = now();
        UUID organizationId = mapper.insertEnterpriseOrganization("ENT-" + randomCode(12), name, cityCode, now);
        try {
            mapper.insertEnterprise(organizationId, creditCode, userId, createKey, now);
        } catch (DuplicateKeyException sameCodeMeanwhile) {
            throw new AccountException(Error.ENTERPRISE_CREDIT_CODE_TAKEN);
        }
        mapper.insertEnterpriseMember(organizationId, userId, ADMIN, now);
        audit(userId, "ENTERPRISE_CREATE", "ENTERPRISE", organizationId, organizationId, now);
        return view(mapper.findMyEnterprise(userId, organizationId));
    }

    // ---- 成员 ----

    @Transactional(readOnly = true)
    public EnterpriseMembersView members(UUID userId, UUID enterpriseId) {
        EnterpriseRow mine = requireMine(userId, enterpriseId);
        List<EnterpriseMemberView> members = mapper.findMembers(enterpriseId).stream()
                .map(row -> memberView(row, userId))
                .toList();
        List<PendingInvitationView> invitations = mapper.findPendingInvitations(enterpriseId, now()).stream()
                .map(EnterpriseService::pendingView)
                .toList();
        return new EnterpriseMembersView(enterpriseId, mine.memberRole(), members, invitations);
    }

    @Transactional
    public EnterpriseMemberView changeRole(UUID userId, UUID enterpriseId, UUID memberId, String role) {
        if (!ADMIN.equals(role) && !MEMBER.equals(role)) {
            throw new AccountException(Error.INVALID);
        }
        lockMine(userId, enterpriseId);
        requireAdmin(userId, enterpriseId);
        String current = mapper.findMemberRole(enterpriseId, memberId);
        if (current == null) {
            throw new AccountException(Error.NOT_FOUND);
        }
        if (ADMIN.equals(current) && MEMBER.equals(role) && mapper.countMembers(enterpriseId, ADMIN) <= 1) {
            throw new AccountException(Error.ENTERPRISE_LAST_ADMIN);
        }
        OffsetDateTime now = now();
        if (!current.equals(role)) {
            mapper.updateMemberRole(enterpriseId, memberId, role, now);
            audit(userId, "ENTERPRISE_MEMBER_ROLE", "USER", memberId, enterpriseId, now);
        }
        return mapper.findMembers(enterpriseId).stream()
                .filter(row -> row.userId().equals(memberId))
                .findFirst()
                .map(row -> memberView(row, userId))
                .orElseThrow(() -> new AccountException(Error.NOT_FOUND));
    }

    @Transactional
    public void remove(UUID userId, UUID enterpriseId, UUID memberId) {
        lockMine(userId, enterpriseId);
        requireAdmin(userId, enterpriseId);
        if (memberId.equals(userId)) {
            // 移除自己请用“退出企业”，那里会检查最后一名管理员。
            throw new AccountException(Error.INVALID);
        }
        OffsetDateTime now = now();
        if (mapper.endMembership(enterpriseId, memberId, "REMOVED", userId, now) == 0) {
            throw new AccountException(Error.NOT_FOUND);
        }
        mapper.clearActingOrganization(enterpriseId, memberId, now);
        audit(userId, "ENTERPRISE_MEMBER_REMOVE", "USER", memberId, enterpriseId, now);
    }

    @Transactional
    public void leave(UUID userId, UUID enterpriseId) {
        EnterpriseRow mine = lockMine(userId, enterpriseId);
        OffsetDateTime now = now();
        int members = mapper.countMembers(enterpriseId, null);
        if (ADMIN.equals(mine.memberRole()) && members > 1 && mapper.countMembers(enterpriseId, ADMIN) <= 1) {
            throw new AccountException(Error.ENTERPRISE_LAST_ADMIN);
        }
        mapper.endMembership(enterpriseId, userId, "LEFT", userId, now);
        mapper.clearActingOrganization(enterpriseId, userId, now);
        if (members <= 1) {
            dissolve(enterpriseId, now);
        }
        audit(userId, members <= 1 ? "ENTERPRISE_DISSOLVE" : "ENTERPRISE_LEAVE", "ENTERPRISE", enterpriseId,
                enterpriseId, now);
    }

    /**
     * 注销到期时调用（在调用方的事务里）：退出全部企业；是唯一成员的企业随之解散。
     * 注销条件已保证本人不是有其他成员的企业的唯一管理员；万一是，留下的成员里最早加入的一位升为管理员。
     */
    public void leaveAllForClosure(UUID userId) {
        OffsetDateTime now = now();
        for (EnterpriseRow row : mapper.findMyEnterprises(userId)) {
            UUID enterpriseId = row.organizationId();
            mapper.lockEnterprise(enterpriseId);
            int members = mapper.countMembers(enterpriseId, null);
            boolean lastAdmin = ADMIN.equals(row.memberRole()) && mapper.countMembers(enterpriseId, ADMIN) <= 1;
            mapper.endMembership(enterpriseId, userId, "LEFT", userId, now);
            if (members <= 1) {
                dissolve(enterpriseId, now);
            } else if (lastAdmin) {
                mapper.findMembers(enterpriseId).stream()
                        .min(java.util.Comparator.comparing(MemberRow::joinedAt))
                        .ifPresent(next -> mapper.updateMemberRole(enterpriseId, next.userId(), ADMIN, now));
            }
        }
    }

    /** 是不是某家还有其他成员的企业的唯一管理员（注销条件用）；返回这些企业的名称。 */
    @Transactional(readOnly = true)
    public List<String> soleAdminOf(UUID userId) {
        List<String> names = new ArrayList<>();
        for (EnterpriseRow row : mapper.findMyEnterprises(userId)) {
            if (ADMIN.equals(row.memberRole()) && row.memberCount() > 1
                    && mapper.countMembers(row.organizationId(), ADMIN) <= 1) {
                names.add(row.organizationName());
            }
        }
        return names;
    }

    // ---- 邀请（邀请方） ----

    @Transactional
    public CreatedInvitationView invite(UUID userId, UUID enterpriseId, String kind, String phone) {
        lockMine(userId, enterpriseId);
        OffsetDateTime now = now();
        if (mapper.countMembers(enterpriseId, null) >= MAX_MEMBERS) {
            throw new AccountException(Error.ENTERPRISE_MEMBER_LIMIT);
        }
        UUID invitationId;
        String inviteCode = null;
        if ("PHONE".equals(kind)) {
            PhoneNumbers.require(phone);
            if (mapper.isPhoneMember(enterpriseId, phone)) {
                throw new AccountException(Error.ENTERPRISE_ALREADY_MEMBER);
            }
            UUID existing = mapper.findPendingPhoneInvitation(enterpriseId, phone);
            if (existing != null) {
                // 已有一条待确认的（可能已过期）：撤销它，发一条新的，避免两条同时有效。
                mapper.revokeInvitation(existing);
            }
            invitationId = mapper.insertInvitation(enterpriseId, "PHONE", phone, null, userId, now,
                    now.plus(INVITATION_TTL));
        } else if ("LINK".equals(kind)) {
            byte[] bytes = new byte[24];
            random.nextBytes(bytes);
            inviteCode = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            invitationId = mapper.insertInvitation(enterpriseId, "LINK", null, codeHash(inviteCode), userId, now,
                    now.plus(INVITATION_TTL));
        } else {
            throw new AccountException(Error.INVALID);
        }
        audit(userId, "ENTERPRISE_INVITE", "INVITATION", invitationId, enterpriseId, now);
        return new CreatedInvitationView(pendingView(mapper.findInvitation(invitationId)), inviteCode);
    }

    @Transactional
    public void revokeInvitation(UUID userId, UUID enterpriseId, UUID invitationId) {
        lockMine(userId, enterpriseId);
        InvitationRow row = mapper.lockInvitation(invitationId);
        if (row == null || !row.organizationId().equals(enterpriseId)) {
            throw new AccountException(Error.NOT_FOUND);
        }
        if (!"PENDING".equals(row.status())) {
            throw new AccountException(Error.INVITATION_NOT_PENDING);
        }
        mapper.revokeInvitation(invitationId);
        audit(userId, "ENTERPRISE_INVITE_REVOKE", "INVITATION", invitationId, enterpriseId, now());
    }

    /** 再发一次：只对手机号邀请；有效期从现在起再算 7 天（已过期但没撤销的也可以）。 */
    @Transactional
    public PendingInvitationView renewInvitation(UUID userId, UUID enterpriseId, UUID invitationId) {
        lockMine(userId, enterpriseId);
        InvitationRow row = mapper.lockInvitation(invitationId);
        if (row == null || !row.organizationId().equals(enterpriseId)) {
            throw new AccountException(Error.NOT_FOUND);
        }
        if (!"PENDING".equals(row.status()) || !"PHONE".equals(row.inviteKind())) {
            throw new AccountException(Error.INVITATION_NOT_PENDING);
        }
        OffsetDateTime now = now();
        mapper.renewInvitation(invitationId, now, now.plus(INVITATION_TTL));
        audit(userId, "ENTERPRISE_INVITE_RENEW", "INVITATION", invitationId, enterpriseId, now);
        return pendingView(mapper.findInvitation(invitationId));
    }

    // ---- 邀请（被邀请方） ----

    @Transactional(readOnly = true)
    public ReceivedInvitationView readInvitation(UUID userId, UUID invitationId, String code) {
        UserRow user = mapper.findUser(userId);
        InvitationRow row = visibleInvitation(user, invitationId, code, false);
        return received(row, user.mobile());
    }

    @Transactional
    public EnterpriseView accept(UUID userId, UUID invitationId, String code) {
        UserRow user = mapper.lockUser(userId);
        InvitationRow row = visibleInvitation(user, invitationId, code, true);
        OffsetDateTime now = now();
        requirePendingAndFresh(row, now);
        if (mapper.lockEnterprise(row.organizationId()) == null) {
            throw new AccountException(Error.INVITATION_NOT_PENDING);
        }
        if (mapper.findMemberRole(row.organizationId(), userId) != null) {
            throw new AccountException(Error.ENTERPRISE_ALREADY_MEMBER);
        }
        if (mapper.countMyMemberships(userId) >= MAX_ENTERPRISES_PER_ACCOUNT) {
            throw new AccountException(Error.ENTERPRISE_LIMIT);
        }
        if (mapper.countMembers(row.organizationId(), null) >= MAX_MEMBERS) {
            throw new AccountException(Error.ENTERPRISE_MEMBER_LIMIT);
        }
        mapper.respondInvitation(invitationId, "ACCEPTED", userId, now);
        mapper.insertEnterpriseMember(row.organizationId(), userId, row.memberRole(), now);
        audit(userId, "ENTERPRISE_JOIN", "INVITATION", invitationId, row.organizationId(), now);
        return view(mapper.findMyEnterprise(userId, row.organizationId()));
    }

    @Transactional
    public ReceivedInvitationView decline(UUID userId, UUID invitationId, String code) {
        UserRow user = mapper.lockUser(userId);
        InvitationRow row = visibleInvitation(user, invitationId, code, true);
        OffsetDateTime now = now();
        requirePendingAndFresh(row, now);
        mapper.respondInvitation(invitationId, "DECLINED", userId, now);
        audit(userId, "ENTERPRISE_INVITE_DECLINE", "INVITATION", invitationId, row.organizationId(), now);
        return received(mapper.findInvitation(invitationId), user.mobile());
    }

    // ---- 身份 ----

    @Transactional
    public CurrentIdentity switchIdentity(UUID userId, UUID enterpriseId) {
        OffsetDateTime now = now();
        mapper.lockUser(userId);
        if (enterpriseId != null && mapper.findMyEnterprise(userId, enterpriseId) == null) {
            throw new AccountException(Error.NOT_FOUND);
        }
        mapper.updateActingOrganization(userId, enterpriseId, now);
        return currentIdentity(userId);
    }

    @Transactional(readOnly = true)
    public CurrentIdentity currentIdentity(UUID userId) {
        ActingRow acting = userId == null ? null : mapper.findActingEnterprise(userId);
        if (acting == null) {
            return CurrentIdentity.personal();
        }
        return new CurrentIdentity(CurrentIdentity.ENTERPRISE, acting.organizationId(), acting.organizationName(),
                acting.memberRole());
    }

    /** 交易下单时的付款主体：当前身份的企业，或个人；注销冷静期内 closing 为真。 */
    @Transactional(readOnly = true)
    public Payer payer(UUID userId, boolean closing) {
        CurrentIdentity identity = currentIdentity(userId);
        if (identity.isEnterprise()) {
            return new Payer(CurrentIdentity.ENTERPRISE, identity.enterpriseId(), identity.enterpriseName(), closing);
        }
        return new Payer(CurrentIdentity.PERSONAL, null, null, closing);
    }

    @Transactional(readOnly = true)
    public boolean isActiveMember(UUID userId, UUID enterpriseId) {
        return userId != null && enterpriseId != null && mapper.findMyEnterprise(userId, enterpriseId) != null;
    }

    @Transactional(readOnly = true)
    public Map<UUID, String> displayNames(List<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new java.util.HashMap<>();
        mapper.findDisplayNames(userIds).forEach(row -> names.put(row.id(), row.displayName()));
        return names;
    }

    // ---- 内部 ----

    private void dissolve(UUID enterpriseId, OffsetDateTime now) {
        mapper.dissolveEnterprise(enterpriseId, now);
        mapper.disableOrganization(enterpriseId, now);
        mapper.revokeAllInvitations(enterpriseId);
        mapper.clearActingOrganization(enterpriseId, null, now);
    }

    /**
     * 被邀请人能看到的邀请：手机号邀请要是本人当前的号码；链接邀请要带对的邀请码。看不到的按不存在回答。
     */
    private InvitationRow visibleInvitation(UserRow user, UUID invitationId, String code, boolean lock) {
        if (user == null || !"ACTIVE".equals(user.status())) {
            throw new AccountException(Error.NOT_FOUND);
        }
        InvitationRow row = lock ? mapper.lockInvitation(invitationId) : mapper.findInvitation(invitationId);
        if (row == null) {
            throw new AccountException(Error.NOT_FOUND);
        }
        if ("PHONE".equals(row.inviteKind())) {
            if (user.mobile() == null || !user.mobile().equals(row.inviteePhone())) {
                throw new AccountException(Error.NOT_FOUND);
            }
        } else {
            if (code == null || code.length() < 16 || code.length() > 64
                    || mapper.findInvitationByCode(invitationId, codeHash(code)) == null) {
                throw new AccountException(Error.NOT_FOUND);
            }
        }
        return row;
    }

    private static void requirePendingAndFresh(InvitationRow row, OffsetDateTime now) {
        if (!"PENDING".equals(row.status())) {
            throw new AccountException(Error.INVITATION_NOT_PENDING);
        }
        if (!row.expiresAt().isAfter(now)) {
            throw new AccountException(Error.INVITATION_EXPIRED);
        }
    }

    private EnterpriseRow requireMine(UUID userId, UUID enterpriseId) {
        EnterpriseRow row = enterpriseId == null ? null : mapper.findMyEnterprise(userId, enterpriseId);
        if (row == null) {
            throw new AccountException(Error.NOT_FOUND);
        }
        return row;
    }

    /** 先锁企业（同一家企业的成员变动排队），再确认本人还在其中。 */
    private EnterpriseRow lockMine(UUID userId, UUID enterpriseId) {
        if (enterpriseId == null || mapper.lockEnterprise(enterpriseId) == null) {
            throw new AccountException(Error.NOT_FOUND);
        }
        return requireMine(userId, enterpriseId);
    }

    private void requireAdmin(UUID userId, UUID enterpriseId) {
        if (!ADMIN.equals(mapper.findMemberRole(enterpriseId, userId))) {
            throw new AccountException(Error.ENTERPRISE_ADMIN_REQUIRED);
        }
    }

    private EnterpriseView view(EnterpriseRow row) {
        String cityName = row.cityCode() == null ? null : cities.names(List.of(row.cityCode())).get(row.cityCode());
        return new EnterpriseView(row.organizationId(), row.organizationName(), maskCreditCode(row.creditCode()),
                cityName, row.qualificationReviewedAt() != null, row.memberRole(), (int) row.memberCount(),
                row.createdAt());
    }

    private static EnterpriseMemberView memberView(MemberRow row, UUID userId) {
        return new EnterpriseMemberView(row.userId(), row.displayName(), PhoneNumbers.mask(row.mobile()),
                row.memberRole(), row.userId().equals(userId), row.joinedAt());
    }

    private static PendingInvitationView pendingView(InvitationRow row) {
        return new PendingInvitationView(row.id(), row.inviteKind(), PhoneNumbers.mask(row.inviteePhone()),
                row.memberRole(), row.invitedByName(), row.sentAt(), row.expiresAt());
    }

    private ReceivedInvitationView received(InvitationRow row, String myPhone) {
        String status = row.status();
        if ("PENDING".equals(status) && !row.expiresAt().isAfter(now())) {
            status = "EXPIRED";
        }
        return new ReceivedInvitationView(row.id(), row.inviteKind(), row.organizationName(),
                row.qualificationReviewedAt() != null, row.invitedByName(), row.memberRole(), row.sentAt(),
                row.expiresAt(), status, PhoneNumbers.mask(myPhone));
    }

    static CurrentIdentityView identityView(CurrentIdentity identity) {
        return new CurrentIdentityView(identity.type(), identity.enterpriseId(), identity.enterpriseName(),
                identity.role());
    }

    /** 前 4 位和后 2 位，中间 12 个星号。 */
    static String maskCreditCode(String code) {
        if (code == null || code.length() != 18) {
            return "";
        }
        return code.substring(0, 4) + "************" + code.substring(16);
    }

    static String codeHash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("enterprise-invite:" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String randomCode(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(ORG_CODE_ALPHABET.charAt(random.nextInt(ORG_CODE_ALPHABET.length())));
        }
        return builder.toString();
    }

    private void audit(UUID actorId, String action, String targetType, UUID targetId, UUID organizationId,
                       OffsetDateTime at) {
        mapper.insertAudit(actorId, action, targetType, targetId, organizationId, UUID.randomUUID().toString(), at);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }
}
