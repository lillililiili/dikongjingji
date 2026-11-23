package com.lowaltitude.inspection.notification.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 推送在写入站内信（或发布公告）的事务提交之后才发：回滚了就不推；推送出错只记日志，不影响已经提交的事务。
 * 没有事务时立刻发。
 */
final class AfterCommit {

    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

    private AfterCommit() {
    }

    static void run(String what, Runnable task) {
        Runnable guarded = () -> {
            try {
                task.run();
            } catch (RuntimeException exception) {
                log.warn("{} after commit failed", what, exception);
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
    }
}
