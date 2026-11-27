package com.lowaltitude.inspection.application.serviceflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

class InspectionServiceFlowOpenApiContractTest {

    private static final Path CONTRACT = Path.of(
            "..", "..", "contracts", "openapi-inspection-service-flow.yaml");

    @Test
    void everyServiceFlowRouteIsDeclaredInTheContract() throws Exception {
        String contract = Files.readString(CONTRACT).replace("\r\n", "\n");

        List<String> routes = new ArrayList<>();
        routes.addAll(routes(InspectionPlanController.class));
        routes.addAll(routes(InspectionOrderController.class));
        routes.addAll(routes(InspectionDeliveryController.class));
        routes.addAll(routes(InspectionPaymentController.class));
        // 2.3.0 服务商工作台的两条读取：控制器类上是公共前缀，方法上是空路径和 /{reservationId}。
        String providerPrefix = com.lowaltitude.inspection.application.reservation
                .InspectionProviderReservationController.class
                .getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class).value()[0];
        routes.add(providerPrefix);
        routes.add(providerPrefix + "/{reservationId}");

        assertThat(routes).hasSizeGreaterThanOrEqualTo(17);
        assertThat(routes).allSatisfy(route ->
                assertThat(contract).contains("  " + route + ":\n"));
    }

    @Test
    void perOrderDeliveryRoutesAreGone() throws Exception {
        String contract = Files.readString(CONTRACT);

        assertThat(contract)
                .doesNotContain("service-orders/{serviceOrderId}/delivery:")
                .doesNotContain("service-orders/{serviceOrderId}/acceptance:")
                .doesNotContain("service-orders/{serviceOrderId}/progress-events:")
                .doesNotContain("service-orders/{serviceOrderId}/delivery-files/");
    }

    @Test
    void contractDeclaresVisitConcurrencyAndErrorCodes() throws Exception {
        String contract = Files.readString(CONTRACT);

        assertThat(contract)
                .contains("version: 2.9.0")
                .contains("/api/v1/inspection/demand-bookings/{demandId}:")
                .contains("INSPECTION_QUOTE_AMOUNT_MISMATCH")
                .contains("operationId: getCurrentProviderInspectionPlan")
                // 2.6.0（S4-4）：订单列表带服务商，服务团队成员可标记持证飞手
                .contains("certifiedPilot:")
                // 2.3.0：服务商工作台读分给本机构的预约
                .contains("operationId: listProviderInspectionReservations")
                .contains("operationId: getProviderInspectionReservationSummary")
                // 2.1.0：登录账号读取自己的展示资料
                .contains("/api/v1/auth/me:")
                // 2.4.0：刷新令牌续期和退出登录
                .contains("/api/v1/auth/refresh:")
                .contains("operationId: refreshLogin")
                .contains("/api/v1/auth/logout:")
                .contains("required: [accessToken, tokenType, expiresInSeconds, refreshToken, refreshExpiresInSeconds]")
                .contains("operationId: getCurrentAccount")
                .contains("INS_VISIT_STATE_INVALID")
                .contains("INS_VISIT_OUT_OF_ORDER")
                .contains("ServiceVisitStatus:")
                .contains("enum: [PENDING_SCHEDULE, SCHEDULED, IN_PROGRESS, REWORK, DELIVERED, ACCEPTED]")
                // 2.5.0（S4-1）：付款节点、取消、改期、退回整改。
                .contains("enum: [AWAITING_PAYMENT, ARRANGING, IN_PROGRESS, DELIVERED, ACCEPTED, CANCELLED]")
                .contains("enum: [PUBLISHED, RETURNED, ACCEPTED]")
                .contains("INS_PROVIDER_PAYMENT_UNAVAILABLE")
                .contains("INS_PAYMENT_NOT_DUE")
                .contains("INS_ORDER_NOT_CANCELLABLE")
                .contains("INS_RESCHEDULE_NOT_ALLOWED")
                .contains("INS_RESCHEDULE_STATE_INVALID");
    }

    private static List<String> routes(Class<?> controller) {
        List<String> routes = new ArrayList<>();
        for (Method method : controller.getDeclaredMethods()) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            PostMapping post = method.getAnnotation(PostMapping.class);
            PutMapping put = method.getAnnotation(PutMapping.class);
            if (get != null) {
                routes.add(get.value()[0]);
            } else if (post != null) {
                routes.add(post.value()[0]);
            } else if (put != null) {
                routes.add(put.value()[0]);
            }
        }
        return routes;
    }
}
