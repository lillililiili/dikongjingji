package com.lowaltitude.inspection.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowaltitude.inspection.notification.application.NotificationRows.SystemNoticeRow;
import com.lowaltitude.inspection.notification.application.SystemNotices.Action;
import com.lowaltitude.inspection.notification.application.SystemNotices.AgreementCode;
import com.lowaltitude.inspection.notification.application.SystemNotices.AgreementUpdateView;
import com.lowaltitude.inspection.notification.application.SystemNotices.Audience;
import com.lowaltitude.inspection.notification.application.SystemNotices.AudienceScope;
import com.lowaltitude.inspection.notification.application.SystemNotices.Content;
import com.lowaltitude.inspection.notification.application.SystemNotices.Draft;
import com.lowaltitude.inspection.notification.application.SystemNotices.Fact;
import com.lowaltitude.inspection.notification.application.SystemNotices.Item;
import com.lowaltitude.inspection.notification.application.SystemNotices.Link;
import com.lowaltitude.inspection.notification.application.SystemNotices.LinkType;
import com.lowaltitude.inspection.notification.application.SystemNotices.Status;
import com.lowaltitude.inspection.notification.application.SystemNotices.SystemNoticePageView;
import com.lowaltitude.inspection.notification.application.SystemNotices.SystemNoticeView;

/**
 * 系统通知（S5-9，契约 contracts/openapi-notification.yaml 1.5.0）。
 *
 * <ul>
 *   <li>发布、撤回、列表：平台运营（接口上校验 PLATFORM_OPERATOR）。同一个幂等键重发得到第一次那份公告，不再推送。</li>
 *   <li>投递：不在发布时给每个账号写一条。账号读站内信时补记（{@link NotificationService#list}），
 *       发布时只按收件范围推给设备（{@link PushDispatcher#toAudience}，事务提交之后）。</li>
 *   <li>撤回：不再补记，已经补记的从“消息”里拿掉，详情读不到。</li>
 *   <li>详情和“协议有更新”公开只读：公告不含任何个人信息，协议页不登录也能看。</li>
 * </ul>
 */
public class SystemNoticeService {

    static final int MAX_TITLE = 40;
    static final int MAX_SUMMARY = 120;
    static final int MAX_PARAGRAPHS = 10;
    static final int MAX_PARAGRAPH = 500;
    static final int MAX_FACTS = 4;
    static final int MAX_FACT_LABEL = 8;
    static final int MAX_FACT_VALUE = 16;
    static final int MAX_ITEMS = 6;
    static final int MAX_ITEM_TITLE = 20;
    static final int MAX_ITEM_DESCRIPTION = 60;
    static final int MAX_FOOTNOTE = 200;
    static final int MAX_ACTION_LABEL = 12;
    static final int MAX_PUBLISH_KEY = 80;
    static final int MAX_PAGE_SIZE = 50;
    static final String CITY_CODE_PATTERN = "^[A-Z0-9]{4,16}$";

    private final SystemNoticeStore store;
    private final PushDispatcher pushes;
    private final ObjectMapper json;
    private final Clock clock;

    SystemNoticeService(SystemNoticeStore store, PushDispatcher pushes, ObjectMapper json, Clock clock) {
        this.store = store;
        this.pushes = pushes;
        this.json = json;
        this.clock = clock;
    }

    public SystemNoticeView publish(UUID operatorId, String publishKey, Draft draft) {
        if (operatorId == null || invalid(publishKey, MAX_PUBLISH_KEY)) {
            throw new IllegalArgumentException("operator and publish key are required");
        }
        Draft valid = validate(draft);
        Instant now = clock.instant();
        if (valid.expiresAt() != null && !valid.expiresAt().isAfter(now)) {
            throw new IllegalArgumentException("expiresAt must be in the future");
        }
        UUID id = UUID.randomUUID();
        boolean inserted = store.insert(id, valid.title(), valid.summary(), write(valid.content()),
                valid.aboutAgreement() == null ? null : valid.aboutAgreement().name(), valid.audienceScope().name(),
                valid.cityCode(), operatorId, now, valid.expiresAt(), publishKey);
        SystemNoticeRow row = store.findByPublishKey(publishKey).orElseThrow();
        if (inserted) {
            AfterCommit.run("system notice push", () ->
                    pushes.toAudience(row.id(), row.title(), row.summary(), row.cityCode()));
        }
        return view(row);
    }

    /** 撤回；已经撤回的再撤一次没有变化。不存在的公告是 404。 */
    public SystemNoticeView withdraw(UUID noticeId) {
        SystemNoticeRow existing = store.find(requireId(noticeId)).orElseThrow(NotificationNotFoundException::new);
        if (!Status.WITHDRAWN.name().equals(existing.status())) {
            Instant now = clock.instant();
            store.withdraw(noticeId, now);
            store.hideDelivered(noticeId, now);
        }
        return view(store.find(noticeId).orElseThrow(NotificationNotFoundException::new));
    }

    public SystemNoticePageView list(int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
        long total = store.count();
        List<SystemNoticeView> items = store.page(size, Math.multiplyExact(page - 1, size)).stream()
                .map(this::view)
                .toList();
        return new SystemNoticePageView(page, size, total, (long) page * size < total, items);
    }

