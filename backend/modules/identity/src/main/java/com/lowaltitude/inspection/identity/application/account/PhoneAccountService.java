package com.lowaltitude.inspection.identity.application.account;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.lowaltitude.inspection.identity.application.account.AccountException.Error;
import com.lowaltitude.inspection.identity.application.account.AccountViews.PhoneBindingView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.PhoneChangeTicketView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.SmsCodeSentView;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper.UserRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/**
 * 手机号验证码登录与注册、绑定和更换手机号（S5-8）。
 *
 * <ul>
 *   <li>验证码登录：号码已绑定账号就登录它（账号要是有效的）；没有就注册一个个人客户账号（显示名“用户” + 后 4 位，
 *       自己一家个人客户机构，全局客户角色），之后可为同一账号设置密码。</li>
 *   <li>发登录验证码时不看号码是否注册，回答都一样。</li>
 *   <li>换号：已有手机号的先验证当前号码换一张 10 分钟的换号凭据，再验证新号码；没有手机号的直接验证新号码绑定。
 *       一个号码只能绑定一个账号。换号成功后其他设备退出登录（本机保留）。</li>
 * </ul>
 * 令牌由启动模块签发（与账号密码登录同一套），这里只给出账号 ID。
 */
public class PhoneAccountService {

    private static final String LOGIN_NAME_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";

    private final AccountMapper mapper;
    private final SmsCodeService codes;
    private final LoginSessionService sessions;
    private final Clock clock;
    private final SecureRandom random;

    public PhoneAccountService(AccountMapper mapper, SmsCodeService codes, LoginSessionService sessions, Clock clock,
                               SecureRandom random) {
        this.mapper = mapper;
        this.codes = codes;
        this.sessions = sessions;
        this.clock = clock;
        this.random = random;
    }

    @Transactional
    public SmsCodeSentView sendLoginCode(String phone) {
        codes.send(PhoneNumbers.require(phone), SmsCodeService.LOGIN, null);
        return sent(phone);
    }

    /** 验证码对了才看账号；对了但账号不可用（停用、已注销）按未登录回答。 */
    @Transactional(noRollbackFor = AccountException.class)
    public PhoneLogin login(String phone, String code) {
        PhoneNumbers.require(phone);
        // Serialize a verified login with a concurrent change of the same user's phone or credentials.
        mapper.lockUserIdByMobile(phone);
        codes.verify(phone, SmsCodeService.LOGIN, code, null);
        UserRow user = mapper.findUserByMobile(phone);
        if (user != null) {
            if (!"ACTIVE".equals(user.status())) {
                throw new AccountException(Error.UNAUTHORIZED);
            }
            return new PhoneLogin(user.id(), false);
        }
        return register(phone);
    }

    @Transactional
    public SmsCodeSentView sendAccountCode(UUID userId, String purpose, String phone) {
        UserRow user = requireUser(userId);
        String target;
        switch (purpose == null ? "" : purpose) {
            case SmsCodeService.VERIFY_CURRENT, SmsCodeService.EXPORT -> {
                if (user.mobile() == null) {
                    throw new AccountException(Error.PHONE_REQUIRED);
                }
                target = user.mobile();
            }
            case SmsCodeService.BIND_NEW -> {
                target = PhoneNumbers.require(phone);
                if (target.equals(user.mobile())) {
                    throw new AccountException(Error.PHONE_UNCHANGED);
                }
                if (mapper.findUserByMobile(target) != null) {
                    throw new AccountException(Error.PHONE_TAKEN);
                }
            }
            default -> throw new AccountException(Error.INVALID);
        }
        codes.send(target, purpose, userId);
        return sent(target);
    }

    @Transactional(noRollbackFor = AccountException.class)
    public PhoneChangeTicketView verifyCurrent(UUID userId, String code) {
        UserRow user = requireUser(userId);
        if (user.mobile() == null) {
            throw new AccountException(Error.PHONE_REQUIRED);
        }
        codes.verify(user.mobile(), SmsCodeService.VERIFY_CURRENT, code, userId);
        return new PhoneChangeTicketView(codes.issueChangeTicket(userId, user.mobile()),
                (int) SmsCodeService.TICKET_TTL.toSeconds());
    }

