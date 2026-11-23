package com.lowaltitude.inspection.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowaltitude.inspection.notification.application.NotificationRows.SystemNoticeRow;
import com.lowaltitude.inspection.notification.application.PushGateway.PushMessage;
import com.lowaltitude.inspection.notification.application.SystemNotices.Action;
import com.lowaltitude.inspection.notification.application.SystemNotices.AgreementCode;
import com.lowaltitude.inspection.notification.application.SystemNotices.AudienceScope;
import com.lowaltitude.inspection.notification.application.SystemNotices.Content;
import com.lowaltitude.inspection.notification.application.SystemNotices.Draft;
import com.lowaltitude.inspection.notification.application.SystemNotices.Fact;
import com.lowaltitude.inspection.notification.application.SystemNotices.Item;
import com.lowaltitude.inspection.notification.application.SystemNotices.Link;
import com.lowaltitude.inspection.notification.application.SystemNotices.LinkType;
import com.lowaltitude.inspection.notification.application.SystemNotices.Status;
import com.lowaltitude.inspection.notification.application.SystemNotices.SystemNoticeView;
import org.junit.jupiter.api.Test;

/** S5-9：发布（校验、幂等、推送）、撤回、公开详情和“协议有更新”。 */
class SystemNoticeServiceTest {

    private static final UUID OPERATOR = UUID.fromString("90000000-0000-4000-8000-0000000000b1");
    private static final Instant NOW = Instant.parse("2026-11-22T04:00:00Z");

    private final FakeNoticeStore store = new FakeNoticeStore();
    private final FakePushStore devices = new FakePushStore();
    private final List<PushMessage> pushed = new ArrayList<>();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final SystemNoticeService service = new SystemNoticeService(store,
            new PushDispatcher(pushed::add, devices, clock), new ObjectMapper(), clock);

    @Test
    void publishesOncePerKeyAndPushesOnlyTheFirstTime() {
        devices.registerDevice(UUID.randomUUID(), "ANDROID", "cid-1", null, NOW);

        SystemNoticeView first = service.publish(OPERATOR, "k-1", privacyDraft());
        SystemNoticeView again = service.publish(OPERATOR, "k-1", privacyDraft());

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(store.rows).hasSize(1);
        assertThat(pushed).singleElement().satisfies(message -> {
            assertThat(message.title()).isEqualTo("隐私政策更新摘要");
            assertThat(message.body()).isEqualTo("查看主要变化与生效日期");
            assertThat(message.targetId()).isEqualTo(first.id());
        });
        assertThat(first.paragraphs()).containsExactly("我们更新了《隐私政策》。");
        assertThat(first.facts()).containsExactly(new Fact("版本", "v1.1"), new Fact("生效日期", "2026-11-08"));
        assertThat(first.items()).extracting(Item::title).containsExactly("权限用途", "说明");
        assertThat(first.items().get(1).link()).isNull();
        assertThat(first.action()).isEqualTo(new Action("查看完整隐私政策",
                new Link(LinkType.AGREEMENT, AgreementCode.PRIVACY_POLICY)));
        assertThat(first.aboutAgreement()).isEqualTo(AgreementCode.PRIVACY_POLICY);
        assertThat(first.status()).isEqualTo(Status.PUBLISHED);
        assertThat(first.publishedAt()).isEqualTo(NOW);
    }

    @Test
    void aCityNoticeIsPushedToThatCitysDevicesOnly() {
        devices.registerDevice(UUID.randomUUID(), "ANDROID", "cid-dy", "370500", NOW);
        devices.registerDevice(UUID.randomUUID(), "ANDROID", "cid-jn", "370100", NOW);

        SystemNoticeView notice = service.publish(OPERATOR, "k-city", draft(AudienceScope.CITY, "370500", null));

        assertThat(notice.audience().cityCode()).isEqualTo("370500");
        assertThat(pushed).extracting(PushMessage::clientId).containsExactly("cid-dy");
    }

