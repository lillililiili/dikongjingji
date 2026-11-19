package com.lowaltitude.inspection.support.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.lowaltitude.inspection.support.application.SupportException.Error;
import com.lowaltitude.inspection.support.infrastructure.persistence.SupportMapper;
import com.lowaltitude.inspection.support.infrastructure.persistence.SupportMapper.ConversationRow;
import com.lowaltitude.inspection.support.infrastructure.persistence.SupportMapper.FeedbackRow;
import com.lowaltitude.inspection.support.infrastructure.persistence.SupportMapper.KeyRow;
import com.lowaltitude.inspection.support.infrastructure.persistence.SupportMapper.MessageRow;
import org.springframework.transaction.annotation.Transactional;

/**
 * 客服会话和意见反馈（G1，docs/redesign/03-App实现任务计划.md §26.1、§26.2，契约 contracts/openapi-support.yaml）。
 *
 * <ul>
 *   <li>客户和同一方只有一个会话：平台客服一个；同一家机构按商家、服务商各一个。对方机构要在用、是服务商类机构。</li>
 *   <li>消息只有文字（≤500 字）和卡片（类型 + 稳定 ID + 标题），只追加；同一个发送人的幂等键只能用在一条消息上。</li>
 *   <li>两边各记未读条数：一边发了，另一边加一，自己这边清零；进会话（read）清零。</li>
 *   <li>对方回复时给客户记一条“客服消息”站内信，同一会话最多一条未读；客户读会话时把它标为已读。</li>
 *   <li>客户看到的对方只有名称；对方看到的客户只有显示名。谁也看不到手机号、地址和对方是哪个操作员。</li>
 * </ul>
 */
public class SupportService {

    static final int MAX_TEXT = 500;
    static final int MAX_CARD_TITLE = 60;
    static final int MAX_CARD_SUBTITLE = 60;
    static final int MAX_FEEDBACK = 200;
    static final int MAX_PAGE_SIZE = 50;
    static final int DEFAULT_MESSAGE_PAGE = 20;
    static final int PREVIEW_LENGTH = 60;
    static final String PLATFORM_NAME = "平台客服";
    /** 身份模块里商家、服务商都是这类机构。 */
    static final String PROVIDER_ORGANIZATION = "PROVIDER";

    private final SupportMapper mapper;
    private final SupportPorts.Organizations organizations;
    private final SupportPorts.Profiles profiles;
    private final SupportPorts.Notifier notifier;
    private final Clock clock;

    public SupportService(
            SupportMapper mapper,
            SupportPorts.Organizations organizations,
            SupportPorts.Profiles profiles,
            SupportPorts.Notifier notifier
    ) {
        this(mapper, organizations, profiles, notifier, Clock.systemUTC());
    }

    SupportService(
            SupportMapper mapper,
            SupportPorts.Organizations organizations,
            SupportPorts.Profiles profiles,
            SupportPorts.Notifier notifier,
            Clock clock
    ) {
        this.mapper = mapper;
        this.organizations = organizations;
        this.profiles = profiles;
        this.notifier = notifier;
        this.clock = clock;
    }

    // ---- 客户 ----

    /** 打开和这一方的会话：已有就返回原来的（created 为 false），没有就新开一个。平台客服不带机构。 */
    @Transactional
    public SupportConversationOpened open(UUID customerId, String kindCode, UUID organizationId) {
        requireAccount(customerId);
        SupportCounterpartKind kind = parse(SupportCounterpartKind.class, kindCode, "counterpart kind");
        String counterpartName;
        if (kind == SupportCounterpartKind.PLATFORM) {
            if (organizationId != null) {
                throw new IllegalArgumentException("the platform support has no organization");
            }
            counterpartName = PLATFORM_NAME;
        } else {
            if (organizationId == null) {
                throw new IllegalArgumentException("organization is required");
            }
            SupportPorts.Organization organization = organizations.findActive(organizationId)
                    .filter(found -> PROVIDER_ORGANIZATION.equals(found.type()))
                    .orElseThrow(() -> new SupportException(Error.COUNTERPART_UNAVAILABLE));
            counterpartName = organization.name();
        }
        String customerName = customerName(customerId);
        boolean created = mapper.insertConversation(customerId, customerName, kind.name(), organizationId,
                counterpartName, now()) > 0;
        ConversationRow row = mapper.findParty(customerId, kind.name(), organizationId);
        if (row == null) {
            throw new IllegalStateException("conversation was neither created nor found");
        }
        return new SupportConversationOpened(customerView(row), created);
    }

