package com.lowaltitude.inspection.trade.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务提交之后再做的事（E09：业务模块在自己的事务里建的退款，提交后才交给渠道——没提交的退款不能先退钱）。
 * 测试里换成记下动作、由测试决定什么时候“提交”的实现。
 */
@FunctionalInterface
interface AfterCommit {

    void run(Runnable action);

    /**
     * 有 Spring 事务时登记到提交之后；动作失败只记日志，不让已经提交的请求变成失败（退款有定时任务兜底重提）。
     * 没有事务时（不该发生）直接做。
     */
    static AfterCommit spring() {
        Logger log = LoggerFactory.getLogger(AfterCommit.class);
        return action -> {
            Runnable guarded = () -> {
                try {
                    action.run();
                } catch (RuntimeException exception) {
                    log.error("an after-commit action failed; the scheduled job will retry", exception);
                }
            };
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        guarded.run();
                    }
                });
            } else {
                guarded.run();
            }
        };
    }
}