    @Test
    void rejectsAMalformedDraft() {
        List<Draft> invalid = List.of(
                draft(AudienceScope.CITY, null, null),
                draft(AudienceScope.CITY, "dongying", null),
                draft(AudienceScope.ALL, "370500", null),
                withContent(new Content(List.of(), List.of(), List.of(), null, null)),
                withContent(new Content(List.of("段".repeat(501)), List.of(), List.of(), null, null)),
                withContent(new Content(List.of("段"), List.of(new Fact("版本号很长很长很长", "v1")), List.of(), null, null)),
                withContent(new Content(List.of("段"), List.of(), List.of(
                        new Item("变化", "说明", new Link(LinkType.AGREEMENT, null))), null, null)),
                withContent(new Content(List.of("段"), List.of(), List.of(
                        new Item("变化", "说明", new Link(LinkType.PRIVACY_PERMISSIONS, AgreementCode.USER_AGREEMENT))),
                        null, null)),
                withContent(new Content(List.of("段"), List.of(), List.of(), null, new Action("看看", null))),
                new Draft("", "摘要", privacyDraft().content(), null, AudienceScope.ALL, null, null),
                new Draft("标题", "摘".repeat(121), privacyDraft().content(), null, AudienceScope.ALL, null, null),
                draft(AudienceScope.ALL, null, NOW));

        for (Draft draft : invalid) {
            assertThatThrownBy(() -> service.publish(OPERATOR, "k-" + UUID.randomUUID(), draft))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.publish(OPERATOR, " ", privacyDraft()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.publish(null, "k", privacyDraft())).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.rows).isEmpty();
        assertThat(pushed).isEmpty();
    }

    @Test
    void withdrawingHidesItFromReadersAndInboxesAndIsRepeatable() {
        SystemNoticeView notice = service.publish(OPERATOR, "k-1", privacyDraft());
        assertThat(service.read(notice.id()).title()).isEqualTo("隐私政策更新摘要");
        assertThat(service.latestAbout("PRIVACY_POLICY")).isPresent();

        assertThat(service.withdraw(notice.id()).status()).isEqualTo(Status.WITHDRAWN);
        assertThat(service.withdraw(notice.id()).status()).isEqualTo(Status.WITHDRAWN);

        assertThat(store.hidden).containsExactly(notice.id());
        assertThatThrownBy(() -> service.read(notice.id())).isInstanceOf(NotificationNotFoundException.class);
        assertThat(service.latestAbout("PRIVACY_POLICY")).isEmpty();
        assertThat(service.list(1, 20).items()).singleElement()
                .satisfies(item -> assertThat(item.status()).isEqualTo(Status.WITHDRAWN));
        assertThatThrownBy(() -> service.withdraw(UUID.randomUUID())).isInstanceOf(NotificationNotFoundException.class);
    }

