package com.lowaltitude.inspection.bootstrap.infrastructure;

import com.lowaltitude.inspection.activity.application.ActivityWaitlistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 活动候补（S5-3）：每分钟把过了保留时间（转正后 15 分钟）还没确认或去付款的转正作废、名额给下一位，
 * 再把场次已经开始还在排队的候补结束。收费的去付款之后由交易模块的超时关单释放名额，不在这里。
 * 间隔可用 activity.waitlist.expire-delay-ms 调（冒烟脚本调短）；activity.waitlist.expiry.enabled=false 时不装配。
 */
@Component
@ConditionalOnProperty(prefix = "activity.waitlist.expiry", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ActivityWaitlistExpiryJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(ActivityWaitlistExpiryJob.class);

    private final ObjectProvider<ActivityWaitlistService> waitlists;

    public ActivityWaitlistExpiryJob(ObjectProvider<ActivityWaitlistService> waitlists) {
        this.waitlists = waitlists;
    }

    @Scheduled(fixedDelayString = "${activity.waitlist.expire-delay-ms:60000}", initialDelay = 35_000)
    public void expireOffers() {
        ActivityWaitlistService service = waitlists.getIfAvailable();
        if (service == null) {
            return;
        }
        try {
            int expired = service.expire();
            if (expired > 0) {
                LOGGER.info("Expired {} activity waitlist offers that were not taken in time", expired);
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("Expiring activity waitlist offers failed; will retry", failure);
        }
    }
}
