package com.lowaltitude.inspection.support.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import com.lowaltitude.inspection.support.application.SupportException.Error;
import com.lowaltitude.inspection.support.infrastructure.persistence.SupportMapper;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * 客服会话和反馈的规则测试（G1，§26.1）。mapper 换成内存实现（按 SupportMapper 的 SQL 条件和 V001 的约束写），
 * 机构、显示名、站内信换成替身。时钟可以往前拨。SQL 文本由 SupportMapperContractTest 检查，真实库上由 infra/scripts/trade-smoke/support.cjs 验证。
 */
class SupportServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-19T02:00:00Z");
    private static final UUID CUSTOMER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_CUSTOMER = UUID.fromString("11111111-1111-1111-1111-222222222222");
    private static final UUID ROBOT = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID EAGLE = UUID.fromString("f2000000-0000-4000-8000-000000000001");
    private static final UUID CUSTOMER_ORG = UUID.fromString("11111111-1111-1111-1111-aaaaaaaaaaaa");
    private static final UUID DISABLED = UUID.fromString("c1000000-0000-4000-8000-0000000000ff");
    private static final UUID ROBOT_OPERATOR = UUID.fromString("c1000000-0000-4000-8000-0000000000a1");
    private static final UUID EAGLE_OPERATOR = UUID.fromString("f2000000-0000-4000-8000-0000000000a1");
    private static final UUID SUPPORT_AGENT = UUID.fromString("90000000-0000-4000-8000-0000000000a1");
    private static final UUID ORDER = UUID.fromString("e3000000-0000-4000-8000-000000000001");

    private final InMemoryMapper mapper = new InMemoryMapper();
    private final Map<UUID, SupportPorts.Organization> orgs = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final List<String> notices = new ArrayList<>();
    private Instant now = NOW;
    private final SupportService service = new SupportService(mapper, id -> Optional.ofNullable(orgs.get(id)),
            id -> Optional.ofNullable(names.get(id)), new SupportPorts.Notifier() {
                @Override
                public void replied(UUID customerId, UUID conversationId, String kind, String name, UUID messageId,
                                    Instant at) {
                    notices.add("replied " + customerId + " " + conversationId + " " + kind + " " + name + " " + at);
                }

                @Override
                public void read(UUID customerId, UUID conversationId) {
                    notices.add("read " + customerId + " " + conversationId);
                }
            }, new Clock() {
                @Override
                public java.time.ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(java.time.ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return now;
                }
            });

    {
        orgs.put(ROBOT, new SupportPorts.Organization(ROBOT, "示例机器人商家", "PROVIDER"));
        orgs.put(EAGLE, new SupportPorts.Organization(EAGLE, "鹰眼智能巡检", "PROVIDER"));
        orgs.put(CUSTOMER_ORG, new SupportPorts.Organization(CUSTOMER_ORG, "东营示例物流公司", "CUSTOMER"));
        names.put(CUSTOMER, "王女士");
        names.put(OTHER_CUSTOMER, "李先生");
    }

    // ---- 打开会话 ----

    @Test
    void aCustomerHasOneConversationPerParty() {
        SupportConversationOpened merchant = service.open(CUSTOMER, "MERCHANT", ROBOT);
        SupportConversationOpened platform = service.open(CUSTOMER, "platform", null);

        assertThat(merchant.created()).isTrue();
        assertThat(merchant.conversation().counterpart())
                .isEqualTo(new SupportCounterpartView(SupportCounterpartKind.MERCHANT, ROBOT, "示例机器人商家"));
        assertThat(merchant.conversation().lastMessage()).isNull();
        assertThat(merchant.conversation().unreadCount()).isZero();
        assertThat(platform.conversation().counterpart())
                .isEqualTo(new SupportCounterpartView(SupportCounterpartKind.PLATFORM, null, "平台客服"));
        // 再开一次是同一个；同一家机构按服务商再开是另一个。
        SupportConversationOpened again = service.open(CUSTOMER, "MERCHANT", ROBOT);
        assertThat(again.created()).isFalse();
        assertThat(again.conversation().conversationId()).isEqualTo(merchant.conversation().conversationId());
        assertThat(service.open(CUSTOMER, "PLATFORM", null).conversation().conversationId())
                .isEqualTo(platform.conversation().conversationId());
        assertThat(service.open(CUSTOMER, "PROVIDER", ROBOT).created()).isTrue();
        assertThat(service.open(OTHER_CUSTOMER, "MERCHANT", ROBOT).created()).isTrue();
        assertThat(mapper.conversations).hasSize(4);
        assertThat(mapper.conversations.getFirst().customerName).isEqualTo("王女士");
    }

    @Test
    void theCounterpartMustBeAnActiveProviderOrganizationAndThePlatformHasNone() {
        assertRejected(Error.COUNTERPART_UNAVAILABLE, () -> service.open(CUSTOMER, "MERCHANT", DISABLED));
        assertRejected(Error.COUNTERPART_UNAVAILABLE, () -> service.open(CUSTOMER, "PROVIDER", CUSTOMER_ORG));
        assertInvalid(() -> service.open(CUSTOMER, "MERCHANT", null));
        assertInvalid(() -> service.open(CUSTOMER, "PLATFORM", ROBOT));
        assertInvalid(() -> service.open(CUSTOMER, "SHOP", ROBOT));
        assertInvalid(() -> service.open(CUSTOMER, null, ROBOT));
        assertInvalid(() -> service.open(null, "PLATFORM", null));
        // 没有登录资料（账号停用）：当作无权限。
        names.remove(CUSTOMER);
        assertRejected(Error.FORBIDDEN, () -> service.open(CUSTOMER, "PLATFORM", null));
        assertThat(mapper.conversations).isEmpty();
    }

    // ---- 发消息 ----

    @Test
    void theCustomerSendsTextAndCardsAndTheCounterpartSeesAnUnreadCount() {
        UUID id = service.open(CUSTOMER, "MERCHANT", ROBOT).conversation().conversationId();

        SupportMessageSent card = service.send(CUSTOMER, id, UUID.randomUUID(), new SupportMessageDraft(null,
                new SupportCardDraft("mall_order", ORDER, " 编程机器人探索套装 ", "已发货 · 实付 ¥1,299")));
        now = NOW.plusSeconds(60);
        SupportMessageSent text = service.send(CUSTOMER, id, UUID.randomUUID(),
                new SupportMessageDraft("  物流到哪了？\n今天能到吗  ", null));

        assertThat(card.created()).isTrue();
        assertThat(card.message().seq()).isEqualTo(1);
        assertThat(card.message().kind()).isEqualTo(SupportMessageKind.CARD);
        assertThat(card.message().card())
                .isEqualTo(new SupportCardView(SupportCardType.MALL_ORDER, ORDER, "编程机器人探索套装", "已发货 · 实付 ¥1,299"));
        assertThat(card.message().text()).isNull();
        assertThat(card.message().mine()).isTrue();
        assertThat(text.message().seq()).isEqualTo(2);
        assertThat(text.message().text()).isEqualTo("物流到哪了？\n今天能到吗");
        assertThat(text.message().senderSide()).isEqualTo(SupportSenderSide.CUSTOMER);

        SupportDeskConversationPageView desk = service.deskConversations(SupportDesk.organization(ROBOT), 1, 20);
        assertThat(desk.unreadCount()).isEqualTo(2);
        assertThat(desk.items()).singleElement().satisfies(item -> {
            assertThat(item.customer()).isEqualTo(new SupportCustomerView("王女士"));
            assertThat(item.unreadCount()).isEqualTo(2);
            assertThat(item.lastMessage()).isEqualTo(new SupportLastMessageView(SupportSenderSide.CUSTOMER,
                    SupportMessageKind.TEXT, "物流到哪了？ 今天能到吗", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)));
        });
        // 客户自己发消息不记站内信，只把自己这边算读过。
        assertThat(notices).containsExactly("read " + CUSTOMER + " " + id, "read " + CUSTOMER + " " + id);
    }

    @Test
    void messagesAreValidated() {
        UUID id = service.open(CUSTOMER, "PLATFORM", null).conversation().conversationId();
        List<SupportMessageDraft> invalid = List.of(
                new SupportMessageDraft(null, null),
                new SupportMessageDraft("在吗", new SupportCardDraft("PRODUCT", ORDER, "商品", null)),
                new SupportMessageDraft("   ", null),
                new SupportMessageDraft("字".repeat(501), null),
                new SupportMessageDraft("响铃\u0007", null),
                new SupportMessageDraft(null, new SupportCardDraft("COUPON", ORDER, "优惠券", null)),
                new SupportMessageDraft(null, new SupportCardDraft("PRODUCT", null, "商品", null)),
                new SupportMessageDraft(null, new SupportCardDraft("PRODUCT", ORDER, " ", null)),
                new SupportMessageDraft(null, new SupportCardDraft("PRODUCT", ORDER, "题".repeat(61), null)),
                new SupportMessageDraft(null, new SupportCardDraft("PRODUCT", ORDER, "两行\n标题", null)),
                new SupportMessageDraft(null, new SupportCardDraft("PRODUCT", ORDER, "商品", "副".repeat(61))));
        for (SupportMessageDraft draft : invalid) {
            assertInvalid(() -> service.send(CUSTOMER, id, UUID.randomUUID(), draft));
        }
        assertInvalid(() -> service.send(CUSTOMER, id, null, new SupportMessageDraft("在吗", null)));
        // 500 字（按字算，不按 UTF-16）正好可以；空白的副标题当没有。
        assertThat(service.send(CUSTOMER, id, UUID.randomUUID(), new SupportMessageDraft("𠀀".repeat(500), null))
                .message().seq()).isEqualTo(1);
        assertThat(service.send(CUSTOMER, id, UUID.randomUUID(),
                new SupportMessageDraft(null, new SupportCardDraft("PRODUCT", ORDER, "商品", " "))).message().card()
                .subtitle()).isNull();
        assertThat(mapper.messages).hasSize(2);
    }

    @Test
    void theSameKeyReplaysTheSameMessageAndAnotherContentIsRefused() {
        UUID id = service.open(CUSTOMER, "PLATFORM", null).conversation().conversationId();
        UUID other = service.open(CUSTOMER, "MERCHANT", ROBOT).conversation().conversationId();
        UUID key = UUID.randomUUID();

        SupportMessageSent first = service.send(CUSTOMER, id, key, new SupportMessageDraft("在吗", null));
        SupportMessageSent replay = service.send(CUSTOMER, id, key, new SupportMessageDraft(" 在吗 ", null));

        assertThat(replay.created()).isFalse();
        assertThat(replay.message()).isEqualTo(first.message());
        assertRejected(Error.IDEMPOTENCY_CONFLICT, () -> service.send(CUSTOMER, id, key,
                new SupportMessageDraft("你好", null)));
        assertRejected(Error.IDEMPOTENCY_CONFLICT, () -> service.send(CUSTOMER, other, key,
                new SupportMessageDraft("在吗", null)));
        // 幂等键按发送人：别人用同一个键不相干。
        UUID desk = service.deskSend(SupportDesk.platformSupport(), SUPPORT_AGENT, id, key,
                new SupportMessageDraft("在的", null)).message().messageId();
        assertThat(desk).isNotEqualTo(first.message().messageId());
        assertThat(mapper.messages).hasSize(2);
        assertThat(mapper.conversation(id).messageCount).isEqualTo(2);
    }

    @Test
    void aCustomerOnlyReachesOwnConversations() {
        UUID id = service.open(CUSTOMER, "MERCHANT", ROBOT).conversation().conversationId();

        assertRejected(Error.NOT_FOUND, () -> service.send(OTHER_CUSTOMER, id, UUID.randomUUID(),
                new SupportMessageDraft("在吗", null)));
        assertRejected(Error.NOT_FOUND, () -> service.messages(OTHER_CUSTOMER, id, null, null));
        assertRejected(Error.NOT_FOUND, () -> service.read(OTHER_CUSTOMER, id));
        assertRejected(Error.NOT_FOUND, () -> service.messages(CUSTOMER, UUID.randomUUID(), null, null));
        assertThat(mapper.messages).isEmpty();
    }

    // ---- 回复方 ----

    @Test
    void organizationsSeeTheirOwnConversationsAndThePlatformSeesThePlatformOnes() {
        UUID robot = service.open(CUSTOMER, "MERCHANT", ROBOT).conversation().conversationId();
        UUID eagle = service.open(CUSTOMER, "PROVIDER", EAGLE).conversation().conversationId();
        UUID platform = service.open(CUSTOMER, "PLATFORM", null).conversation().conversationId();
        UUID silent = service.open(OTHER_CUSTOMER, "MERCHANT", ROBOT).conversation().conversationId();
        for (UUID id : List.of(robot, eagle, platform)) {
            service.send(CUSTOMER, id, UUID.randomUUID(), new SupportMessageDraft("在吗", null));
        }

        // 没发过消息的会话不列出来，但能按 ID 打开。
        assertThat(service.deskConversations(SupportDesk.organization(ROBOT), 1, 20).items())
                .extracting(SupportDeskConversationView::conversationId).containsExactly(robot);
        assertThat(service.deskMessages(SupportDesk.organization(ROBOT), silent, null, null).items()).isEmpty();
        assertThat(service.deskConversations(SupportDesk.organization(EAGLE), 1, 20).items())
                .extracting(SupportDeskConversationView::conversationId).containsExactly(eagle);
        assertThat(service.deskConversations(SupportDesk.platformSupport(), 1, 20).items())
                .extracting(SupportDeskConversationView::conversationId, SupportDeskConversationView::counterpartKind)
                .containsExactly(tuple(platform, SupportCounterpartKind.PLATFORM));
        for (UUID other : List.of(eagle, platform)) {
            assertRejected(Error.NOT_FOUND, () -> service.deskMessages(SupportDesk.organization(ROBOT), other, null, null));
            assertRejected(Error.NOT_FOUND, () -> service.deskSend(SupportDesk.organization(ROBOT), ROBOT_OPERATOR, other,
                    UUID.randomUUID(), new SupportMessageDraft("你好", null)));
            assertRejected(Error.NOT_FOUND, () -> service.deskRead(SupportDesk.organization(ROBOT), other));
        }
        assertRejected(Error.NOT_FOUND, () -> service.deskMessages(SupportDesk.platformSupport(), robot, null, null));
        assertThat(service.conversations(OTHER_CUSTOMER, 1, 20).total()).isZero();
        assertInvalid(() -> new SupportDesk(true, ROBOT));
        assertInvalid(() -> SupportDesk.organization(null));
        assertInvalid(() -> service.deskConversations(null, 1, 20));
    }

    @Test
    void aReplyRaisesTheCustomersUnreadAndNotifiesThenReadingClearsIt() {
        UUID id = service.open(CUSTOMER, "PROVIDER", EAGLE).conversation().conversationId();
        service.send(CUSTOMER, id, UUID.randomUUID(), new SupportMessageDraft("第 2 次巡检什么时候？", null));
        notices.clear();
        now = NOW.plusSeconds(300);

        SupportMessageSent reply = service.deskSend(SupportDesk.organization(EAGLE), EAGLE_OPERATOR, id,
                UUID.randomUUID(), new SupportMessageDraft("11/16，具体时间会提前发到“消息”。", null));
        service.deskSend(SupportDesk.organization(EAGLE), EAGLE_OPERATOR, id, UUID.randomUUID(),
                new SupportMessageDraft(null, new SupportCardDraft("INSPECTION_ORDER", ORDER, "物流园区季度安防巡检", null)));

        assertThat(reply.message().senderSide()).isEqualTo(SupportSenderSide.COUNTERPART);
        assertThat(reply.message().mine()).isTrue();
        assertThat(notices).containsExactly(
                "replied " + CUSTOMER + " " + id + " PROVIDER 鹰眼智能巡检 " + now,
                "replied " + CUSTOMER + " " + id + " PROVIDER 鹰眼智能巡检 " + now);
        SupportConversationPageView mine = service.conversations(CUSTOMER, 1, 20);
        assertThat(mine.unreadCount()).isEqualTo(2);
        assertThat(mine.items()).singleElement().satisfies(item -> {
            assertThat(item.unreadCount()).isEqualTo(2);
            assertThat(item.lastMessage().preview()).isEqualTo("物流园区季度安防巡检");
            assertThat(item.lastMessage().senderSide()).isEqualTo(SupportSenderSide.COUNTERPART);
        });
        // 回复方这边回复即读过。
        assertThat(service.deskConversations(SupportDesk.organization(EAGLE), 1, 20).unreadCount()).isZero();

        SupportConversationView read = service.read(CUSTOMER, id);
        assertThat(read.unreadCount()).isZero();
        assertThat(notices).last().isEqualTo("read " + CUSTOMER + " " + id);
        assertThat(service.conversations(CUSTOMER, 1, 20).unreadCount()).isZero();
        // 客户看到的消息里，对方的不是“我的”。
        assertThat(service.messages(CUSTOMER, id, null, null).items())
                .extracting(SupportMessageView::seq, SupportMessageView::mine)
                .containsExactly(tuple(1, true), tuple(2, false), tuple(3, false));
        // 回复方也能把客户的消息标为读过。
        service.send(CUSTOMER, id, UUID.randomUUID(), new SupportMessageDraft("好的", null));
        assertThat(service.deskRead(SupportDesk.organization(EAGLE), id).unreadCount()).isZero();
    }

    // ---- 翻页 ----

    @Test
    void messagesArePagedBackwardsBySequenceAndReturnedOldestFirst() {
        UUID id = service.open(CUSTOMER, "PLATFORM", null).conversation().conversationId();
        for (int i = 1; i <= 25; i++) {
            now = NOW.plusSeconds(i);
            service.send(CUSTOMER, id, UUID.randomUUID(), new SupportMessageDraft("第 " + i + " 条", null));
        }

        SupportMessagePageView latest = service.messages(CUSTOMER, id, null, null);
        assertThat(latest.items()).extracting(SupportMessageView::seq).containsExactlyElementsOf(range(6, 25));
        assertThat(latest.hasMore()).isTrue();
        assertThat(latest.nextBefore()).isEqualTo(6);
        SupportMessagePageView older = service.messages(CUSTOMER, id, latest.nextBefore(), 10);
        assertThat(older.items()).extracting(SupportMessageView::seq).containsExactlyElementsOf(range(1, 5));
        assertThat(older.hasMore()).isFalse();
        assertThat(older.nextBefore()).isNull();
        assertThat(service.messages(CUSTOMER, id, 6, 5).hasMore()).isFalse();
        assertThat(service.messages(CUSTOMER, id, 7, 5).nextBefore()).isEqualTo(2);
        assertInvalid(() -> service.messages(CUSTOMER, id, 0, null));
        assertInvalid(() -> service.messages(CUSTOMER, id, null, 51));
        assertInvalid(() -> service.messages(CUSTOMER, id, null, 0));
        assertThat(service.deskMessages(SupportDesk.platformSupport(), id, null, 50).items()).hasSize(25)
                .allSatisfy(message -> assertThat(message.mine()).isFalse());
    }

    @Test
    void conversationsAreListedByTheLatestMessage() {
        UUID first = service.open(CUSTOMER, "MERCHANT", ROBOT).conversation().conversationId();
        UUID second = service.open(CUSTOMER, "PROVIDER", EAGLE).conversation().conversationId();
        service.open(CUSTOMER, "PLATFORM", null);
        service.send(CUSTOMER, first, UUID.randomUUID(), new SupportMessageDraft("一", null));
        now = NOW.plusSeconds(10);
        service.send(CUSTOMER, second, UUID.randomUUID(), new SupportMessageDraft("二", null));
        now = NOW.plusSeconds(20);
        service.deskSend(SupportDesk.organization(ROBOT), ROBOT_OPERATOR, first, UUID.randomUUID(),
                new SupportMessageDraft("长".repeat(70) + "\n尾巴", null));

        SupportConversationPageView page = service.conversations(CUSTOMER, 1, 1);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.conversationId()).isEqualTo(first);
            assertThat(item.lastMessage().preview()).isEqualTo("长".repeat(60));
        });
        assertThat(service.conversations(CUSTOMER, 2, 1).items()).extracting(SupportConversationView::conversationId)
                .containsExactly(second);
        assertInvalid(() -> service.conversations(CUSTOMER, 0, 20));
        assertInvalid(() -> service.conversations(CUSTOMER, 1, 51));
        assertThat(SupportService.preview("  a \n\n b ")).isEqualTo("a b");
    }

    // ---- 反馈 ----

    @Test
    void feedbackIsRecordedOncePerKey() {
        UUID key = UUID.randomUUID();

        SupportFeedbackCreated created = service.feedback(CUSTOMER, key, "display", " 订单页金额被挡住了\n在小屏手机上 ");
        SupportFeedbackCreated replay = service.feedback(CUSTOMER, key, "DISPLAY", "订单页金额被挡住了\n在小屏手机上");

        assertThat(created.created()).isTrue();
        assertThat(created.feedback().type()).isEqualTo(SupportFeedbackType.DISPLAY);
        assertThat(created.feedback().text()).isEqualTo("订单页金额被挡住了\n在小屏手机上");
        assertThat(replay.created()).isFalse();
        assertThat(replay.feedback()).isEqualTo(created.feedback());
        assertRejected(Error.IDEMPOTENCY_CONFLICT, () -> service.feedback(CUSTOMER, key, "CONTENT", "别的"));
        assertThat(service.feedback(OTHER_CUSTOMER, key, "SUGGESTION", "加个夜间模式").created()).isTrue();
        assertInvalid(() -> service.feedback(CUSTOMER, UUID.randomUUID(), "BUG", "页面错误"));
        assertInvalid(() -> service.feedback(CUSTOMER, UUID.randomUUID(), "FUNCTION", " "));
        assertInvalid(() -> service.feedback(CUSTOMER, UUID.randomUUID(), "FUNCTION", "字".repeat(201)));
        assertInvalid(() -> service.feedback(CUSTOMER, null, "FUNCTION", "字"));
        assertThat(service.feedback(CUSTOMER, UUID.randomUUID(), "FUNCTION", "字".repeat(200)).created()).isTrue();
        assertThat(mapper.feedback).hasSize(3);
    }

    // ---- 辅助 ----

    private static List<Integer> range(int from, int to) {
        List<Integer> values = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            values.add(i);
        }
        return values;
    }

    // ---- S3-1 举报与运营端处理 ----

    @Test
    void aReportRemembersItsTargetAndIsIdempotent() {
        UUID key = UUID.randomUUID();
        UUID comment = UUID.randomUUID();
        UUID room = UUID.randomUUID();

        SupportFeedbackCreated first = service.report(CUSTOMER, key, "CONTENT", "举报直播弹幕：第 3 条", "LIVE_COMMENT_REPORT",
                comment, room);
        SupportFeedbackCreated again = service.report(CUSTOMER, key, "CONTENT", "举报直播弹幕：第 3 条",
                "LIVE_COMMENT_REPORT", comment, room);

        assertThat(first.created()).isTrue();
        assertThat(again.created()).isFalse();
        assertThat(again.feedback().feedbackId()).isEqualTo(first.feedback().feedbackId());
        OpsFeedbackView view = service.opsFeedback(null, null, 1, 20).items().getFirst();
        assertThat(view.source()).isEqualTo(SupportFeedbackSource.LIVE_COMMENT_REPORT);
        assertThat(view.target()).isEqualTo(new SupportReportTargetView(comment, room));
        assertThat(view.reporter().displayName()).isEqualTo("王女士");
        // 同一个键换了对象：幂等冲突。用户反馈来源和缺对象的举报一律拒绝。
        assertRejected(Error.IDEMPOTENCY_CONFLICT, () -> service.report(CUSTOMER, key, "CONTENT", "举报直播弹幕：第 3 条",
                "LIVE_COMMENT_REPORT", UUID.randomUUID(), room));
        assertInvalid(() -> service.report(CUSTOMER, UUID.randomUUID(), "CONTENT", "x", "USER", comment, room));
        assertInvalid(() -> service.report(CUSTOMER, UUID.randomUUID(), "CONTENT", "x", "VIDEO_COMMENT_REPORT", comment,
                null));
    }

    @Test
    void supportHandlesFeedbackOnceWithANoteAndTheCurrentVersion() {
        UUID feedbackId = service.feedback(CUSTOMER, UUID.randomUUID(), "FUNCTION", "下单页卡住了").feedback().feedbackId();
        now = NOW.plusSeconds(60);
        service.report(OTHER_CUSTOMER, UUID.randomUUID(), "CONTENT", "举报视频评论", "VIDEO_COMMENT_REPORT",
                UUID.randomUUID(), UUID.randomUUID());

        OpsFeedbackPageView pending = service.opsFeedback("PENDING", null, 1, 20);
        assertThat(pending.total()).isEqualTo(2);
        assertThat(pending.pendingCount()).isEqualTo(2);
        // 待处理的先来的在前。
        assertThat(pending.items().getFirst().feedbackId()).isEqualTo(feedbackId);
        assertThat(service.opsFeedback(null, "VIDEO_COMMENT_REPORT", 1, 20).total()).isEqualTo(1);

        assertRejected(Error.VERSION_CONFLICT,
                () -> service.handleFeedback(feedbackId, "已转交研发", 7, SUPPORT_AGENT, "平台客服（示例）"));
        assertInvalid(() -> service.handleFeedback(feedbackId, " ", 0, SUPPORT_AGENT, "平台客服（示例）"));
        assertInvalid(() -> service.handleFeedback(feedbackId, "长".repeat(201), 0, SUPPORT_AGENT, "平台客服（示例）"));

        now = NOW.plusSeconds(120);
        OpsFeedbackView handled = service.handleFeedback(feedbackId, "已转交研发", 0, SUPPORT_AGENT, "平台客服（示例）");
        assertThat(handled.handleStatus()).isEqualTo(SupportFeedbackHandleStatus.HANDLED);
        assertThat(handled.handling()).isEqualTo(new SupportFeedbackHandlingView("已转交研发", "平台客服（示例）",
                OffsetDateTime.ofInstant(NOW.plusSeconds(120), ZoneOffset.UTC)));
        assertThat(handled.version()).isEqualTo(1);
        assertThat(service.opsFeedback(null, null, 1, 20).pendingCount()).isEqualTo(1);
        assertRejected(Error.ALREADY_HANDLED,
                () -> service.handleFeedback(feedbackId, "再处理一次", 1, SUPPORT_AGENT, "平台客服（示例）"));
        assertRejected(Error.NOT_FOUND,
                () -> service.handleFeedback(UUID.randomUUID(), "不存在", 0, SUPPORT_AGENT, "平台客服（示例）"));
        assertInvalid(() -> service.opsFeedback("DONE", null, 1, 20));
        assertInvalid(() -> service.opsFeedback(null, null, 1, 51));
    }

    private static void assertRejected(Error error, ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(SupportException.class,
                exception -> assertThat(exception.error()).isEqualTo(error));
    }

    private static void assertInvalid(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(IllegalArgumentException.class);
    }

    /** 内存里的 mapper：条件按 SupportMapper 的 SQL 写，V001 的约束在这里用异常代替。 */
    private static final class InMemoryMapper implements SupportMapper {

        final List<Conversation> conversations = new ArrayList<>();
        final List<Message> messages = new ArrayList<>();
        final List<FeedbackRow> feedback = new ArrayList<>();
        final Map<UUID, String> feedbackKeys = new HashMap<>();

        Conversation conversation(UUID id) {
            return conversations.stream().filter(c -> c.id.equals(id)).findFirst().orElse(null);
        }

        @Override
        public Integer lockSender(UUID accountId) {
            return 1;
        }

        @Override
        public int insertConversation(UUID customerId, String customerName, String counterpartKind,
                                      UUID counterpartOrgId, String counterpartName, OffsetDateTime at) {
            if ("PLATFORM".equals(counterpartKind) != (counterpartOrgId == null)) {
                throw new IllegalStateException("ck_support_conversation_counterpart");
            }
            if (findParty(customerId, counterpartKind, counterpartOrgId) != null) {
                return 0;
            }
            Conversation c = new Conversation();
            c.id = UUID.randomUUID();
            c.customerId = customerId;
            c.customerName = customerName;
            c.kind = counterpartKind;
            c.orgId = counterpartOrgId;
            c.name = counterpartName;
            c.createdAt = at;
            conversations.add(c);
            return 1;
        }

        private ConversationRow row(Conversation c) {
            Message last = messages.stream().filter(m -> m.conversationId.equals(c.id) && m.seq == c.messageCount)
                    .findFirst().orElse(null);
            return new ConversationRow(c.id, c.customerId, c.customerName, c.kind, c.orgId, c.name, c.messageCount,
                    c.customerUnread, c.counterpartUnread, c.lastMessageAt, c.createdAt, c.version,
                    last == null ? null : last.side, last == null ? null : last.kind,
                    last == null ? null : last.text, last == null ? null : last.cardTitle);
        }

        private ConversationRow find(Predicate<Conversation> where) {
            return conversations.stream().filter(where).findFirst().map(this::row).orElse(null);
        }

        private List<ConversationRow> page(Predicate<Conversation> where, int limit, int offset) {
            return conversations.stream().filter(where).filter(c -> c.messageCount > 0)
                    .sorted(Comparator.comparing((Conversation c) -> c.lastMessageAt).reversed()
                            .thenComparing(c -> c.id, Comparator.reverseOrder()))
                    .skip(offset).limit(limit).map(this::row).toList();
        }

        private static Predicate<Conversation> desk(boolean platform, UUID organizationId) {
            return c -> platform ? c.kind.equals("PLATFORM")
                    : !c.kind.equals("PLATFORM") && Objects.equals(c.orgId, organizationId);
        }

        @Override
        public ConversationRow findParty(UUID customerId, String counterpartKind, UUID counterpartOrgId) {
            return find(c -> c.customerId.equals(customerId) && c.kind.equals(counterpartKind)
                    && Objects.equals(c.orgId, counterpartOrgId));
        }

        @Override
        public ConversationRow findCustomerConversation(UUID customerId, UUID conversationId) {
            return find(c -> c.id.equals(conversationId) && c.customerId.equals(customerId));
        }

        @Override
        public ConversationRow lockCustomerConversation(UUID customerId, UUID conversationId) {
            return findCustomerConversation(customerId, conversationId);
        }

        @Override
        public long countCustomerConversations(UUID customerId) {
            return page(c -> c.customerId.equals(customerId), Integer.MAX_VALUE, 0).size();
        }

        @Override
        public long sumCustomerUnread(UUID customerId) {
            return conversations.stream().filter(c -> c.customerId.equals(customerId))
                    .mapToLong(c -> c.customerUnread).sum();
        }

        @Override
        public List<ConversationRow> findCustomerPage(UUID customerId, int limit, int offset) {
            return page(c -> c.customerId.equals(customerId), limit, offset);
        }

        @Override
        public ConversationRow findDeskConversation(boolean platform, UUID organizationId, UUID conversationId) {
            return find(desk(platform, organizationId).and(c -> c.id.equals(conversationId)));
        }

        @Override
        public ConversationRow lockDeskConversation(boolean platform, UUID organizationId, UUID conversationId) {
            return findDeskConversation(platform, organizationId, conversationId);
        }

        @Override
        public long countDeskConversations(boolean platform, UUID organizationId) {
            return page(desk(platform, organizationId), Integer.MAX_VALUE, 0).size();
        }

        @Override
        public long sumDeskUnread(boolean platform, UUID organizationId) {
            return conversations.stream().filter(desk(platform, organizationId)).mapToLong(c -> c.counterpartUnread)
                    .sum();
        }

        @Override
        public List<ConversationRow> findDeskPage(boolean platform, UUID organizationId, int limit, int offset) {
            return page(desk(platform, organizationId), limit, offset);
        }

        private int record(UUID conversationId, int seq, OffsetDateTime at, boolean customer, String customerName) {
            Conversation c = conversation(conversationId);
            if (c == null || c.messageCount != seq - 1) {
                return 0;
            }
            c.messageCount = seq;
            c.lastMessageAt = at;
            if (customer) {
                c.counterpartUnread++;
                c.customerUnread = 0;
                c.customerName = customerName;
            } else {
                c.customerUnread++;
                c.counterpartUnread = 0;
            }
            c.version++;
            return 1;
        }

        @Override
        public int recordCustomerMessage(UUID conversationId, int seq, String customerName, OffsetDateTime at) {
            return record(conversationId, seq, at, true, customerName);
        }

        @Override
        public int recordDeskMessage(UUID conversationId, int seq, OffsetDateTime at) {
            return record(conversationId, seq, at, false, null);
        }

        @Override
        public int markCustomerRead(UUID customerId, UUID conversationId, OffsetDateTime at) {
            Conversation c = conversation(conversationId);
            if (c == null || !c.customerId.equals(customerId)) {
                return 0;
            }
            c.customerUnread = 0;
            c.version++;
            return 1;
        }

        @Override
        public int markDeskRead(boolean platform, UUID organizationId, UUID conversationId, OffsetDateTime at) {
            Conversation c = conversation(conversationId);
            if (c == null || !desk(platform, organizationId).test(c)) {
                return 0;
            }
            c.counterpartUnread = 0;
            c.version++;
            return 1;
        }

        private MessageRow messageRow(Message m) {
            return new MessageRow(m.id, m.conversationId, m.seq, m.side, m.kind, m.text, m.cardType, m.cardRefId,
                    m.cardTitle, m.cardSubtitle, m.createdAt);
        }

        @Override
        public List<MessageRow> findMessages(UUID conversationId, Integer before, int limit) {
            return messages.stream().filter(m -> m.conversationId.equals(conversationId)
                            && (before == null || m.seq < before))
                    .sorted(Comparator.comparingInt((Message m) -> m.seq).reversed())
                    .limit(limit).map(this::messageRow).toList();
        }

        @Override
        public MessageRow findMessage(UUID messageId) {
            return messages.stream().filter(m -> m.id.equals(messageId)).findFirst().map(this::messageRow).orElse(null);
        }

        @Override
        public KeyRow findMessageByKey(UUID senderId, UUID idempotencyKey) {
            return messages.stream().filter(m -> m.senderId.equals(senderId) && m.key.equals(idempotencyKey))
                    .findFirst().map(m -> new KeyRow(m.id, m.hash)).orElse(null);
        }

        @Override
        public UUID insertMessage(UUID conversationId, int seq, String senderSide, UUID senderId, String kind,
                                  String text, String cardType, UUID cardRefId, String cardTitle, String cardSubtitle,
                                  UUID idempotencyKey, String requestHash, OffsetDateTime at) {
            boolean textOk = kind.equals("TEXT") && text != null && cardType == null && cardRefId == null
                    && cardTitle == null && cardSubtitle == null;
            boolean cardOk = kind.equals("CARD") && text == null && cardType != null && cardRefId != null
                    && cardTitle != null;
            if (!(textOk || cardOk) || (text != null && text.codePointCount(0, text.length()) > 500)
                    || findMessageByKey(senderId, idempotencyKey) != null
                    || messages.stream().anyMatch(m -> m.conversationId.equals(conversationId) && m.seq == seq)) {
                throw new IllegalStateException("support_message check");
            }
            Message m = new Message();
            m.id = UUID.randomUUID();
            m.conversationId = conversationId;
            m.seq = seq;
            m.side = senderSide;
            m.senderId = senderId;
            m.kind = kind;
            m.text = text;
            m.cardType = cardType;
            m.cardRefId = cardRefId;
            m.cardTitle = cardTitle;
            m.cardSubtitle = cardSubtitle;
            m.key = idempotencyKey;
            m.hash = requestHash;
            m.createdAt = at;
            messages.add(m);
            return m.id;
        }

        @Override
        public FeedbackRow findFeedbackByKey(UUID accountId, UUID idempotencyKey) {
            return feedback.stream().filter(row -> row.accountId().equals(accountId)
                            && idempotencyKey.toString().equals(feedbackKeys.get(row.id())))
                    .findFirst().orElse(null);
        }

        @Override
        public FeedbackRow insertFeedback(UUID accountId, String feedbackType, String body, UUID idempotencyKey,
                                          String requestHash, OffsetDateTime at) {
            if (body.length() > 200 || findFeedbackByKey(accountId, idempotencyKey) != null) {
                throw new IllegalStateException("support_feedback check");
            }
            FeedbackRow row = new FeedbackRow(UUID.randomUUID(), accountId, feedbackType, body, requestHash, at);
            feedback.add(row);
            feedbackKeys.put(row.id(), idempotencyKey.toString());
            return row;
        }

        // ---- S3-1 运营端处理反馈 ----

        final Map<UUID, Handling> handling = new HashMap<>();

        static final class Handling {
            String source = "USER";
            UUID targetId;
            UUID parentId;
            String status = "PENDING";
            String note;
            OffsetDateTime handledAt;
            String handlerName;
            int version;
        }

        @Override
        public FeedbackRow insertReportFeedback(UUID accountId, String feedbackType, String body, UUID idempotencyKey,
                                                String requestHash, OffsetDateTime at, String source, UUID targetId,
                                                UUID targetParentId) {
            if ("USER".equals(source) != (targetId == null) || (targetId == null) != (targetParentId == null)) {
                throw new IllegalStateException("ck_support_feedback_target");
            }
            FeedbackRow row = insertFeedback(accountId, feedbackType, body, idempotencyKey, requestHash, at);
            Handling h = new Handling();
            h.source = source;
            h.targetId = targetId;
            h.parentId = targetParentId;
            handling.put(row.id(), h);
            return row;
        }

        private Handling handlingOf(UUID id) {
            return handling.computeIfAbsent(id, ignored -> new Handling());
        }

        private OpsFeedbackRow opsRow(FeedbackRow row) {
            Handling h = handlingOf(row.id());
            return new OpsFeedbackRow(row.id(), row.accountId(), row.feedbackType(), row.body(), h.source, h.targetId,
                    h.parentId, h.status, h.note, h.handledAt, h.handlerName, h.version, row.createdAt());
        }

        private List<OpsFeedbackRow> opsRows(String handleStatus, String source) {
            return feedback.stream().map(this::opsRow)
                    .filter(row -> handleStatus == null || row.handleStatus().equals(handleStatus))
                    .filter(row -> source == null || row.source().equals(source))
                    .sorted(Comparator.comparing((OpsFeedbackRow row) -> row.handleStatus().equals("PENDING") ? 0 : 1)
                            .thenComparing(row -> row.handleStatus().equals("PENDING")
                                    ? row.createdAt().toEpochSecond() : -row.createdAt().toEpochSecond()))
                    .toList();
        }

        @Override
        public long countOpsFeedback(String handleStatus, String source) {
            return opsRows(handleStatus, source).size();
        }

        @Override
        public List<OpsFeedbackRow> findOpsFeedback(String handleStatus, String source, int limit, int offset) {
            return opsRows(handleStatus, source).stream().skip(offset).limit(limit).toList();
        }

        @Override
        public OpsFeedbackRow findOpsFeedbackById(UUID feedbackId) {
            return feedback.stream().filter(row -> row.id().equals(feedbackId)).findFirst().map(this::opsRow)
                    .orElse(null);
        }

        @Override
        public long countPendingFeedback() {
            return opsRows("PENDING", null).size();
        }

        @Override
        public int handleFeedback(UUID feedbackId, String note, UUID handlerId, String handlerName, int version,
                                  OffsetDateTime at) {
            if (findOpsFeedbackById(feedbackId) == null) {
                return 0;
            }
            Handling h = handlingOf(feedbackId);
            if (!h.status.equals("PENDING") || h.version != version) {
                return 0;
            }
            h.status = "HANDLED";
            h.note = note;
            h.handledAt = at;
            h.handlerName = handlerName;
            h.version++;
            return 1;
        }

        static final class Conversation {
            UUID id;
            UUID customerId;
            String customerName;
            String kind;
            UUID orgId;
            String name;
            int messageCount;
            int customerUnread;
            int counterpartUnread;
            OffsetDateTime lastMessageAt;
            OffsetDateTime createdAt;
            int version;
        }

        static final class Message {
            UUID id;
            UUID conversationId;
            int seq;
            String side;
            UUID senderId;
            String kind;
            String text;
            String cardType;
            UUID cardRefId;
            String cardTitle;
            String cardSubtitle;
            UUID key;
            String hash;
            OffsetDateTime createdAt;
        }
    }
}