    @Test
    void latestAboutAnAgreementOnlyCountsNoticesForEveryone() {
        service.publish(OPERATOR, "k-city", new Draft("东营站隐私说明", "摘要", privacyDraft().content(),
                AgreementCode.PRIVACY_POLICY, AudienceScope.CITY, "370500", null));
        assertThat(service.latestAbout("PRIVACY_POLICY")).isEmpty();

        SystemNoticeView notice = service.publish(OPERATOR, "k-all", privacyDraft());
        assertThat(service.latestAbout("PRIVACY_POLICY")).hasValueSatisfying(update -> {
            assertThat(update.noticeId()).isEqualTo(notice.id());
            assertThat(update.title()).isEqualTo("隐私政策更新摘要");
        });
        assertThat(service.latestAbout("USER_AGREEMENT")).isEmpty();
        assertThatThrownBy(() -> service.latestAbout("privacy")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listsNewestFirstWithPaging() {
        service.publish(OPERATOR, "k-1", privacyDraft());
        service.publish(OPERATOR, "k-2", draft(AudienceScope.ALL, null, null));

        assertThat(service.list(1, 1).hasMore()).isTrue();
        assertThat(service.list(1, 1).total()).isEqualTo(2);
        assertThatThrownBy(() -> service.list(0, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(1, 51)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Draft privacyDraft() {
        return new Draft(
                "隐私政策更新摘要",
                "查看主要变化与生效日期",
                new Content(
                        List.of("我们更新了《隐私政策》。"),
                        List.of(new Fact("版本", "v1.1"), new Fact("生效日期", "2026-11-08")),
                        List.of(new Item("权限用途", "逐项说明定位、相机、通知用在哪里",
                                        new Link(LinkType.PRIVACY_PERMISSIONS, null)),
                                new Item("说明", "没有链接的一条", null)),
                        "生效前可以阅读全文。",
                        new Action("查看完整隐私政策", new Link(LinkType.AGREEMENT, AgreementCode.PRIVACY_POLICY))),
                AgreementCode.PRIVACY_POLICY,
                AudienceScope.ALL,
                null,
                null);
    }

    private static Draft draft(AudienceScope scope, String cityCode, Instant expiresAt) {
        return new Draft("东营站服务时间调整", "11 月 25 日起客服时间调整", new Content(List.of("客服时间调整为 9:00–21:00。"),
                null, null, null, null), null, scope, cityCode, expiresAt);
    }

    private static Draft withContent(Content content) {
        return new Draft("标题", "摘要", content, null, AudienceScope.ALL, null, null);
    }

    private static final class FakeNoticeStore implements SystemNoticeStore {
        final List<SystemNoticeRow> rows = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
        final List<UUID> hidden = new ArrayList<>();

        @Override
        public boolean insert(UUID id, String title, String summary, String content, String aboutAgreement,
                              String audienceScope, String cityCode, UUID publishedBy, Instant publishedAt,
                              Instant expiresAt, String publishKey) {
            if (keys.contains(publishKey)) {
                return false;
            }
            keys.add(publishKey);
            rows.add(new SystemNoticeRow(id, title, summary, content, aboutAgreement, audienceScope, cityCode,
                    publishedAt.plusMillis(rows.size()), expiresAt, "PUBLISHED", null));
            return true;
        }

        @Override
        public Optional<SystemNoticeRow> findByPublishKey(String publishKey) {
            int index = keys.indexOf(publishKey);
            return index < 0 ? Optional.empty() : Optional.of(rows.get(index));
        }

        @Override
        public Optional<SystemNoticeRow> find(UUID id) {
            return rows.stream().filter(row -> row.id().equals(id)).findFirst();
        }

        @Override
        public Optional<SystemNoticeRow> latestAbout(String agreement, Instant now) {
            return rows.stream()
                    .filter(row -> agreement.equals(row.aboutAgreement()) && "PUBLISHED".equals(row.status())
                            && "ALL".equals(row.audienceScope()))
                    .max(Comparator.comparing(SystemNoticeRow::publishedAt));
        }

        @Override
        public List<SystemNoticeRow> page(int limit, int offset) {
            return rows.stream().sorted(Comparator.comparing(SystemNoticeRow::publishedAt).reversed())
                    .skip(offset).limit(limit).toList();
        }

        @Override
        public long count() {
            return rows.size();
        }

        @Override
        public int withdraw(UUID id, Instant at) {
            for (int i = 0; i < rows.size(); i++) {
                SystemNoticeRow row = rows.get(i);
                if (row.id().equals(id) && "PUBLISHED".equals(row.status())) {
                    rows.set(i, new SystemNoticeRow(row.id(), row.title(), row.summary(), row.content(),
                            row.aboutAgreement(), row.audienceScope(), row.cityCode(), row.publishedAt(),
                            row.expiresAt(), "WITHDRAWN", at));
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public int hideDelivered(UUID id, Instant at) {
            hidden.add(id);
            return 1;
        }
    }
}
