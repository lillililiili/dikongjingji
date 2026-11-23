package com.lowaltitude.inspection.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/** S4-1 付款节点的 SQL 约定（真实库验证见 infra/scripts/trade-smoke/inspection.cjs）。 */
class InspectionPaymentMapperSqlTest {

    // 交易回调推进服务单时没有操作人：不能把 NOT NULL 的 updated_by 写成 null。
    @Test
    void aCallbackWithoutAnActorKeepsTheLastUpdater() throws Exception {
        Method method = InspectionPaymentMapper.class.getMethod("updateOrderStatus",
                UUID.class, String.class, String.class, UUID.class, Instant.class);

        assertThat(String.join(" ", method.getAnnotation(Update.class).value()))
                .contains("updated_by = COALESCE(#{actorId}, updated_by)")
                .contains("AND status = #{expectedStatus}");
    }

    // 节点只记一次已付、退款只记一次；付款尝试只有待付的能关、待付或关闭的能记为已付。
    @Test
    void writesAreGuardedByTheCurrentState() {
        assertThat(update("markNodePaid")).contains("AND paid_at IS NULL");
        assertThat(update("markNodeRefunded")).contains("AND refund_id IS NULL").contains("AND paid_at IS NOT NULL");
        assertThat(update("markAttemptClosed")).contains("AND status = 'OPEN'");
        assertThat(update("markAttemptPaid")).contains("AND status IN ('OPEN', 'CLOSED')");
        assertThat(update("rescheduleVisit"))
                .contains("original_planned_date = COALESCE(original_planned_date, planned_date)")
                .contains("planned_start_at = NULL")
                .contains("AND status IN ('PENDING_SCHEDULE', 'SCHEDULED')");
        assertThat(update("decideReschedule")).contains("AND status = 'REQUESTED'");
        assertThat(update("republishDelivery")).contains("revision = revision + 1").contains("AND status = 'RETURNED'");
        assertThat(select("findLiveAttempt")).contains("a.status IN ('OPEN', 'PAID')");
    }

    private static String update(String name) {
        return String.join(" ", method(name).getAnnotation(Update.class).value());
    }

    private static String select(String name) {
        return String.join(" ", method(name).getAnnotation(Select.class).value());
    }

    private static Method method(String name) {
        return Arrays.stream(InspectionPaymentMapper.class.getMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
