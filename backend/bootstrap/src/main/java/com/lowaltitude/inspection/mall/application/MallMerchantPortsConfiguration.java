package com.lowaltitude.inspection.mall.application;

import java.time.Clock;

import com.lowaltitude.inspection.activity.application.ActivityOrganizerCatalog;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityOrganizerMapper;
import com.lowaltitude.inspection.content.application.ContentPublisherCatalog;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentPublisherMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 商家主页（S5-2）的“活动”“内容”页签接到活动模块和内容模块的只读入口；商城不读它们的表。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class MallMerchantPortsConfiguration {

    @Bean
    ActivityOrganizerCatalog activityOrganizerCatalog(ActivityOrganizerMapper mapper) {
        return new ActivityOrganizerCatalog(mapper, Clock.systemUTC());
    }

    @Bean
    ContentPublisherCatalog contentPublisherCatalog(ContentPublisherMapper mapper) {
        return new ContentPublisherCatalog(mapper, Clock.systemUTC());
    }

    @Bean
    MallMerchantPorts.Activities mallMerchantActivities(ActivityOrganizerCatalog catalog) {
        return (organizationId, cityCode, limit) -> {
            ActivityOrganizerCatalog.Upcoming upcoming = catalog.upcoming(organizationId, cityCode, limit);
            return new MallMerchantPorts.Slice<>(upcoming.total(), upcoming.items().stream()
                    .map(item -> new MallMerchantPorts.ActivityItem(item.activityId(), item.title(), item.coverUrl(),
                            item.venueName(), item.startsAt(), item.priceMode(), item.priceAmountMinor()))
                    .toList());
        };
    }

    @Bean
    MallMerchantPorts.Contents mallMerchantContents(ContentPublisherCatalog catalog) {
        return (organizationId, cityCode, limit) -> {
            ContentPublisherCatalog.Published published = catalog.published(organizationId, cityCode, limit);
            return new MallMerchantPorts.Slice<>(published.total(), published.items().stream()
                    .map(item -> new MallMerchantPorts.ContentItem(item.contentId(), item.kind(), item.title(),
                            item.coverUrl(), item.sponsored(), item.sample(), item.publishedAt()))
                    .toList());
        };
    }
}