    /**
     * 绑定或更换手机号。先看凭据和新号码能不能用，再核对新号码的验证码，最后才用掉凭据：新号码的码输错时凭据还能再用。
     */
    @Transactional(noRollbackFor = AccountException.class)
    public PhoneBindingView changePhone(UUID userId, UUID currentSessionId, String newPhone, String code,
                                        String changeTicket) {
        PhoneNumbers.require(newPhone);
        UserRow user = mapper.lockUser(userId);
        if (user == null || !"ACTIVE".equals(user.status())) {
            throw new AccountException(Error.NOT_FOUND);
        }
        UUID ticketId = null;
        if (user.mobile() != null) {
            ticketId = codes.checkChangeTicket(userId, user.mobile(), changeTicket);
            if (newPhone.equals(user.mobile())) {
                throw new AccountException(Error.PHONE_UNCHANGED);
            }
        }
        if (mapper.findUserByMobile(newPhone) != null) {
            throw new AccountException(Error.PHONE_TAKEN);
        }
        codes.verify(newPhone, SmsCodeService.BIND_NEW, code, userId);
        OffsetDateTime now = now();
        try {
            mapper.updateMobile(userId, newPhone, now);
        } catch (DuplicateKeyException takenMeanwhile) {
            throw new AccountException(Error.PHONE_TAKEN);
        }
        int signedOut = 0;
        if (ticketId != null) {
            mapper.clearPasswordSetupProofs(userId);
            mapper.retirePhoneCredentials(userId, user.mobile(), newPhone, now);
            codes.consume(ticketId);
            signedOut = sessions.revokeAllExcept(userId, currentSessionId, "PHONE_CHANGED");
        }
        mapper.insertAudit(userId, ticketId == null ? "ACCOUNT_PHONE_BIND" : "ACCOUNT_PHONE_CHANGE", "USER", userId,
                null, UUID.randomUUID().toString(), now);
        return new PhoneBindingView(PhoneNumbers.mask(newPhone), signedOut);
    }

    private PhoneLogin register(String phone) {
        OffsetDateTime now = now();
        String suffix = randomToken(12);
        AccountMapper.PersonalRegistration created = mapper.insertPhoneUser("p-" + suffix, "用户" + phone.substring(7),
                phone, "PERSONAL-" + suffix.toUpperCase(java.util.Locale.ROOT), "个人客户 " + PhoneNumbers.mask(phone), now);
        if (created == null) {
            UserRow existing = mapper.findUserByMobile(phone);
            if (existing == null || !"ACTIVE".equals(existing.status())) {
                throw new AccountException(Error.UNAUTHORIZED);
            }
            return new PhoneLogin(existing.id(), false);
        }
        UUID userId = created.userId();
        UUID organizationId = created.organizationId();
        mapper.insertOrganizationMember(organizationId, userId, now);
        mapper.grantCustomerRole(userId);
        mapper.insertAudit(userId, "ACCOUNT_REGISTER_PHONE", "USER", userId, organizationId,
                UUID.randomUUID().toString(), now);
        return new PhoneLogin(userId, true);
    }

    private UserRow requireUser(UUID userId) {
        UserRow user = userId == null ? null : mapper.findUser(userId);
        if (user == null || !"ACTIVE".equals(user.status())) {
            throw new AccountException(Error.NOT_FOUND);
        }
        return user;
    }

    private SmsCodeSentView sent(String phone) {
        return new SmsCodeSentView(PhoneNumbers.mask(phone), (int) SmsCodeService.RESEND_INTERVAL.toSeconds(),
                (int) SmsCodeService.CODE_TTL.toSeconds());
    }

    private String randomToken(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(LOGIN_NAME_ALPHABET.charAt(random.nextInt(LOGIN_NAME_ALPHABET.length())));
        }
        return builder.toString();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }

    /** 验证码登录的结果：账号 ID，和这次是不是新注册的。 */
    public record PhoneLogin(UUID userId, boolean registered) {
    }
}
