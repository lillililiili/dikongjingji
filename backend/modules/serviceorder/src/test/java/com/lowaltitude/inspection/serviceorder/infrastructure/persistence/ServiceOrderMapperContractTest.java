package com.lowaltitude.inspection.serviceorder.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/**
 * S4-3 的 SQL 文本和 V001 迁移：只用绑定参数、客户按账号、服务商按机构限定、状态变化是条件更新、加锁，以及迁移里的约束。
 * 规则由 ServiceOrderServiceTest 用内存 mapper 验证，真实库上由冒烟脚本 service-order.cjs 验证。
 */
class ServiceOrderMapperContractTest {

    @Test
    void everyStatementUsesBoundParametersOnly() {
        List<String> statements = Arrays.stream(ServiceOrderMapper.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic() && !method.isDefault())
                .map(ServiceOrderMapperContractTest::sql)
                .toList();

        assertThat(statements).hasSize(41);
        assertThat(statements).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
    }

    @Test
    void customerAndProviderReadsAreScopedAndLocksAreExplicit() throws Exception {
        for (String name : List.of("findOrder", "lockOrder", "countOrders", "findOrderPage")) {
            assertThat(sql(method(name))).contains("o.account_id = #{accountId}");
        }
        for (String name : List.of("findProviderOrder", "lockProviderOrder", "countProviderOrders",
                "findProviderOrderPage")) {
            assertThat(sql(method(name))).contains("o.provider_id = #{providerId}");
        }
        for (String name : List.of("lockOrder", "lockProviderOrder", "lockOrderById")) {
            assertThat(sql(method(name))).endsWith("FOR UPDATE OF o");
        }
        // 交易回调：付款尝试和交易订单一起对上才算。
        assertThat(sql(method("findPayment"))).contains("id = #{attemptId} AND trade_order_id = #{tradeOrderId}");
        // 下载令牌：没过期、文件已交付。
        assertThat(sql(method("findDownload"))).contains("t.expires_at > #{now}").contains("f.delivery_id IS NOT NULL");
    }

    @Test
    void stateChangesAreConditional() throws Exception {
        assertThat(sql(method("setPlan"))).contains("status IN ('AWAITING_PLAN', 'PLAN_PENDING')")
                .contains("version = version + 1");
        assertThat(sql(method("moveOrder"))).contains("AND status = #{fromStatus}")
                .contains("<if test=\"toStatus == 'AWAITING_START'\">paid_at = #{at},</if>");
        assertThat(sql(method("closeOrder"))).contains("AND status = #{fromStatus}");
        assertThat(sql(method("confirmPlan"))).contains("confirmed_at IS NULL");
        assertThat(sql(method("reviewDelivery"))).contains("status = 'SUBMITTED'");
        assertThat(sql(method("markPaymentPaid"))).contains("status IN ('PENDING', 'CLOSED') AND refused = false");
        assertThat(sql(method("attachFiles"))).contains("delivery_id IS NULL");
        assertThat(sql(method("deleteStagedFile"))).contains("delivery_id IS NULL");
    }

    @Test
    void theMigrationKeepsOnePendingAndOnePaidAttemptAndAuditTrail() throws IOException {
        String sql = migration();
        assertThat(sql).contains("CREATE UNIQUE INDEX uq_service_order_payment_pending ON service_order_payment (order_id) WHERE status = 'PENDING'")
                .contains("CREATE UNIQUE INDEX uq_service_order_payment_paid ON service_order_payment (order_id) WHERE status = 'PAID'")
                .contains("demand_id uuid NOT NULL UNIQUE")
                .contains("CHECK (type IN ('AERIAL', 'SURVEY', 'MAINTENANCE', 'TECH_SUPPORT'))")
                .contains("CONSTRAINT ck_service_order_terminated CHECK (status <> 'TERMINATED' OR closed_by = 'PROVIDER')")
                .contains("CREATE TABLE service_order_event");
    }

    private static String migration() throws IOException {
        try (InputStream in = ServiceOrderMapperContractTest.class
                .getResourceAsStream("/db/migration/serviceorder/V001__service_order.sql")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Method method(String name) {
        return Arrays.stream(ServiceOrderMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static String sql(Method method) {
        for (Class<? extends Annotation> type : List.of(Select.class, Insert.class, Update.class, Delete.class)) {
            Annotation annotation = method.getAnnotation(type);
            if (annotation != null) {
                try {
                    String[] value = (String[]) type.getMethod("value").invoke(annotation);
                    return String.join(" ", value).strip();
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException(exception);
                }
            }
        }
        throw new IllegalStateException("no SQL on " + method.getName());
    }
}