    /** 公开详情：只有发布着的公告（过了有效期的仍可读，收到过的人还能点开）。 */
    public SystemNoticeView read(UUID noticeId) {
        return store.find(requireId(noticeId))
                .filter(row -> Status.PUBLISHED.name().equals(row.status()))
                .map(this::view)
                .orElseThrow(NotificationNotFoundException::new);
    }

    /** 某份协议最近的更新公告；没有就是空。 */
    public Optional<AgreementUpdateView> latestAbout(String agreement) {
        AgreementCode code = parse(AgreementCode.class, agreement, "agreement");
        return store.latestAbout(code.name(), clock.instant())
                .map(row -> new AgreementUpdateView(row.id(), row.title(), row.publishedAt()));
    }

    private SystemNoticeView view(SystemNoticeRow row) {
        Content content = read(row.content());
        return new SystemNoticeView(
                row.id(),
                row.title(),
                row.summary(),
                content.paragraphs(),
                content.facts(),
                content.items(),
                content.footnote(),
                content.action(),
                row.aboutAgreement() == null ? null : AgreementCode.valueOf(row.aboutAgreement()),
                new Audience(AudienceScope.valueOf(row.audienceScope()), row.cityCode()),
                row.publishedAt(),
                row.expiresAt(),
                Status.valueOf(row.status()));
    }

    private Content read(String content) {
        try {
            Content parsed = json.readValue(content, Content.class);
            return new Content(
                    parsed.paragraphs() == null ? List.of() : parsed.paragraphs(),
                    parsed.facts() == null ? List.of() : parsed.facts(),
                    parsed.items() == null ? List.of() : parsed.items(),
                    parsed.footnote(),
                    parsed.action());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("system notice content is unreadable", exception);
        }
    }

    private String write(Content content) {
        try {
            return json.writeValueAsString(content);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("system notice content cannot be written", exception);
        }
    }

    static Draft validate(Draft draft) {
        if (draft == null || draft.content() == null || draft.audienceScope() == null) {
            throw new IllegalArgumentException("notice is incomplete");
        }
        String title = text(draft.title(), MAX_TITLE, "title");
        String summary = text(draft.summary(), MAX_SUMMARY, "summary");
        String cityCode = draft.cityCode() == null || draft.cityCode().isBlank() ? null : draft.cityCode().strip();
        if (draft.audienceScope() == AudienceScope.CITY
                ? cityCode == null || !cityCode.matches(CITY_CODE_PATTERN)
                : cityCode != null) {
            throw new IllegalArgumentException("cityCode goes with the CITY audience only");
        }
        Content content = draft.content();
        List<String> paragraphs = Objects.requireNonNullElse(content.paragraphs(), List.<String>of());
        if (paragraphs.isEmpty() || paragraphs.size() > MAX_PARAGRAPHS) {
            throw new IllegalArgumentException("paragraphs must hold 1 to " + MAX_PARAGRAPHS);
        }
        List<String> cleanParagraphs = paragraphs.stream().map(p -> text(p, MAX_PARAGRAPH, "paragraph")).toList();
        List<Fact> facts = Objects.requireNonNullElse(content.facts(), List.<Fact>of());
        if (facts.size() > MAX_FACTS) {
            throw new IllegalArgumentException("too many facts");
        }
        List<Fact> cleanFacts = facts.stream().map(fact -> {
            if (fact == null) {
                throw new IllegalArgumentException("fact is required");
            }
            return new Fact(text(fact.label(), MAX_FACT_LABEL, "fact label"), text(fact.value(), MAX_FACT_VALUE,
                    "fact value"));
        }).toList();
        List<Item> items = Objects.requireNonNullElse(content.items(), List.<Item>of());
        if (items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("too many items");
        }
        List<Item> cleanItems = items.stream().map(item -> {
            if (item == null) {
                throw new IllegalArgumentException("item is required");
            }
            return new Item(text(item.title(), MAX_ITEM_TITLE, "item title"),
                    text(item.description(), MAX_ITEM_DESCRIPTION, "item description"), link(item.link(), true));
        }).toList();
        String footnote = content.footnote() == null || content.footnote().isBlank()
                ? null : text(content.footnote(), MAX_FOOTNOTE, "footnote");
        Action action = content.action() == null ? null
                : new Action(text(content.action().label(), MAX_ACTION_LABEL, "action label"),
                        link(content.action().link(), false));
        return new Draft(title, summary, new Content(cleanParagraphs, cleanFacts, cleanItems, footnote, action),
                draft.aboutAgreement(), draft.audienceScope(), cityCode, draft.expiresAt());
    }

    private static Link link(Link link, boolean optional) {
        if (link == null) {
            if (optional) {
                return null;
            }
            throw new IllegalArgumentException("link is required");
        }
        if (link.type() == null || (link.type() == LinkType.AGREEMENT) != (link.agreement() != null)) {
            throw new IllegalArgumentException("an AGREEMENT link names the agreement, other links do not");
        }
        return link;
    }

    private static String text(String value, int max, String name) {
        if (invalid(value, max)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value.strip();
    }

    private static boolean invalid(String value, int max) {
        return value == null || value.isBlank() || value.strip().codePointCount(0, value.strip().length()) > max;
    }

    private static UUID requireId(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("notice id is required");
        }
        return id;
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value, String name) {
        try {
            return Enum.valueOf(type, value == null ? "" : value.strip());
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(name + " is invalid", unknown);
        }
    }
}
