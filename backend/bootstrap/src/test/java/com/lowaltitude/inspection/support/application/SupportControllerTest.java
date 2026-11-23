package com.lowaltitude.inspection.support.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import com.lowaltitude.inspection.support.application.SupportException.Error;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 客服会话的两个控制器（G1）：从令牌里取账号、角色和机构，把请求转成服务的参数，把结果和业务拒绝转成契约里的状态码。
 * 没带令牌时的 401 由安全过滤器给出，在 AuthenticationSecurityIntegrationTest 里检查。
 */
class SupportControllerTest {

    private static final UUID SUBJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CUSTOMER_ORG = UUID.fromString("11111111-1111-1111-1111-aaaaaaaaaaaa");
    private static final UUID OPERATOR = UUID.fromString("c1000000-0000-4000-8000-0000000000a1");
    private static final UUID MERCHANT = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID AGENT = UUID.fromString("90000000-0000-4000-8000-0000000000a1");
    private static final UUID PLATFORM_ORG = UUID.fromString("90000000-0000-4000-8000-000000000001");
    private static final UUID CONVERSATION = UUID.fromString("e5000000-0000-4000-8000-000000000001");
    private static final UUID MESSAGE = UUID.fromString("e6000000-0000-4000-8000-000000000001");
    private static final UUID ORDER = UUID.fromString("e3000000-0000-4000-8000-000000000001");
    private static final UUID KEY = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-19T02:00:00Z");

    /** 每一种业务拒绝对外的状态码和错误码（GlobalExceptionHandler 与契约 1.0.0）。 */
    private static final Map<Error, Rejection> REJECTIONS = Map.of(
            Error.NOT_FOUND, new Rejection(404, "COMMON_NOT_FOUND"),
            Error.FORBIDDEN, new Rejection(403, "COMMON_FORBIDDEN"),
            Error.IDEMPOTENCY_CONFLICT, new Rejection(409, "SUPPORT_IDEMPOTENCY_KEY_REUSED"),
            Error.COUNTERPART_UNAVAILABLE, new Rejection(409, "SUPPORT_COUNTERPART_UNAVAILABLE"),
            // 运营端处理反馈（openapi-ops-feedback.yaml）。
            Error.ALREADY_HANDLED, new Rejection(409, "OPS_FEEDBACK_ALREADY_HANDLED"),
            Error.VERSION_CONFLICT, new Rejection(409, "OPS_VERSION_CONFLICT"));

