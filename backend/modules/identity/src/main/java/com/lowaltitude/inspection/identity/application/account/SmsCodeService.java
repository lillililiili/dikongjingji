package com.lowaltitude.inspection.identity.application.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.lowaltitude.inspection.identity.application.account.AccountException.Error;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper.PhoneGuardRow;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper.SmsCodeRow;

/**
 * 短信验证码（S5-8）：发送、核对、频率限制和错误锁定，以及换号第一步通过后的一次性换号凭据。
 *
 * <ul>
 *   <li>6 位数字，5 分钟有效，只存摘要；发新码时同一号码同一用途的旧码作废。</li>
 *   <li>同一号码同一用途 60 秒内只发一次；同一号码北京时间每天最多 10 条（各用途合计）。</li>
 *   <li>一条码错 5 次作废；同一号码 1 小时内错 10 次锁 30 分钟，期间不发也不认。</li>
 *   <li>绑定账号的码（换号、导出）只认发给它的那个账号。</li>
 * </ul>
 * 核对失败要把错误次数记下来，所以调用方的事务对 {@link AccountException} 不回滚（noRollbackFor）。
 */
public class SmsCodeService {

    public static final String LOGIN = "LOGIN";
    public static final String VERIFY_CURRENT = "VERIFY_CURRENT";
    public static final String BIND_NEW = "BIND_NEW";
    public static final String EXPORT = "EXPORT";
    /** 商家自助申请入驻时验证联系人手机号（迁移 V012）。 */
    public static final String SIGNUP = "SIGNUP";
    static final String CHANGE_TICKET = "CHANGE_TICKET";

    public static final Duration CODE_TTL = Duration.ofMinutes(5);
    public static final Duration RESEND_INTERVAL = Duration.ofSeconds(60);
    public static final Duration TICKET_TTL = Duration.ofMinutes(10);
    static final Duration FAILURE_WINDOW = Duration.ofHours(1);
    static final Duration LOCK_DURATION = Duration.ofMinutes(30);
    public static final int DAILY_LIMIT = 10;
    public static final int MAX_ATTEMPTS_PER_CODE = 5;
    public static final int MAX_FAILURES_PER_WINDOW = 10;
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final Map<String, String> PURPOSE_TEXT = Map.of(
            LOGIN, "登录",
            VERIFY_CURRENT, "验证当前手机号",
            BIND_NEW, "绑定新手机号",
            EXPORT, "申请导出个人信息",
            SIGNUP, "商家入驻申请");

    private final AccountMapper mapper;
    private final AccountPorts.SmsGateway gateway;
    private final Clock clock;
    private final SecureRandom random;

    public SmsCodeService(AccountMapper mapper, AccountPorts.SmsGateway gateway, Clock clock, SecureRandom random) {
        this.mapper = mapper;
        this.gateway = gateway;
        this.clock = clock;
        this.random = random;
    }

