package com.lowaltitude.inspection.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.notification.application.NotificationRows.MessageRow;
import org.junit.jupiter.api.Test;

class NotificationServiceTest {

    private static final UUID ME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SOMEONE_ELSE = UUID.fromString("11111111-1111-1111-1111-222222222222");
    private static final UUID TARGET = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-17T04:00:00Z");

    private final FakeStore store = new FakeStore();
    private final List<NewNotification> pushed = new ArrayList<>();
    private final NotificationService service = new NotificationService(store, Clock.fixed(NOW, ZoneOffset.UTC),
            pushed::add);

    @Test
    void recordsOncePerSourceKey() {
        assertThat(service.record(notification(ME, "inspection-outbox:1"))).isTrue();
        assertThat(service.record(notification(ME, "inspection-outbox:1"))).isFalse();
        assertThat(service.record(notification(ME, "inspection-outbox:2"))).isTrue();

        assertThat(store.messages).hasSize(2);
    }

    @Test
    void rejectsAnIncompleteOrOversizedNotification() {
        NewNotification valid = notification(ME, "k");
        List<NewNotification> invalid = List.of(
                copy(valid, null, valid.title(), valid.body(), valid.sourceKey(), TARGET),
                copy(valid, ME, " ", valid.body(), valid.sourceKey(), TARGET),
                copy(valid, ME, "题".repeat(81), valid.body(), valid.sourceKey(), TARGET),
                copy(valid, ME, valid.title(), "文".repeat(301), valid.sourceKey(), TARGET),
                copy(valid, ME, valid.title(), valid.body(), "", TARGET),
                copy(valid, ME, valid.title(), valid.body(), "k".repeat(121), TARGET),
                copy(valid, ME, valid.title(), valid.body(), valid.sourceKey(), null));

        for (NewNotification candidate : invalid) {
            assertThatThrownBy(() -> service.record(candidate)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.record(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.messages).isEmpty();
    }

    @Test
    void listsOnlyTheRecipientsNotificationsNewestFirstWithTheUnreadCount() {
        service.record(notification(ME, "a"));
        service.record(notification(SOMEONE_ELSE, "b"));
        service.record(notification(ME, "c"));

        NotificationPageView page = service.list(ME, 1, 1);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.unreadCount()).isEqualTo(2);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("巡检报告已交付");
            assertThat(item.target()).isEqualTo(
                    new NotificationTargetView(NotificationTargetType.INSPECTION_DELIVERY, TARGET, null));
            assertThat(item.read()).isFalse();
        });
        assertThat(service.list(SOMEONE_ELSE, 1, 20).total()).isEqualTo(1);
    }

    @Test
    void readingIsPerRecipientAndRepeatable() {
        service.record(notification(ME, "a"));
        UUID id = store.messages.get(0).id;

        // 别人标不了，也当作不存在。
        assertThat(service.markRead(SOMEONE_ELSE, id)).isFalse();
        assertThat(service.unreadCount(ME).count()).isEqualTo(1);

        assertThat(service.markRead(ME, id)).isTrue();
        assertThat(service.markRead(ME, id)).isTrue();
        assertThat(store.messages.get(0).readAt).isEqualTo(NOW);
        assertThat(service.unreadCount(ME).count()).isZero();
        assertThat(service.markRead(ME, UUID.randomUUID())).isFalse();
    }

    @Test
    void markAllReadTouchesOnlyTheRecipientsUnreadNotifications() {
        service.record(notification(ME, "a"));
        service.record(notification(ME, "b"));
        service.record(notification(SOMEONE_ELSE, "c"));

        assertThat(service.markAllRead(ME).updated()).isEqualTo(2);
        assertThat(service.markAllRead(ME).updated()).isZero();
        assertThat(service.unreadCount(SOMEONE_ELSE).count()).isEqualTo(1);
    }

    // G1：客服消息一个会话最多一条未读；读过之后的新回复再记一条。
    @Test
    void customerServiceKeepsAtMostOneUnreadNotificationPerConversation() {
        assertThat(service.recordUnlessUnread(reply(ME, "support-message:1", NOW.minusSeconds(60), "商家回复了你")))
                .isTrue();
        assertThat(service.recordUnlessUnread(reply(ME, "support-message:2", NOW, "服务商回复了你"))).isFalse();
        // 别的会话、别的收件人各记各的。
        assertThat(service.recordUnlessUnread(reply(SOMEONE_ELSE, "support-message:3", NOW, "商家回复了你"))).isTrue();

        List<Stored> mine = store.messages.stream().filter(stored -> stored.notification.recipientId().equals(ME)).toList();
        assertThat(mine).singleElement().satisfies(stored -> {
            assertThat(stored.notification.title()).isEqualTo("服务商回复了你");
            assertThat(stored.notification.occurredAt()).isEqualTo(NOW);
        });
        // 时间只往后挪。
        service.recordUnlessUnread(reply(ME, "support-message:4", NOW.minusSeconds(600), "商家回复了你"));
        assertThat(mine.get(0).notification.occurredAt()).isEqualTo(NOW);

        assertThat(service.markTargetRead(ME, NotificationCategory.CUSTOMER_SERVICE, NotificationTargetType.CONVERSATION,
                TARGET)).isEqualTo(1);
        assertThat(service.unreadCount(ME).count()).isZero();
        assertThat(service.unreadCount(SOMEONE_ELSE).count()).isEqualTo(1);
        assertThat(service.recordUnlessUnread(reply(ME, "support-message:5", NOW, "商家回复了你"))).isTrue();
        assertThat(service.unreadCount(ME).count()).isEqualTo(1);
        // 客服消息只能走这条路。
        assertThatThrownBy(() -> service.record(reply(ME, "support-message:6", NOW, "商家回复了你")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.markTargetRead(ME, null, NotificationTargetType.CONVERSATION, TARGET))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.markTargetRead(null, NotificationCategory.CUSTOMER_SERVICE,
                NotificationTargetType.CONVERSATION, TARGET)).isInstanceOf(IllegalArgumentException.class);
    }

    // S5-9：新记一条才交给推送；同一事件重放、客服消息只刷新未读都不再推。
    @Test
    void onlyANewlyRecordedNotificationIsHandedToThePush() {
        service.record(notification(ME, "a"));
        service.record(notification(ME, "a"));
        service.recordUnlessUnread(reply(ME, "support-message:1", NOW, "商家回复了你"));
        service.recordUnlessUnread(reply(ME, "support-message:2", NOW, "商家回复了你"));

        assertThat(pushed).extracting(NewNotification::sourceKey).containsExactly("a", "support-message:1");
    }

    // 系统通知只由公告补记，业务不能直接写。
    @Test
    void systemNoticesCannotBeRecordedDirectly() {
        NewNotification notice = new NewNotification(ME, NotificationCategory.SYSTEM_NOTICE, "SYSTEM_NOTICE_PUBLISHED",
                "隐私政策更新摘要", "查看主要变化与生效日期", NotificationTargetType.SYSTEM_NOTICE, TARGET, null, "x", NOW);

        assertThatThrownBy(() -> service.record(notice)).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.messages).isEmpty();
        assertThat(pushed).isEmpty();
    }

    // 开通了系统通知的构建读列表和未读数之前先补记，带上 App 的当前城市；没开通的不补记；城市格式不对是参数错误。
    @Test
    void listingAndCountingDeliverSystemNoticesOnlyWhenAsked() {
        service.list(ME, 1, 20, true, "370500");
        service.unreadCount(ME, true, "370500");
        service.list(ME, 1, 20, true, null);
        service.list(ME, 1, 20);
        service.unreadCount(ME);
        service.unreadCount(ME, false, "370500");

        assertThat(store.deliveries).containsExactly(ME + "@370500", ME + "@370500", ME + "@null");
        assertThatThrownBy(() -> service.list(ME, 1, 20, true, "dongying"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.unreadCount(ME, false, "37 05")).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.deliveries).hasSize(3);
    }

    @Test
    void rejectsInvalidReadArguments() {
        assertThatThrownBy(() -> service.list(null, 1, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(ME, 0, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(ME, 1, 51)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.unreadCount(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.markRead(ME, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.markAllRead(null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static NewNotification notification(UUID recipient, String sourceKey) {
        return new NewNotification(
                recipient,
                NotificationCategory.SERVICE_PROGRESS,
                "INSPECTION_DELIVERY_PUBLISHED",
                "巡检报告已交付",
                "光伏电站巡检服务第 1 次报告已交付，请核对后验收。",
                NotificationTargetType.INSPECTION_DELIVERY,
                TARGET,
                null,
                sourceKey,
                NOW.minusSeconds(60));
    }

    private static NewNotification reply(UUID recipient, String sourceKey, Instant at, String title) {
        return new NewNotification(recipient, NotificationCategory.CUSTOMER_SERVICE, "SUPPORT_REPLIED", title,
                "示例机器人商家回复了你的消息，点开查看。", NotificationTargetType.CONVERSATION, TARGET, null, sourceKey, at);
    }

    private static NewNotification copy(
            NewNotification base, UUID recipient, String title, String body, String sourceKey, UUID target
    ) {
        return new NewNotification(
                recipient, base.category(), base.eventCode(), title, body, base.targetType(), target, null,
                sourceKey, base.occurredAt());
    }

    private static final class Stored {
        final UUID id = UUID.randomUUID();
        NewNotification notification;
        final int order;
        Instant readAt;

        Stored(NewNotification notification, int order) {
            this.notification = notification;
            this.order = order;
        }
    }

    private static final class FakeStore implements NotificationStore {
        final List<Stored> messages = new ArrayList<>();
        final List<String> deliveries = new ArrayList<>();

        @Override
        public int deliverSystemNotices(UUID recipientId, String cityCode, Instant now) {
            deliveries.add(recipientId + "@" + cityCode);
            return 0;
        }

        @Override
        public boolean insert(NewNotification notification) {
            if (messages.stream().anyMatch(stored -> stored.notification.sourceKey().equals(notification.sourceKey()))) {
                return false;
            }
            messages.add(new Stored(notification, messages.size()));
            return true;
        }

        @Override
        public boolean insertUnlessUnread(NewNotification notification) {
            if (unread(notification).isPresent()) {
                return false;
            }
            return insert(notification);
        }

        @Override
        public int refreshUnread(NewNotification notification) {
            return unread(notification).map(stored -> {
                Instant at = notification.occurredAt().isAfter(stored.notification.occurredAt())
                        ? notification.occurredAt() : stored.notification.occurredAt();
                stored.notification = new NewNotification(stored.notification.recipientId(),
                        stored.notification.category(), notification.eventCode(), notification.title(),
                        notification.body(), stored.notification.targetType(), stored.notification.targetId(),
                        stored.notification.targetSubId(), stored.notification.sourceKey(), at);
                return 1;
            }).orElse(0);
        }

        @Override
        public int markTargetRead(UUID recipientId, NotificationCategory category, NotificationTargetType targetType,
                                  UUID targetId, Instant at) {
            int updated = 0;
            for (Stored stored : of(recipientId)) {
                if (stored.readAt == null && stored.notification.category() == category
                        && stored.notification.targetType() == targetType
                        && stored.notification.targetId().equals(targetId)) {
                    stored.readAt = at;
                    updated++;
                }
            }
            return updated;
        }

        private java.util.Optional<Stored> unread(NewNotification notification) {
            return of(notification.recipientId()).stream()
                    .filter(stored -> stored.readAt == null
                            && stored.notification.category() == notification.category()
                            && stored.notification.targetType() == notification.targetType()
                            && stored.notification.targetId().equals(notification.targetId()))
                    .findFirst();
        }

        @Override
        public long count(UUID recipientId) {
            return of(recipientId).size();
        }

        @Override
        public long countUnread(UUID recipientId) {
            return of(recipientId).stream().filter(stored -> stored.readAt == null).count();
        }

        @Override
        public List<MessageRow> findPage(UUID recipientId, int limit, int offset) {
            return of(recipientId).stream()
                    .sorted((left, right) -> Integer.compare(right.order, left.order))
                    .skip(offset)
                    .limit(limit)
                    .map(stored -> new MessageRow(
                            stored.id,
                            stored.notification.category().name(),
                            stored.notification.eventCode(),
                            stored.notification.title(),
                            stored.notification.body(),
                            stored.notification.targetType().name(),
                            stored.notification.targetId(),
                            stored.notification.targetSubId(),
                            stored.notification.occurredAt(),
                            stored.readAt))
                    .toList();
        }

        @Override
        public boolean exists(UUID recipientId, UUID id) {
            return of(recipientId).stream().anyMatch(stored -> stored.id.equals(id));
        }

        @Override
        public int markRead(UUID recipientId, UUID id, Instant at) {
            int updated = 0;
            for (Stored stored : of(recipientId)) {
                if (stored.id.equals(id) && stored.readAt == null) {
                    stored.readAt = at;
                    updated++;
                }
            }
            return updated;
        }

        @Override
        public int markAllRead(UUID recipientId, Instant at) {
            int updated = 0;
            for (Stored stored : of(recipientId)) {
                if (stored.readAt == null) {
                    stored.readAt = at;
                    updated++;
                }
            }
            return updated;
        }

        private List<Stored> of(UUID recipientId) {
            return messages.stream()
                    .filter(stored -> stored.notification.recipientId().equals(recipientId))
                    .toList();
        }
    }
}
