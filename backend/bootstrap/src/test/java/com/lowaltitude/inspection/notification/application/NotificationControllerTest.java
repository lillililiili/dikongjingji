package com.lowaltitude.inspection.notification.application;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class NotificationControllerTest {

    private static final UUID SUBJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORGANIZATION = UUID.fromString("11111111-1111-1111-1111-aaaaaaaaaaaa");
    private static final UUID NOTIFICATION = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final UUID ORDER = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID VISIT = UUID.fromString("61000000-0000-4000-8000-000000000001");

    private final NotificationService service = mock(NotificationService.class);
    private final TestingAuthenticationToken customer = new TestingAuthenticationToken(
            new CurrentSubject(SUBJECT, Set.of("CUSTOMER"), ORGANIZATION, Set.of(), 0), null);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new NotificationController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listsTheSignedInAccountsNotificationsWithTheUnreadCount() throws Exception {
        when(service.list(SUBJECT, 2, 10)).thenReturn(new NotificationPageView(
                2, 10, 11, false, 3,
                List.of(new NotificationView(
                        NOTIFICATION, NotificationCategory.SERVICE_PROGRESS, "INSPECTION_DELIVERY_PUBLISHED",
                        "巡检报告已交付", "光伏电站巡检服务第 1 次报告已交付，请核对后验收。",
                        new NotificationTargetView(NotificationTargetType.INSPECTION_DELIVERY, ORDER, VISIT),
                        Instant.parse("2026-11-17T03:00:00Z"), false))));

        mockMvc.perform(get("/api/v1/notifications").principal(customer).param("page", "2").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(3))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.items[0].id").value(NOTIFICATION.toString()))
                .andExpect(jsonPath("$.items[0].category").value("SERVICE_PROGRESS"))
                .andExpect(jsonPath("$.items[0].title").value("巡检报告已交付"))
                .andExpect(jsonPath("$.items[0].target.type").value("INSPECTION_DELIVERY"))
                .andExpect(jsonPath("$.items[0].target.id").value(ORDER.toString()))
                .andExpect(jsonPath("$.items[0].target.subId").value(VISIT.toString()))
                .andExpect(jsonPath("$.items[0].read").value(false))
                // 每条通知只有契约里的八个字段，没有收件人。
                .andExpect(jsonPath("$.items[0].length()").value(8))
                .andExpect(jsonPath("$.items[0].recipientId").doesNotExist());
    }

    @Test
    void unreadCountAndMarkingReadUseTheSubjectOfTheToken() throws Exception {
        when(service.unreadCount(SUBJECT)).thenReturn(new UnreadCountView(4));
        when(service.markAllRead(SUBJECT)).thenReturn(new MarkedReadView(4));
        when(service.markRead(SUBJECT, NOTIFICATION)).thenReturn(true);

        mockMvc.perform(get("/api/v1/notifications/unread-count").principal(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(4));
        mockMvc.perform(post("/api/v1/notifications/read-all").principal(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(4));
        mockMvc.perform(post("/api/v1/notifications/{id}/read", NOTIFICATION).principal(customer))
                .andExpect(status().isNoContent());

        verify(service).markRead(SUBJECT, NOTIFICATION);
    }

    // 别人的通知和不存在的通知是同一个回答。
    @Test
    void someoneElsesNotificationIsNotFound() throws Exception {
        when(service.markRead(SUBJECT, NOTIFICATION)).thenReturn(false);

        mockMvc.perform(post("/api/v1/notifications/{id}/read", NOTIFICATION).principal(customer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"))
                .andExpect(jsonPath("$.details").value(nullValue()));
    }

    // 契约里的路由和控制器的路由一一对应，而且没有一条是公开的。
    @Test
    void contractDeclaresExactlyTheControllersRoutesAndNoneIsPublic() throws Exception {
        String contract = java.nio.file.Files.readString(
                java.nio.file.Path.of("..", "..", "contracts", "openapi-notification.yaml"));
        java.util.Set<String> routes = new java.util.TreeSet<>();
        for (java.lang.reflect.Method method : NotificationController.class.getDeclaredMethods()) {
            var get = method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class);
            var post = method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class);
            if (get != null) {
                routes.add(get.value()[0]);
            }
            if (post != null) {
                routes.add(post.value()[0]);
            }
        }

        org.assertj.core.api.Assertions.assertThat(routes).hasSize(4);
        for (String route : routes) {
            org.assertj.core.api.Assertions.assertThat(contract).contains("  " + route + ":");
        }
        org.assertj.core.api.Assertions.assertThat(contract)
                .contains("security:\n  - bearerAuth: []")
                .doesNotContain("security: []")
                .contains("required: [id, category, eventCode, title, body, target, occurredAt, read]")
                .contains("required: [page, size, total, hasMore, unreadCount, items]");
    }

    // 契约里的分类和跳转目标就是服务端的两个枚举（1.1.0 加了需求与报价，1.2.0 加了客服消息）。
    @Test
    void contractEnumsMatchTheJavaSide() throws Exception {
        java.util.Map<String, Object> root = new org.yaml.snakeyaml.Yaml().load(java.nio.file.Files.readString(
                java.nio.file.Path.of("..", "..", "contracts", "openapi-notification.yaml")));
        @SuppressWarnings("unchecked")
        java.util.Map<String, java.util.Map<String, Object>> schemas = (java.util.Map<String, java.util.Map<String, Object>>)
                ((java.util.Map<String, Object>) root.get("components")).get("schemas");

        org.assertj.core.api.Assertions.assertThat(schemas.get("NotificationCategory").get("enum"))
                .isEqualTo(java.util.Arrays.stream(NotificationCategory.values()).map(Enum::name).toList());
        org.assertj.core.api.Assertions.assertThat(schemas.get("NotificationTargetType").get("enum"))
                .isEqualTo(java.util.Arrays.stream(NotificationTargetType.values()).map(Enum::name).toList());
        // 1.6.0：活动候补、改期、结果的站内信（S5-3）。
        org.assertj.core.api.Assertions.assertThat(((java.util.Map<String, Object>) root.get("info")).get("version"))
                .isEqualTo("1.6.0");
    }

    @Test
    void badArgumentsAreBadRequests() throws Exception {
        when(service.list(SUBJECT, 0, 20)).thenThrow(new IllegalArgumentException("page is invalid"));

        mockMvc.perform(get("/api/v1/notifications").principal(customer).param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(post("/api/v1/notifications/not-a-uuid/read").principal(customer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }
}