    /** 发一条验证码；在调用方的事务里写，短信服务不可用时整体回滚。 */
    public void send(String phone, String purpose, UUID userId) {
        PhoneNumbers.require(phone);
        if (!PURPOSE_TEXT.containsKey(purpose)) {
            throw new IllegalArgumentException("unknown purpose " + purpose);
        }
        OffsetDateTime now = now();
        OffsetDateTime lockedUntil = mapper.findPhoneLockedUntil(phone);
        if (lockedUntil != null && lockedUntil.isAfter(now)) {
            throw new AccountException(Error.PHONE_LOCKED);
        }
        OffsetDateTime last = mapper.findLastSentAt(phone, purpose);
        if (last != null && last.plus(RESEND_INTERVAL).isAfter(now)) {
            throw new AccountException(Error.SMS_TOO_FREQUENT);
        }
        OffsetDateTime startOfDay = LocalDate.now(clock.withZone(BEIJING)).atStartOfDay(BEIJING).toOffsetDateTime();
        if (mapper.countSentSince(phone, startOfDay) >= DAILY_LIMIT) {
            throw new AccountException(Error.SMS_TOO_FREQUENT);
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        mapper.retireSmsCodes(phone, purpose, now);
        mapper.insertSmsCode(phone, purpose, userId, hash(phone, purpose, code), now, now.plus(CODE_TTL));
        try {
            gateway.send(phone, "验证码 " + code + "，5 分钟内有效，用于" + PURPOSE_TEXT.get(purpose) + "。请勿告诉他人。");
        } catch (AccountPorts.SmsUnavailableException unavailable) {
            throw new AccountException(Error.SMS_UNAVAILABLE);
        }
    }

    /**
     * 核对并用掉验证码。userId 不为空时这条码必须是发给这个账号的。不对时记错误次数（码和号码各记一次）并拒绝。
     */
    public void verify(String phone, String purpose, String code, UUID userId) {
        PhoneNumbers.require(phone);
        if (code == null || !code.matches("^[0-9]{6}$")) {
            throw new AccountException(Error.INVALID);
        }
        OffsetDateTime now = now();
        mapper.ensurePhoneGuard(phone, now);
        PhoneGuardRow guard = mapper.lockPhoneGuard(phone);
        if (guard.lockedUntil() != null && guard.lockedUntil().isAfter(now)) {
            throw new AccountException(Error.PHONE_LOCKED);
        }
        SmsCodeRow row = mapper.lockUsableSmsCode(phone, purpose, now, MAX_ATTEMPTS_PER_CODE);
        boolean matches = row != null
                && Objects.equals(row.userId(), userId)
                && MessageDigest.isEqual(
                        row.codeHash().trim().getBytes(StandardCharsets.US_ASCII),
                        hash(phone, purpose, code).getBytes(StandardCharsets.US_ASCII));
        if (!matches) {
            if (row != null) {
                mapper.markSmsCodeFailed(row.id());
            }
            recordFailure(phone, guard, now);
            throw new AccountException(Error.SMS_CODE_INVALID);
        }
        mapper.consumeSmsCode(row.id(), now);
        // 输对了：这个号码的错误计数清零（锁定期已经过了才会走到这里）。
        mapper.updatePhoneGuard(phone, 0, now, null, now);
    }

    /** 换号第一步通过后发一张一次性的换号凭据（不是短信，只返回给本人）。 */
    public String issueChangeTicket(UUID userId, String currentPhone) {
        OffsetDateTime now = now();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        mapper.retireSmsCodes(currentPhone, CHANGE_TICKET, now);
        mapper.insertSmsCode(currentPhone, CHANGE_TICKET, userId, hash(currentPhone, CHANGE_TICKET, ticket),
                now, now.plus(TICKET_TTL));
        return ticket;
    }

    /** 换号凭据对得上（这个账号、这个号码、没过期、没用过）时返回它的 ID，留给 {@link #consume} 用掉；否则拒绝。 */
    public UUID checkChangeTicket(UUID userId, String currentPhone, String ticket) {
        if (ticket == null || ticket.length() < 32 || ticket.length() > 64) {
            throw new AccountException(Error.PHONE_TICKET_INVALID);
        }
        SmsCodeRow row = mapper.lockUsableSmsCode(currentPhone, CHANGE_TICKET, now(), MAX_ATTEMPTS_PER_CODE);
        if (row == null || !Objects.equals(row.userId(), userId)
                || !MessageDigest.isEqual(row.codeHash().trim().getBytes(StandardCharsets.US_ASCII),
                        hash(currentPhone, CHANGE_TICKET, ticket).getBytes(StandardCharsets.US_ASCII))) {
            throw new AccountException(Error.PHONE_TICKET_INVALID);
        }
        return row.id();
    }

    public void consume(UUID codeId) {
        mapper.consumeSmsCode(codeId, now());
    }

    private void recordFailure(String phone, PhoneGuardRow guard, OffsetDateTime now) {
        boolean windowOpen = guard.windowStartedAt() != null && guard.windowStartedAt().plus(FAILURE_WINDOW).isAfter(now);
        int failures = windowOpen ? guard.failedCount() + 1 : 1;
        OffsetDateTime windowStart = windowOpen ? guard.windowStartedAt() : now;
        OffsetDateTime lockedUntil = failures >= MAX_FAILURES_PER_WINDOW ? now.plus(LOCK_DURATION) : null;
        mapper.updatePhoneGuard(phone, lockedUntil == null ? failures : 0, lockedUntil == null ? windowStart : now,
                lockedUntil, now);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }

    static String hash(String phone, String purpose, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    (phone + ":" + purpose + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
