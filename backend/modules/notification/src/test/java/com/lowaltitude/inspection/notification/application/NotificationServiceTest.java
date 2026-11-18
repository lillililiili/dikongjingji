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
    private final NotificationService service = new NotificationService(store, Clock.fixed(NOW, ZoneOffset.UTC));

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

    private static NewNotification copy(
            NewNotification base, UUID recipient, String title, String body, String sourceKey, UUID target
    ) {
        return new NewNotification(
                recipient, base.category(), base.eventCode(), title, body, base.targetType(), target, null,
                sourceKey, base.occurredAt());
    }

    private static final class Stored {
        final UUID id = UUID.randomUUID();
        final NewNotification notification;
        final int order;
        Instant readAt;

        Stored(NewNotification notification, int order) {
            this.notification = notification;
            this.order = order;
        }
    }

    private static final class FakeStore implements NotificationStore {
        final List<Stored> messages = new ArrayList<>();

        @Override
        public boolean insert(NewNotification notification) {
            if (messages.stream().anyMatch(stored -> stored.notification.sourceKey().equals(notification.sourceKey()))) {
                return false;
            }
            messages.add(new Stored(notification, messages.size()));
            return true;
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
