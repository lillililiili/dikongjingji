package com.lowaltitude.inspection.bootstrap.infrastructure;

import com.lowaltitude.inspection.mall.application.MallInquiryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每分钟处理一批到期的询价单（F4，§25.1 第 3 条）：报价过了有效期的标为过期；截止之后没有有效报价的关闭。
 * 测试里用 mall.inquiry-deadline.enabled=false 关掉。
 */
@Component
@ConditionalOnProperty(prefix = "mall.inquiry-deadline", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MallInquiryDeadlineJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(MallInquiryDeadlineJob.class);

    private final ObjectProvider<MallInquiryService> inquiries;

    public MallInquiryDeadlineJob(ObjectProvider<MallInquiryService> inquiries) {
        this.inquiries = inquiries;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 50_000)
    public void closeLapsedInquiries() {
        MallInquiryService service = inquiries.getIfAvailable();
        if (service == null) {
            return;
        }
        try {
            int closed = service.closeLapsed();
            if (closed > 0) {
                LOGGER.info("Closed {} mall inquiries with no live quote after their deadline", closed);
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("Closing mall inquiries past their deadline failed; will retry", failure);
        }
    }
}
