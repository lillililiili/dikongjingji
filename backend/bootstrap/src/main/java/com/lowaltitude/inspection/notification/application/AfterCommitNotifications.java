package com.lowaltitude.inspection.notification.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 在业务事务提交之后才记站内信（R6-5）：发货、付款到账、退款到账、报名、预约的事务回滚了就没有通知，
 * 通知写失败也不会让已经提交的业务变成失败。写入用自己的新事务（提交之后原事务的连接还挂着，但不会再提交）。
 * 同一事件的幂等由 sourceKey 保证；提交之后写失败只记日志，这条通知就没有了（不重试）。
 */
final class AfterCommitNotifications {

    private static final Logger log = LoggerFactory.getLogger(AfterCommitNotifications.class);

    private final NotificationService notifications;
    private final TransactionOperations newTransaction;

    /** @param newTransaction 每次开一个新事务（PROPAGATION_REQUIRES_NEW） */
    AfterCommitNotifications(NotificationService notifications, TransactionOperations newTransaction) {
        this.notifications = notifications;
        this.newTransaction = newTransaction;
    }

    void record(NewNotification notification) {
        Runnable write = () -> {
            try {
                newTransaction.executeWithoutResult(status -> notifications.record(notification));
            } catch (RuntimeException exception) {
                log.error("recording notification {} after commit failed", notification.sourceKey(), exception);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    write.run();
                }
            });
        } else {
            write.run();
        }
    }
}