    @Transactional(readOnly = true)
    public SupportConversationPageView conversations(UUID customerId, int page, int size) {
        requireAccount(customerId);
        requirePage(page, size);
        long total = mapper.countCustomerConversations(customerId);
        List<SupportConversationView> items = mapper.findCustomerPage(customerId, size, offset(page, size)).stream()
                .map(SupportService::customerView)
                .toList();
        return new SupportConversationPageView(page, size, total, (long) page * size < total,
                mapper.sumCustomerUnread(customerId), items);
    }

    /** 一页消息（不改未读，进会话请调 read）。 */
    @Transactional(readOnly = true)
    public SupportMessagePageView messages(UUID customerId, UUID conversationId, Integer before, Integer size) {
        requireAccount(customerId);
        int limit = messagePage(before, size);
        ConversationRow row = customerConversation(customerId, conversationId);
        List<MessageRow> rows = mapper.findMessages(row.id(), before, limit + 1);
        return new SupportMessagePageView(customerView(row), messageViews(rows, limit, SupportSenderSide.CUSTOMER),
                rows.size() > limit, nextBefore(rows, limit));
    }

    /** 客户发一条消息。同一个幂等键带同样的内容重复提交，返回第一次那条（created 为 false）。 */
    @Transactional
    public SupportMessageSent send(UUID customerId, UUID conversationId, UUID idempotencyKey,
                                   SupportMessageDraft draft) {
        requireAccount(customerId);
        Body body = body(draft);
        requireKey(idempotencyKey);
        requireId(conversationId);
        String hash = hash(conversationId + "|" + body.fingerprint());

        mapper.lockSender(customerId);
        SupportMessageSent replayed = replay(customerId, idempotencyKey, hash, SupportSenderSide.CUSTOMER);
        if (replayed != null) {
            return replayed;
        }
        ConversationRow row = mapper.lockCustomerConversation(customerId, conversationId);
        if (row == null) {
            throw new SupportException(Error.NOT_FOUND);
        }
        OffsetDateTime at = now();
        int seq = row.messageCount() + 1;
        UUID messageId = insert(row.id(), seq, SupportSenderSide.CUSTOMER, customerId, body, idempotencyKey, hash, at);
        requireOne(mapper.recordCustomerMessage(row.id(), seq, customerName(customerId), at));
        // 客户在会话里发消息就是在看这个会话。
        notifier.read(customerId, row.id());
        return new SupportMessageSent(messageView(mapper.findMessage(messageId), SupportSenderSide.CUSTOMER), true);
    }

    /** 客户进了会话：自己这边的未读清零，这个会话的“客服消息”站内信标为已读。 */
    @Transactional
    public SupportConversationView read(UUID customerId, UUID conversationId) {
        requireAccount(customerId);
        requireId(conversationId);
        if (mapper.markCustomerRead(customerId, conversationId, now()) == 0) {
            throw new SupportException(Error.NOT_FOUND);
        }
        notifier.read(customerId, conversationId);
        return customerView(customerConversation(customerId, conversationId));
    }

    // ---- 回复方（商家、服务商的操作员，平台客服） ----

