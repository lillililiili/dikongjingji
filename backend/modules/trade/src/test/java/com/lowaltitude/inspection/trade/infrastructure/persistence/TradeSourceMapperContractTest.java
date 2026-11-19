package com.lowaltitude.inspection.trade.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

/**
 * 业务来源订单（E09）的 SQL 文本：只用绑定参数；建的订单不发货、没有商家类型，幂等键就是业务单 ID；
 * 给业务模块的锁只锁非商城订单这一行；列清单和行的字段一一对应。
 */
class TradeSourceMapperContractTest {

    @Test
    void everyMethodCarriesOneStatementThatOnlyBindsParameters() {
        Map<String, String> statements = statements();

        assertThat(statements).hasSize(TradeSourceMapper.class.getDeclaredMethods().length).hasSize(6);
        assertThat(statements.values()).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
    }

    @Test
    void aSourceOrderHasNoDeliveryNoMerchantKindAndItsKeyIsTheSourceReference() {
        String sql = normalized(sql("insertSourceOrder"));

        assertThat(sql)
                .contains("INSERT INTO trade_order ( order_no, buyer_id, merchant_id, merchant_kind, merchant_name,"
                        + " sub_mchid, source, source_ref, city_code, delivery_mode, goods_minor, shipping_minor,"
                        + " payable_minor, pay_before, idempotency_key, request_hash, created_at, updated_at )")
                .contains("VALUES ( #{orderNo}, #{buyerId}, #{merchantId}, NULL, #{merchantName}, #{subMchid},"
                        + " #{source}, #{sourceRef}, #{cityCode}, 'NONE', #{goodsMinor}, 0, #{goodsMinor},"
                        + " #{payBefore}, #{sourceRef}, #{requestHash}, #{at}, #{at} )")
                .endsWith("RETURNING id");
        // INSERT … RETURNING 跑在 @Select 上，要显式清会话缓存，同一事务里之后的读才看得到这张单。
        assertThat(method("insertSourceOrder").getAnnotation(Options.class).flushCache())
                .isEqualTo(Options.FlushCachePolicy.TRUE);
    }

    @Test
    void ordersAreFoundBySourceReferenceOrIdAndOnlyNonMallOnesAreLockedForASource() {
        assertThat(where("findBySourceRef")).isEqualTo("WHERE o.source = #{source} AND o.source_ref = #{sourceRef}");
        assertThat(where("findSourceOrder")).isEqualTo("WHERE o.id = #{orderId}");
        assertThat(where("lockSourceOrder")).isEqualTo("WHERE o.id = #{orderId} AND o.source <> 'MALL' FOR UPDATE");
        assertThat(normalized(sql("lockSourceOrder"))).doesNotContain("JOIN");
        for (String method : List.of("findBySourceRef", "findSourceOrder", "lockSourceOrder")) {
            assertThat(sql(method)).as(method).startsWith(TradeSourceMapper.SOURCE_ORDER_COLUMNS);
        }
        assertThat(columns(TradeSourceMapper.SOURCE_ORDER_COLUMNS, "o."))
                .containsExactlyElementsOf(snakeCase(TradeSourceMapper.SourceOrderRow.class));
    }

    @Test
    void theMerchantNameAndRefundStatesAreReadByTheirIds() {
        assertThat(normalized(sql("findOrganizationName")))
                .isEqualTo("SELECT organization_name FROM identity_organization WHERE id = #{organizationId}");
        String refunds = normalized(sql("findRefunds"));
        assertThat(refunds)
                .startsWith("<script>")
                .contains("FROM trade_refund WHERE id IN <foreach collection=\"refundIds\" item=\"id\" open=\"(\""
                        + " separator=\",\" close=\")\">#{id}</foreach>");
        assertThat(columns(refunds.replace("<script>", ""), ""))
                .containsExactlyElementsOf(snakeCase(TradeSourceMapper.SourceRefundRow.class));
    }

    // ---- 辅助 ----

    private static String where(String methodName) {
        String text = normalized(sql(methodName));
        return text.substring(text.indexOf("WHERE"));
    }

    private static Method method(String name) {
        return Arrays.stream(TradeSourceMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, String> statements() {
        Map<String, String> statements = new LinkedHashMap<>();
        for (Method method : TradeSourceMapper.class.getDeclaredMethods()) {
            Select select = method.getAnnotation(Select.class);
            if (select != null) {
                statements.put(method.getName(), String.join("\n", select.value()).strip());
            }
        }
        return statements;
    }

    private static String sql(String methodName) {
        String sql = statements().get(methodName);
        assertThat(sql).as(methodName).isNotNull();
        return sql;
    }

    private static List<String> columns(String select, String alias) {
        String text = normalized(select);
        String list = text.substring(text.indexOf("SELECT") + "SELECT".length(),
                text.contains(" FROM ") ? text.indexOf(" FROM ") : text.length());
        return Arrays.stream(list.split(","))
                .map(String::trim)
                .map(column -> alias.isEmpty() ? column : column.replace(alias, ""))
                .toList();
    }

    private static List<String> snakeCase(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName().replaceAll("([A-Z])", "_$1").toLowerCase())
                .toList();
    }

    private static String normalized(String sql) {
        return Arrays.stream(sql.split("\n"))
                .map(line -> line.contains("--") ? line.substring(0, line.indexOf("--")) : line)
                .collect(Collectors.joining(" "))
                .replaceAll("\\s+", " ")
                .trim();
    }
}
