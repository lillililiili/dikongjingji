package com.lowaltitude.inspection.bootstrap.infrastructure;

import com.lowaltitude.inspection.rental.application.RentalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每分钟处理一批到期的租赁询价单（F3，§24.1 第 3 条）：提交 7 天还没有报价的关闭，报价过了有效期的标为过期。
 * 测试里用 rental.deadline.enabled=false 关掉。
 */
@Component
@ConditionalOnProperty(prefix = "rental.deadline", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RentalDeadlineJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(RentalDeadlineJob.class);

    private final ObjectProvider<RentalService> rentals;

    public RentalDeadlineJob(ObjectProvider<RentalService> rentals) {
        this.rentals = rentals;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    public void closeLapsedInquiries() {
        RentalService service = rentals.getIfAvailable();
        if (service == null) {
            return;
        }
        try {
            int changed = service.closeLapsed();
            if (changed > 0) {
                LOGGER.info("Closed or expired {} rental inquiries past their deadline", changed);
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("Closing rental inquiries past their deadline failed; will retry", failure);
        }
    }
}