    @Transactional(readOnly = true)
    public SupportDeskConversationPageView deskConversations(SupportDesk desk, int page, int size) {
        requireDesk(desk);
        requirePage(page, size);
        long total = mapper.countDeskConversations(desk.platform(), desk.organizationId());
        List<SupportDeskConversationView> items = mapper.findDeskPage(desk.platform(), desk.organizationId(), size,
                        offset(page, size)).stream()
                .map(SupportService::deskView)
                .toList();
        return new SupportDeskConversationPageView(page, size, total, (long) page * size < total,
                mapper.sumDeskUnread(desk.platform(), desk.organizationId()), items);
    }

    @Transactional(readOnly = true)
    public SupportDeskMessagePageView deskMessages(SupportDesk desk, UUID conversationId, Integer before,
                                                   Integer size) {
        requireDesk(desk);
        int limit = messagePage(before, size);
        ConversationRow row = deskConversation(desk, conversationId);
        List<MessageRow> rows = mapper.findMessages(row.id(), before, limit + 1);
        return new SupportDeskMessagePageView(deskView(row), messageViews(rows, limit, SupportSenderSide.COUNTERPART),
                rows.size() > limit, nextBefore(rows, limit));
    }

    /** 回复一条。客户那边未读加一，并记一条“客服消息”站内信（同一会话最多一条未读）。 */
    @Transactional
    public SupportMessageSent deskSend(SupportDesk desk, UUID operatorId, UUID conversationId, UUID idempotencyKey,
                                       SupportMessageDraft draft) {
        requireDesk(desk);
        requireAccount(operatorId);
        Body body = body(draft);
        requireKey(idempotencyKey);
        requireId(conversationId);
        String hash = hash(conversationId + "|" + body.fingerprint());

        mapper.lockSender(operatorId);
        SupportMessageSent replayed = replay(operatorId, idempotencyKey, hash, SupportSenderSide.COUNTERPART);
        if (replayed != null) {
            return replayed;
        }
        ConversationRow row = mapper.lockDeskConversation(desk.platform(), desk.organizationId(), conversationId);
        if (row == null) {
            throw new SupportException(Error.NOT_FOUND);
        }
        OffsetDateTime at = now();
        int seq = row.messageCount() + 1;
        UUID messageId = insert(row.id(), seq, SupportSenderSide.COUNTERPART, operatorId, body, idempotencyKey, hash,
                at);
        requireOne(mapper.recordDeskMessage(row.id(), seq, at));
        notifier.replied(row.customerId(), row.id(), row.counterpartKind(), row.counterpartName(), messageId,
                at.toInstant());
        return new SupportMessageSent(messageView(mapper.findMessage(messageId), SupportSenderSide.COUNTERPART), true);
    }

    @Transactional
    public SupportDeskConversationView deskRead(SupportDesk desk, UUID conversationId) {
        requireDesk(desk);
        requireId(conversationId);
        if (mapper.markDeskRead(desk.platform(), desk.organizationId(), conversationId, now()) == 0) {
            throw new SupportException(Error.NOT_FOUND);
        }
        return deskView(deskConversation(desk, conversationId));
    }

    // ---- 反馈 ----

    /** 记一条反馈（类型 + ≤200 字）。同一个幂等键带同样的内容重复提交，返回第一次那条。 */
    @Transactional
    public SupportFeedbackCreated feedback(UUID accountId, UUID idempotencyKey, String typeCode, String text) {
        requireAccount(accountId);
        requireKey(idempotencyKey);
        SupportFeedbackType type = parse(SupportFeedbackType.class, typeCode, "feedback type");
        String body = text(text, MAX_FEEDBACK, true, "feedback");
        String hash = hash(type.name() + "|" + body);

        mapper.lockSender(accountId);
        FeedbackRow existing = mapper.findFeedbackByKey(accountId, idempotencyKey);
        if (existing != null) {
            if (!hash.equals(existing.requestHash().trim())) {
                throw new SupportException(Error.IDEMPOTENCY_CONFLICT);
            }
            return new SupportFeedbackCreated(feedbackView(existing), false);
        }
        return new SupportFeedbackCreated(feedbackView(mapper.insertFeedback(accountId, type.name(), body,
                idempotencyKey, hash, now())), true);
    }

