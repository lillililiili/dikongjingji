package com.lowaltitude.inspection.support.application;

import java.time.Instant;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.IdentityOrganizationPort;
import com.lowaltitude.inspection.identity.domain.IdentityProfile;
import com.lowaltitude.inspection.identity.domain.IdentityProfilePort;
import com.lowaltitude.inspection.notification.application.NewNotification;
import com.lowaltitude.inspection.notification.application.NotificationCategory;
import com.lowaltitude.inspection.notification.application.NotificationService;
import com.lowaltitude.inspection.notification.application.NotificationTargetType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 客服会话（G1）的端口接到身份模块（机构、显示名）和站内信。对方回复时给客户记一条“客服消息”：
 * 标题按对方类型写“商家 / 服务商 / 平台客服回复了你”，正文只写对方名称，不抄消息内容；同一会话最多一条未读。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class SupportPortsConfiguration {

    static final String EVENT_CODE = "SUPPORT_REPLIED";

    @Bean
    SupportPorts.Organizations supportOrganizations(IdentityOrganizationPort organizations) {
        return organizationId -> organizations.findActiveOrganization(organizationId)
                .map(found -> new SupportPorts.Organization(found.organizationId(), found.name(),
                        found.organizationType()));
    }

    @Bean
    SupportPorts.Profiles supportProfiles(IdentityProfilePort profiles) {
        return accountId -> profiles.findProfile(accountId).map(IdentityProfile::displayName);
    }

    @Bean
    SupportPorts.Notifier supportNotifier(NotificationService notifications) {
        return notifier(notifications);
    }

    static SupportPorts.Notifier notifier(NotificationService notifications) {
        return new SupportPorts.Notifier() {
            @Override
            public void replied(UUID customerId, UUID conversationId, String counterpartKind, String counterpartName,
                                UUID messageId, Instant at) {
                notifications.recordUnlessUnread(new NewNotification(
                        customerId,
                        NotificationCategory.CUSTOMER_SERVICE,
                        EVENT_CODE,
                        title(counterpartKind),
                        clip(counterpartName, 60) + "回复了你的消息，点开查看。",
                        NotificationTargetType.CONVERSATION,
                        conversationId,
                        null,
                        "support-message:" + messageId,
                        at));
            }

            @Override
            public void read(UUID customerId, UUID conversationId) {
                notifications.markTargetRead(customerId, NotificationCategory.CUSTOMER_SERVICE,
                        NotificationTargetType.CONVERSATION, conversationId);
            }
        };
    }

    static String title(String counterpartKind) {
        return switch (counterpartKind) {
            case "MERCHANT" -> "商家回复了你";
            case "PROVIDER" -> "服务商回复了你";
            default -> "平台客服回复了你";
        };
    }

    private static String clip(String name, int max) {
        return name.codePointCount(0, name.length()) <= max ? name : name.substring(0, name.offsetByCodePoints(0, max));
    }
}
