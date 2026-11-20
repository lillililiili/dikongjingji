package com.lowaltitude.inspection.support.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.IdentityOrganization;
import com.lowaltitude.inspection.identity.domain.IdentityOrganizationPort;
import com.lowaltitude.inspection.identity.domain.IdentityProfile;
import com.lowaltitude.inspection.identity.domain.IdentityProfilePort;
import com.lowaltitude.inspection.notification.application.NewNotification;
import com.lowaltitude.inspection.notification.application.NotificationCategory;
import com.lowaltitude.inspection.notification.application.NotificationService;
import com.lowaltitude.inspection.notification.application.NotificationTargetType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 客服会话（G1）的端口接到身份模块和站内信：机构、显示名原样转过去；回复记一条不抄内容的“客服消息”。 */
class SupportPortsConfigurationTest {

    private static final UUID CUSTOMER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MERCHANT = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID CONVERSATION = UUID.fromString("e5000000-0000-4000-8000-000000000001");
    private static final UUID MESSAGE = UUID.fromString("e6000000-0000-4000-8000-000000000001");
    private static final Instant AT = Instant.parse("2026-11-19T02:00:00Z");

    private final SupportPortsConfiguration configuration = new SupportPortsConfiguration();

    @Test
    void organizationsAndNamesComeFromTheIdentityModule() {
        IdentityOrganizationPort organizations = mock(IdentityOrganizationPort.class);
        when(organizations.findActiveOrganization(MERCHANT))
                .thenReturn(Optional.of(new IdentityOrganization(MERCHANT, "示例机器人商家", "PROVIDER", null)));
        IdentityProfilePort profiles = mock(IdentityProfilePort.class);
        when(profiles.findProfile(CUSTOMER))
                .thenReturn(Optional.of(new IdentityProfile(CUSTOMER, "王女士（示例）", "东营示例物流公司", "CUSTOMER")));

        assertThat(configuration.supportOrganizations(organizations).findActive(MERCHANT))
                .contains(new SupportPorts.Organization(MERCHANT, "示例机器人商家", "PROVIDER"));
        assertThat(configuration.supportOrganizations(organizations).findActive(UUID.randomUUID())).isEmpty();
        assertThat(configuration.supportProfiles(profiles).displayName(CUSTOMER)).contains("王女士（示例）");
        assertThat(configuration.supportProfiles(profiles).displayName(UUID.randomUUID())).isEmpty();
    }

    @Test
    void aReplyRecordsOneUnreadCustomerServiceNotificationWithoutTheMessage() {
        NotificationService notifications = mock(NotificationService.class);
        SupportPorts.Notifier notifier = configuration.supportNotifier(notifications);

        notifier.replied(CUSTOMER, CONVERSATION, "MERCHANT", "示例机器人商家", MESSAGE, AT);

        ArgumentCaptor<NewNotification> recorded = ArgumentCaptor.forClass(NewNotification.class);
        verify(notifications).recordUnlessUnread(recorded.capture());
        assertThat(recorded.getValue()).isEqualTo(new NewNotification(CUSTOMER, NotificationCategory.CUSTOMER_SERVICE,
                "SUPPORT_REPLIED", "商家回复了你", "示例机器人商家回复了你的消息，点开查看。",
                NotificationTargetType.CONVERSATION, CONVERSATION, null, "support-message:" + MESSAGE, AT));
        assertThat(SupportPortsConfiguration.title("PROVIDER")).isEqualTo("服务商回复了你");
        assertThat(SupportPortsConfiguration.title("PLATFORM")).isEqualTo("平台客服回复了你");

        // 名称太长时截到 60 个字，正文不超过站内信的 300 字。
        notifier.replied(CUSTOMER, CONVERSATION, "PROVIDER", "长".repeat(160), MESSAGE, AT);
        verify(notifications, org.mockito.Mockito.times(2)).recordUnlessUnread(recorded.capture());
        assertThat(recorded.getValue().body()).isEqualTo("长".repeat(60) + "回复了你的消息，点开查看。");

        notifier.read(CUSTOMER, CONVERSATION);
        verify(notifications).markTargetRead(CUSTOMER, NotificationCategory.CUSTOMER_SERVICE,
                NotificationTargetType.CONVERSATION, CONVERSATION);
    }
}