    // ---- 内部 ----

    private SupportMessageSent replay(UUID senderId, UUID idempotencyKey, String hash, SupportSenderSide viewer) {
        KeyRow existing = mapper.findMessageByKey(senderId, idempotencyKey);
        if (existing == null) {
            return null;
        }
        if (!hash.equals(existing.requestHash().trim())) {
            throw new SupportException(Error.IDEMPOTENCY_CONFLICT);
        }
        return new SupportMessageSent(messageView(mapper.findMessage(existing.id()), viewer), false);
    }

    private UUID insert(UUID conversationId, int seq, SupportSenderSide side, UUID senderId, Body body,
                        UUID idempotencyKey, String hash, OffsetDateTime at) {
        SupportCardDraft card = body.card();
        return mapper.insertMessage(conversationId, seq, side.name(), senderId, body.kind().name(), body.text(),
                card == null ? null : card.type(),
                card == null ? null : card.refId(),
                card == null ? null : card.title(),
                card == null ? null : card.subtitle(),
                idempotencyKey, hash, at);
    }

    private ConversationRow customerConversation(UUID customerId, UUID conversationId) {
        requireId(conversationId);
        ConversationRow row = mapper.findCustomerConversation(customerId, conversationId);
        if (row == null) {
            throw new SupportException(Error.NOT_FOUND);
        }
        return row;
    }

    private ConversationRow deskConversation(SupportDesk desk, UUID conversationId) {
        requireId(conversationId);
        ConversationRow row = mapper.findDeskConversation(desk.platform(), desk.organizationId(), conversationId);
        if (row == null) {
            throw new SupportException(Error.NOT_FOUND);
        }
        return row;
    }

    private String customerName(UUID customerId) {
        return profiles.displayName(customerId)
                .filter(name -> !name.isBlank())
                .orElseThrow(() -> new SupportException(Error.FORBIDDEN));
    }

    /** 消息体：文字和卡片只给一个；文字去掉首尾空白后 1–500 字，可以换行；卡片的类型、ID、标题必填。 */
    private static Body body(SupportMessageDraft draft) {
        if (draft == null || (draft.text() == null) == (draft.card() == null)) {
            throw new IllegalArgumentException("a message is a text or a card");
        }
        if (draft.text() != null) {
            return new Body(SupportMessageKind.TEXT, text(draft.text(), MAX_TEXT, true, "text"), null);
        }
        SupportCardDraft card = draft.card();
        SupportCardType type = parse(SupportCardType.class, card.type(), "card type");
        if (card.refId() == null) {
            throw new IllegalArgumentException("card id is required");
        }
        String title = text(card.title(), MAX_CARD_TITLE, false, "card title");
        String subtitle = card.subtitle() == null || card.subtitle().isBlank()
                ? null
                : text(card.subtitle(), MAX_CARD_SUBTITLE, false, "card subtitle");
        return new Body(SupportMessageKind.CARD, null, new SupportCardDraft(type.name(), card.refId(), title, subtitle));
    }