    private final SupportService service = mock(SupportService.class);
    private final TestingAuthenticationToken customer = signedIn(SUBJECT, CUSTOMER_ORG, "CUSTOMER");
    private final TestingAuthenticationToken operator = signedIn(OPERATOR, MERCHANT, "PROVIDER_OPERATOR");
    private final TestingAuthenticationToken agent = signedIn(AGENT, PLATFORM_ORG, "PLATFORM_SUPPORT");
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SupportController(service), new SupportDeskController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
    }

    // ---- 谁能调 ----

    @ParameterizedTest(name = "{0}")
    @MethodSource("customerRoutes")
    void customerRoutesNeedTheCustomerRole(String route, MockHttpServletRequestBuilder request) throws Exception {
        for (TestingAuthenticationToken who : List.of(operator, agent)) {
            mockMvc.perform(request.principal(who))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        }
        verifyNoInteractions(service);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providerRoutes")
    void providerRoutesNeedAProviderOperatorOfAnOrganization(String route, MockHttpServletRequestBuilder request)
            throws Exception {
        for (TestingAuthenticationToken who : List.of(customer, agent, signedIn(OPERATOR, null, "PROVIDER_OPERATOR"))) {
            mockMvc.perform(request.principal(who))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        }
        verifyNoInteractions(service);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("platformRoutes")
    void platformRoutesNeedThePlatformSupportRole(String route, MockHttpServletRequestBuilder request)
            throws Exception {
        for (TestingAuthenticationToken who : List.of(customer, operator,
                signedIn(AGENT, PLATFORM_ORG, "PLATFORM_OPERATOR"))) {
            mockMvc.perform(request.principal(who))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        }
        verifyNoInteractions(service);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allRoutes")
    void noRouteWorksWithoutASignedInSubject(String route, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        verifyNoInteractions(service);
    }

    // ---- 客户 ----

    @Test
    void openingANewConversationAnswers201AndAnExistingOne200() throws Exception {
        when(service.open(SUBJECT, "MERCHANT", MERCHANT))
                .thenReturn(new SupportConversationOpened(conversation(0, null), true))
                .thenReturn(new SupportConversationOpened(conversation(0, null), false));
        when(service.open(SUBJECT, "PLATFORM", null))
                .thenThrow(new SupportException(Error.COUNTERPART_UNAVAILABLE));
        String body = "{\"counterpartKind\":\"MERCHANT\",\"organizationId\":\"" + MERCHANT + "\"}";

        mockMvc.perform(post("/api/v1/conversations").principal(customer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.conversationId").value(CONVERSATION.toString()))
                .andExpect(jsonPath("$.counterpart.kind").value("MERCHANT"))
                .andExpect(jsonPath("$.counterpart.organizationId").value(MERCHANT.toString()))
                .andExpect(jsonPath("$.counterpart.name").value("示例机器人商家"))
                .andExpect(jsonPath("$.counterpart.length()").value(3))
                .andExpect(jsonPath("$.lastMessage").isEmpty())
                .andExpect(jsonPath("$.unreadCount").value(0));
        mockMvc.perform(post("/api/v1/conversations").principal(customer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/conversations").principal(customer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"counterpartKind\":\"PLATFORM\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUPPORT_COUNTERPART_UNAVAILABLE"));
    }

    @Test
    void conversationsMessagesAndReadingAreTheCustomersOwn() throws Exception {
        SupportLastMessageView last = new SupportLastMessageView(SupportSenderSide.COUNTERPART, SupportMessageKind.TEXT,
                "收到订单，有什么可以帮你？", NOW);
        when(service.conversations(SUBJECT, 2, 10)).thenReturn(new SupportConversationPageView(2, 10, 11, false, 3,
                List.of(conversation(3, last))));
        when(service.messages(SUBJECT, CONVERSATION, 21, 10)).thenReturn(new SupportMessagePageView(conversation(3, last),
                List.of(card(SupportSenderSide.CUSTOMER, true), text(SupportSenderSide.COUNTERPART, false)), true, 19));
        when(service.messages(SUBJECT, CONVERSATION, null, null)).thenReturn(new SupportMessagePageView(
                conversation(0, null), List.of(), false, null));
        when(service.read(SUBJECT, CONVERSATION)).thenReturn(conversation(0, last));

        mockMvc.perform(get("/api/v1/conversations?page=2&size=10").principal(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(3))
                .andExpect(jsonPath("$.items[0].lastMessage.length()").value(4))
                .andExpect(jsonPath("$.items[0].lastMessage.senderSide").value("COUNTERPART"))
                .andExpect(jsonPath("$.items[0].lastMessage.preview").value("收到订单，有什么可以帮你？"))
                .andExpect(jsonPath("$.items[0].unreadCount").value(3));
        mockMvc.perform(get("/api/v1/conversations/" + CONVERSATION + "/messages?before=21&size=10").principal(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextBefore").value(19))
                .andExpect(jsonPath("$.items[0].length()").value(8))
                .andExpect(jsonPath("$.items[0].kind").value("CARD"))
                .andExpect(jsonPath("$.items[0].card.type").value("MALL_ORDER"))
                .andExpect(jsonPath("$.items[0].card.refId").value(ORDER.toString()))
                .andExpect(jsonPath("$.items[0].card.subtitle").value("已发货 · 实付 ¥1,299"))
                .andExpect(jsonPath("$.items[0].mine").value(true))
                .andExpect(jsonPath("$.items[1].text").value("收到订单，有什么可以帮你？"))
                .andExpect(jsonPath("$.items[1].mine").value(false))
                .andExpect(jsonPath("$.items[1].sentAt").value("2026-11-19T02:00:00Z"));
        mockMvc.perform(get("/api/v1/conversations/" + CONVERSATION + "/messages").principal(customer))
                .andExpect(jsonPath("$.nextBefore").isEmpty());
        mockMvc.perform(post("/api/v1/conversations/" + CONVERSATION + "/read").principal(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test
    void sendingPassesTheKeyAndTheDraftAndAnswers201ThenReplays200() throws Exception {
        SupportMessageDraft cardDraft = new SupportMessageDraft(null,
                new SupportCardDraft("MALL_ORDER", ORDER, "编程机器人探索套装", "已发货 · 实付 ¥1,299"));
        when(service.send(SUBJECT, CONVERSATION, KEY, cardDraft))
                .thenReturn(new SupportMessageSent(card(SupportSenderSide.CUSTOMER, true), true))
                .thenReturn(new SupportMessageSent(card(SupportSenderSide.CUSTOMER, true), false));
        String body = """
                {"card":{"type":"MALL_ORDER","refId":"%s","title":"编程机器人探索套装","subtitle":"已发货 · 实付 ¥1,299"}}
                """.formatted(ORDER);

        mockMvc.perform(post("/api/v1/conversations/" + CONVERSATION + "/messages").principal(customer)
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.messageId").value(MESSAGE.toString()))
                .andExpect(jsonPath("$.seq").value(20))
                .andExpect(jsonPath("$.senderSide").value("CUSTOMER"));
        mockMvc.perform(post("/api/v1/conversations/" + CONVERSATION + "/messages").principal(customer)
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/conversations/" + CONVERSATION + "/messages").principal(customer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"在吗\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        when(service.send(eq(SUBJECT), eq(CONVERSATION), eq(KEY), eq(new SupportMessageDraft("", null))))
                .thenThrow(new IllegalArgumentException("text is invalid"));
        mockMvc.perform(post("/api/v1/conversations/" + CONVERSATION + "/messages").principal(customer)
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }

    @Test
    void feedbackIsRecordedWithItsKey() throws Exception {
        SupportFeedbackView feedback = new SupportFeedbackView(MESSAGE, SupportFeedbackType.DISPLAY, "金额被挡住了", NOW);
        when(service.feedback(SUBJECT, KEY, "DISPLAY", "金额被挡住了"))
                .thenReturn(new SupportFeedbackCreated(feedback, true))
                .thenReturn(new SupportFeedbackCreated(feedback, false));
        String body = "{\"type\":\"DISPLAY\",\"text\":\"金额被挡住了\"}";

        mockMvc.perform(post("/api/v1/feedback").principal(customer).header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.feedbackId").value(MESSAGE.toString()))
                .andExpect(jsonPath("$.type").value("DISPLAY"));
        mockMvc.perform(post("/api/v1/feedback").principal(customer).header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/feedback").principal(customer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @EnumSource(Error.class)
    void everyBusinessRejectionHasItsStatusAndCode(Error error) throws Exception {
        when(service.send(eq(SUBJECT), eq(CONVERSATION), eq(KEY), any())).thenThrow(new SupportException(error));
        Rejection expected = REJECTIONS.get(error);

        mockMvc.perform(post("/api/v1/conversations/" + CONVERSATION + "/messages").principal(customer)
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"在吗\"}"))
                .andExpect(status().is(expected.status()))
                .andExpect(jsonPath("$.code").value(expected.code()))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // ---- 回复方 ----

    @Test
    void providerOperatorsWorkAsTheirOrganization() throws Exception {
        SupportDesk desk = SupportDesk.organization(MERCHANT);
        when(service.deskConversations(desk, 1, 20)).thenReturn(new SupportDeskConversationPageView(1, 20, 1, false, 2,
                List.of(deskConversation(2))));
        when(service.deskMessages(desk, CONVERSATION, null, 30)).thenReturn(new SupportDeskMessagePageView(
                deskConversation(2), List.of(text(SupportSenderSide.CUSTOMER, false)), false, null));
        when(service.deskSend(desk, OPERATOR, CONVERSATION, KEY, new SupportMessageDraft("在的", null)))
                .thenReturn(new SupportMessageSent(text(SupportSenderSide.COUNTERPART, true), true));
        when(service.deskRead(desk, CONVERSATION)).thenReturn(deskConversation(0));

        mockMvc.perform(get("/api/v1/provider/conversations").principal(operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(2))
                .andExpect(jsonPath("$.items[0].length()").value(6))
                .andExpect(jsonPath("$.items[0].customer.length()").value(1))
                .andExpect(jsonPath("$.items[0].customer.name").value("王女士"))
                .andExpect(jsonPath("$.items[0].counterpartKind").value("MERCHANT"));
        mockMvc.perform(get("/api/v1/provider/conversations/" + CONVERSATION + "/messages?size=30").principal(operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation.customer.name").value("王女士"))
                .andExpect(jsonPath("$.items[0].mine").value(false));
        mockMvc.perform(post("/api/v1/provider/conversations/" + CONVERSATION + "/messages").principal(operator)
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"在的\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.senderSide").value("COUNTERPART"))
                .andExpect(jsonPath("$.mine").value(true));
        mockMvc.perform(post("/api/v1/provider/conversations/" + CONVERSATION + "/read").principal(operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));
        verify(service).deskSend(desk, OPERATOR, CONVERSATION, KEY, new SupportMessageDraft("在的", null));
    }

    @Test
    void thePlatformSupportWorksOnPlatformConversations() throws Exception {
        SupportDesk desk = SupportDesk.platformSupport();
        when(service.deskConversations(desk, 1, 20)).thenReturn(new SupportDeskConversationPageView(1, 20, 0, false, 0,
                List.of()));
        when(service.deskMessages(desk, CONVERSATION, 5, null)).thenThrow(new SupportException(Error.NOT_FOUND));
        when(service.deskSend(desk, AGENT, CONVERSATION, KEY, new SupportMessageDraft("收到", null)))
                .thenReturn(new SupportMessageSent(text(SupportSenderSide.COUNTERPART, true), false));
        when(service.deskRead(desk, CONVERSATION)).thenReturn(deskConversation(0));

        mockMvc.perform(get("/api/v1/platform/support/conversations").principal(agent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(get("/api/v1/platform/support/conversations/" + CONVERSATION + "/messages?before=5")
                        .principal(agent))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/platform/support/conversations/" + CONVERSATION + "/messages").principal(agent)
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"收到\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/platform/support/conversations/" + CONVERSATION + "/read").principal(agent))
                .andExpect(status().isOk());
    }

    // ---- 数据 ----

    private static Stream<Arguments> customerRoutes() {
        String conversation = "/api/v1/conversations/" + CONVERSATION;
        return Stream.of(
                Arguments.of("GET conversations", get("/api/v1/conversations")),
                Arguments.of("POST conversations", post("/api/v1/conversations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"counterpartKind\":\"PLATFORM\"}")),
                Arguments.of("GET messages", get(conversation + "/messages")),
                Arguments.of("POST messages", post(conversation + "/messages").header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"在吗\"}")),
                Arguments.of("POST read", post(conversation + "/read")),
                Arguments.of("POST feedback", post("/api/v1/feedback").header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"FUNCTION\",\"text\":\"x\"}")));
    }

    private static Stream<Arguments> deskRoutes(String prefix) {
        String conversation = prefix + "/" + CONVERSATION;
        return Stream.of(
                Arguments.of("GET " + prefix, get(prefix)),
                Arguments.of("GET messages " + prefix, get(conversation + "/messages")),
                Arguments.of("POST messages " + prefix, post(conversation + "/messages")
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"在的\"}")),
                Arguments.of("POST read " + prefix, post(conversation + "/read")));
    }

    private static Stream<Arguments> providerRoutes() {
        return deskRoutes("/api/v1/provider/conversations");
    }

    private static Stream<Arguments> platformRoutes() {
        return deskRoutes("/api/v1/platform/support/conversations");
    }

    private static Stream<Arguments> allRoutes() {
        return Stream.concat(customerRoutes(), Stream.concat(providerRoutes(), platformRoutes()));
    }

    private static SupportConversationView conversation(int unread, SupportLastMessageView last) {
        return new SupportConversationView(CONVERSATION,
                new SupportCounterpartView(SupportCounterpartKind.MERCHANT, MERCHANT, "示例机器人商家"), last, unread, NOW);
    }

    private static SupportDeskConversationView deskConversation(int unread) {
        return new SupportDeskConversationView(CONVERSATION, SupportCounterpartKind.MERCHANT,
                new SupportCustomerView("王女士"),
                new SupportLastMessageView(SupportSenderSide.CUSTOMER, SupportMessageKind.TEXT, "在吗", NOW), unread, NOW);
    }

    private static SupportMessageView card(SupportSenderSide side, boolean mine) {
        return new SupportMessageView(MESSAGE, 20, side, mine, SupportMessageKind.CARD, null,
                new SupportCardView(SupportCardType.MALL_ORDER, ORDER, "编程机器人探索套装", "已发货 · 实付 ¥1,299"), NOW);
    }

    private static SupportMessageView text(SupportSenderSide side, boolean mine) {
        return new SupportMessageView(UUID.randomUUID(), 21, side, mine, SupportMessageKind.TEXT,
                "收到订单，有什么可以帮你？", null, NOW);
    }

    private static TestingAuthenticationToken signedIn(UUID subjectId, UUID organizationId, String role) {
        return new TestingAuthenticationToken(
                new CurrentSubject(subjectId, Set.of(role), organizationId, Set.of(), 0), null);
    }

    private record Rejection(int status, String code) {
    }
}
