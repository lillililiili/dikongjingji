package com.lowaltitude.inspection.trade.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/**
 * 售后（S4-2）的 SQL 文本检查：只用绑定参数；买家、商家的读都以本人或本机构为条件；锁售后单只锁售后表的行；
 * 定时任务只取到了时限的。真实执行由本机冒烟（infra/scripts/trade-smoke/aftersale.cjs）覆盖。
 */
class TradeAftersaleMapperContractTest {

    @Test
    void everyMethodCarriesOneStatementThatOnlyBindsParameters() {
        Map<String, String> statements = statements();

        assertThat(statements).hasSize(TradeAftersaleMapper.class.getDeclaredMethods().length);
        assertThat(statements.values()).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
        assertThat(statements).allSatisfy((method, sql) -> {
            if (sql.contains("<foreach") || sql.contains("<if")) {
                assertThat(sql).as(method).startsWith("<script>").contains("</script>");
            }
        });
    }

    @Test
    void buyersAndMerchantsOnlyReadTheirOwn() {
        assertThat(normalized("findBuyerAftersale")).contains("WHERE a.id = #{id} AND a.buyer_id = #{buyerId}");
        assertThat(normalized("findMerchantAftersale")).contains("WHERE a.id = #{id} AND a.merchant_id = #{merchantId}");
        assertThat(normalized("findBuyerAftersales")).contains("WHERE a.buyer_id = #{buyerId}");
        assertThat(normalized("findMerchantAftersales")).contains("WHERE a.merchant_id = #{merchantId}");
        assertThat(normalized("findByKey")).contains("a.buyer_id = #{buyerId} AND a.idempotency_key = #{idempotencyKey}");
    }

    @Test
    void lockingAnAftersaleOnlyLocksItsOwnRow() {
        // 订单行由退款的映射先锁（先订单、后售后单），这里只锁售后表，免得和别的事务的锁顺序反过来。
        assertThat(normalized("lockAftersale")).endsWith("FOR UPDATE OF a");
        assertThat(normalized("lockByRefund")).contains("WHERE a.refund_id = #{refundId} FOR UPDATE OF a");
    }

    @Test
    void theDeadlineJobOnlyTakesWhatIsDue() {
        assertThat(normalized("findDue")).contains("WHERE due_at IS NOT NULL AND due_at <= #{now}")
                .contains("LIMIT #{limit}");
    }

    @Test
    void anImageIsAttachedOnlyOnceAndCountedPerUploader() {
        assertThat(normalized("attachImage")).contains("WHERE id = #{imageId} AND aftersale_id IS NULL");
        assertThat(normalized("countRecentUploads"))
                .contains("WHERE uploaded_by = #{uploadedBy} AND created_at >= #{since}");
    }

    private static String normalized(String method) {
        return statements().get(method).replaceAll("\\s+", " ").trim();
    }

    private static Map<String, String> statements() {
        return Arrays.stream(TradeAftersaleMapper.class.getDeclaredMethods())
                .collect(Collectors.toMap(Method::getName, TradeAftersaleMapperContractTest::statement));
    }

    private static String statement(Method method) {
        Select select = method.getAnnotation(Select.class);
        if (select != null) {
            assertThat(select.value()).as(method.getName()).hasSize(1);
            return select.value()[0];
        }
        Update update = method.getAnnotation(Update.class);
        assertThat(update).as(method.getName()).isNotNull();
        assertThat(update.value()).as(method.getName()).hasSize(1);
        return update.value()[0];
    }
}