    private static String text(String value, int maxLength, boolean newlines, String what) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > maxLength
                || trimmed.chars().anyMatch(c -> Character.isISOControl(c) && !(newlines && c == '\n'))) {
            throw new IllegalArgumentException(what + " is invalid");
        }
        return trimmed;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String code, String what) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException(what + " is required");
        }
        try {
            return Enum.valueOf(type, code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(what + " is invalid", unknown);
        }
    }

    private static int messagePage(Integer before, Integer size) {
        if (before != null && before < 1) {
            throw new IllegalArgumentException("before is invalid");
        }
        int limit = size == null ? DEFAULT_MESSAGE_PAGE : size;
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size is invalid");
        }
        return limit;
    }

    private static List<SupportMessageView> messageViews(List<MessageRow> rows, int limit, SupportSenderSide viewer) {
        List<SupportMessageView> views = new ArrayList<>();
        for (MessageRow row : rows.subList(0, Math.min(limit, rows.size()))) {
            views.add(messageView(row, viewer));
        }
        // 读出来是新的在前，页面按时间先后显示。
        return views.reversed();
    }

    private static Integer nextBefore(List<MessageRow> rows, int limit) {
        return rows.size() > limit ? rows.get(limit - 1).seq() : null;
    }

    private static SupportMessageView messageView(MessageRow row, SupportSenderSide viewer) {
        SupportSenderSide side = SupportSenderSide.valueOf(row.senderSide());
        SupportMessageKind kind = SupportMessageKind.valueOf(row.kind());
        return new SupportMessageView(row.id(), row.seq(), side, side == viewer, kind,
                kind == SupportMessageKind.TEXT ? row.textBody() : null,
                kind == SupportMessageKind.CARD
                        ? new SupportCardView(SupportCardType.valueOf(row.cardType()), row.cardRefId(), row.cardTitle(),
                                row.cardSubtitle())
                        : null,
                row.createdAt());
    }

    private static SupportConversationView customerView(ConversationRow row) {
        return new SupportConversationView(row.id(),
                new SupportCounterpartView(SupportCounterpartKind.valueOf(row.counterpartKind()), row.counterpartOrgId(),
                        row.counterpartName()),
                lastMessage(row), row.customerUnread(), row.createdAt());
    }

    private static SupportDeskConversationView deskView(ConversationRow row) {
        return new SupportDeskConversationView(row.id(), SupportCounterpartKind.valueOf(row.counterpartKind()),
                new SupportCustomerView(row.customerName()), lastMessage(row), row.counterpartUnread(),
                row.createdAt());
    }

    private static SupportLastMessageView lastMessage(ConversationRow row) {
        if (row.messageCount() == 0 || row.lastKind() == null) {
            return null;
        }
        SupportMessageKind kind = SupportMessageKind.valueOf(row.lastKind());
        return new SupportLastMessageView(SupportSenderSide.valueOf(row.lastSenderSide()), kind,
                kind == SupportMessageKind.TEXT ? preview(row.lastText()) : row.lastCardTitle(), row.lastMessageAt());
    }

    /** 文字的前 60 个字，换行换成空格。 */
    static String preview(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.codePointCount(0, flat.length()) <= PREVIEW_LENGTH
                ? flat
                : flat.substring(0, flat.offsetByCodePoints(0, PREVIEW_LENGTH));
    }

    private static SupportFeedbackView feedbackView(FeedbackRow row) {
        return new SupportFeedbackView(row.id(), SupportFeedbackType.valueOf(row.feedbackType()), row.body(),
                row.createdAt());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static int offset(int page, int size) {
        return Math.multiplyExact(page - 1, size);
    }

    private static void requirePage(int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
    }

    private static void requireAccount(UUID accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("account is required");
        }
    }

    private static void requireDesk(SupportDesk desk) {
        if (desk == null) {
            throw new IllegalArgumentException("desk is required");
        }
    }

    private static void requireKey(UUID idempotencyKey) {
        if (idempotencyKey == null) {
            throw new IllegalArgumentException("idempotency key is required");
        }
    }

    private static void requireId(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("conversation is required");
        }
    }

    private static void requireOne(int updated) {
        if (updated != 1) {
            throw new IllegalStateException("conversation changed while it was locked");
        }
    }

    private static String hash(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Body(SupportMessageKind kind, String text, SupportCardDraft card) {

        String fingerprint() {
            return card == null
                    ? "TEXT|" + text
                    : "CARD|" + card.type() + "|" + card.refId() + "|" + card.title() + "|"
                            + (card.subtitle() == null ? "" : card.subtitle());
        }
    }
}
