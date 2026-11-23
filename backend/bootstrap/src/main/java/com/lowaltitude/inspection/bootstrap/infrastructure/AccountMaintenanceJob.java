package com.lowaltitude.inspection.bootstrap.infrastructure;

import java.util.UUID;

import com.lowaltitude.inspection.identity.application.account.AccountClosureService;
import com.lowaltitude.inspection.identity.application.account.PersonalDataExportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 账号的定时任务（S5-8）：
 * <ul>
 *   <li>每 15 秒生成等着的个人信息导出（每次最多 5 份），删掉过期的副本；</li>
 *   <li>每 10 分钟把冷静期满的注销申请匿名化（每次最多 20 个，每个一个事务，失败的下次再试）。</li>
 * </ul>
 * 间隔可用 identity.account-jobs.export-interval-ms、closure-interval-ms 调（冒烟脚本调短）；
 * 测试里用 identity.account-jobs.enabled=false 关掉。
 */
@Component
@ConditionalOnProperty(prefix = "identity.account-jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AccountMaintenanceJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccountMaintenanceJob.class);
    private static final int EXPORTS_PER_RUN = 5;
    private static final int CLOSURES_PER_RUN = 20;

    private final ObjectProvider<PersonalDataExportService> exports;
    private final ObjectProvider<AccountClosureService> closures;

    public AccountMaintenanceJob(ObjectProvider<PersonalDataExportService> exports,
                                 ObjectProvider<AccountClosureService> closures) {
        this.exports = exports;
        this.closures = closures;
    }

    @Scheduled(fixedDelayString = "${identity.account-jobs.export-interval-ms:15000}", initialDelay = 20_000)
    public void generateExports() {
        PersonalDataExportService service = exports.getIfAvailable();
        if (service == null) {
            return;
        }
        try {
            for (int i = 0; i < EXPORTS_PER_RUN && service.generateNext(); i++) {
                // 一份一个事务；没有等着的就停。
            }
            int expired = service.expireOld(50);
            if (expired > 0) {
                LOGGER.info("Expired {} personal data exports", expired);
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("Generating personal data exports failed; will retry", failure);
        }
    }

    @Scheduled(fixedDelayString = "${identity.account-jobs.closure-interval-ms:600000}", initialDelay = 60_000)
    public void completeDueClosures() {
        AccountClosureService service = closures.getIfAvailable();
        if (service == null) {
            return;
        }
        for (UUID closureId : service.findDue(CLOSURES_PER_RUN)) {
            try {
                if (service.complete(closureId)) {
                    LOGGER.info("Account closure {} completed and anonymized", closureId);
                }
            } catch (RuntimeException failure) {
                LOGGER.warn("Account closure {} failed; will retry", closureId, failure);
            }
        }
    }
}
