package com.lowaltitude.inspection.identity.application.account;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.identity.application.account.AccountException.Error;
import com.lowaltitude.inspection.identity.application.account.AccountViews.ClosureConditionView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.ClosureView;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper.ClosureRow;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper.UserRow;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注销账户（S5-8，docs/redesign/08-后续开发计划.md §5“账号”：注销 15 天冷静期后匿名化）。
 *
 * <ul>
 *   <li>申请条件：没有进行中的订单、没有待参加的报名（别的模块经 {@link AccountPorts.ClosureCondition} 给出），
 *       不是还有其他成员的企业的唯一管理员。</li>
 *   <li>申请后 15 天冷静期：仍能登录（为了撤回），但不能下单和付款（交易模块经付款主体端口得知）；可随时撤回。</li>
 *   <li>到期由服务端定时任务匿名化：退出全部企业、别的模块去标识（删收货地址、订单配送快照打码）、会话全部撤销、
 *       账号改成“已注销用户”且不能再登录。订单等业务数据保留。</li>
 * </ul>
 */
public class AccountClosureService {

    public static final Duration COOLING_OFF = Duration.ofDays(15);
    static final String NO_ENTERPRISE_ADMIN = "NO_ENTERPRISE_ADMIN";

    private final AccountMapper mapper;
    private final List<AccountPorts.ClosureCondition> conditions;
    private final List<AccountPorts.PersonalDataEraser> erasers;
    private final EnterpriseService enterprises;
    private final LoginSessionService sessions;
    private final Clock clock;

    public AccountClosureService(AccountMapper mapper, List<AccountPorts.ClosureCondition> conditions,
                                 List<AccountPorts.PersonalDataEraser> erasers, EnterpriseService enterprises,
                                 LoginSessionService sessions, Clock clock) {
        this.mapper = mapper;
        this.conditions = List.copyOf(conditions);
        this.erasers = List.copyOf(erasers);
        this.enterprises = enterprises;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ClosureView read(UUID userId) {
        ClosureRow pending = mapper.findPendingClosure(userId);
        return view(pending, evaluate(userId));
    }

    @Transactional
    public ClosureView request(UUID userId) {
        UserRow user = mapper.lockUser(userId);
        if (user == null || !"ACTIVE".equals(user.status())) {
            throw new AccountException(Error.NOT_FOUND);
        }
        if (mapper.findPendingClosure(userId) != null) {
            throw new AccountException(Error.CLOSURE_ALREADY_PENDING);
        }
        List<ClosureConditionView> evaluated = evaluate(userId);
        if (evaluated.stream().anyMatch(condition -> !condition.met())) {
            throw new AccountException(Error.CLOSURE_CONDITIONS_UNMET);
        }
        OffsetDateTime now = now();
        mapper.insertClosure(userId, now, now.plus(COOLING_OFF));
        mapper.insertAudit(userId, "ACCOUNT_CLOSURE_REQUEST", "USER", userId, null, UUID.randomUUID().toString(), now);
        return view(mapper.findPendingClosure(userId), evaluated);
    }

    @Transactional
    public ClosureView withdraw(UUID userId) {
        mapper.lockUser(userId);
        ClosureRow pending = mapper.findPendingClosure(userId);
        if (pending == null) {
            throw new AccountException(Error.CLOSURE_NOT_PENDING);
        }
        OffsetDateTime now = now();
        mapper.withdrawClosure(pending.id(), now);
        mapper.insertAudit(userId, "ACCOUNT_CLOSURE_WITHDRAW", "USER", userId, null, UUID.randomUUID().toString(), now);
        return view(null, evaluate(userId));
    }

    /** 在冷静期内（交易下单前检查）。 */
    @Transactional(readOnly = true)
    public boolean isClosing(UUID userId) {
        return userId != null && mapper.findPendingClosure(userId) != null;
    }

    /** 到期要匿名化的申请（定时任务先取，再逐条调 {@link #complete}，每条一个事务）。 */
    @Transactional(readOnly = true)
    public List<UUID> findDue(int limit) {
        return mapper.findDueClosures(now(), limit).stream().map(ClosureRow::id).toList();
    }

    /** 匿名化一个到期的申请；已经处理过或撤回了的什么都不做。别的模块去标识失败时整条回滚，下次再试。 */
    @Transactional
    public boolean complete(UUID closureId) {
        ClosureRow row = mapper.lockPendingClosure(closureId);
        OffsetDateTime now = now();
        if (row == null || row.dueAt().isAfter(now)) {
            return false;
        }
        UUID userId = row.userId();
        mapper.lockUser(userId);
        enterprises.leaveAllForClosure(userId);
        for (AccountPorts.PersonalDataEraser eraser : erasers) {
            eraser.erase(userId);
        }
        sessions.revokeAllExcept(userId, null, "CLOSED");
        mapper.anonymizeUser(userId, "closed-" + userId.toString().replace("-", ""), now);
        mapper.completeClosure(closureId, now);
        mapper.insertAudit(null, "ACCOUNT_CLOSURE_COMPLETE", "USER", userId, null, UUID.randomUUID().toString(), now);
        return true;
    }

    private List<ClosureConditionView> evaluate(UUID userId) {
        List<ClosureConditionView> result = new ArrayList<>();
        for (AccountPorts.ClosureCondition condition : conditions) {
            String unmet = condition.unmetDetail(userId);
            result.add(new ClosureConditionView(condition.code(), condition.title(), unmet == null,
                    unmet == null ? "已满足" : unmet));
        }
        List<String> soleAdmin = enterprises.soleAdminOf(userId);
        result.add(new ClosureConditionView(NO_ENTERPRISE_ADMIN, "企业管理员已移交或退出", soleAdmin.isEmpty(),
                soleAdmin.isEmpty() ? "已满足" : "仍是 " + String.join("、", soleAdmin) + " 的唯一管理员"));
        return List.copyOf(result);
    }

    private static ClosureView view(ClosureRow pending, List<ClosureConditionView> conditions) {
        if (pending == null) {
            return new ClosureView("NONE", null, null, conditions);
        }
        return new ClosureView("PENDING", pending.requestedAt(), pending.dueAt(), conditions);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }
}
