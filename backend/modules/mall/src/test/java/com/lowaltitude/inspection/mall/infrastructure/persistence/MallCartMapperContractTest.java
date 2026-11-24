package com.lowaltitude.inspection.mall.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/**
 * 没有可用的数据库测试容器，按现有做法检查 SQL 文本：只用绑定参数；购物车的每条语句都以账号为条件；
 * 同一个供给再加一次在 SQL 里合并数量并封顶 99；读出的列和行记录一一对应。再检查 V002 迁移脚本的约束。
 * 真实执行由 dev 环境启动和接口联调覆盖。
 */
class MallCartMapperContractTest {

    @Test
    void everyStatementBindsItsParametersAndNeverInterpolatesText() {
        Map<String, String> statements = statements();

        // 接口里每个方法都带着一条语句，没有漏掉注解的。
        assertThat(statements).hasSize(MallCartMapper.class.getDeclaredMethods().length);
        assertThat(statements).hasSize(8);
        assertThat(statements.values()).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
    }

    @Test
    void everyCartStatementIsLimitedToTheAccount() {
        // 加购前按账号取事务锁，同一账号的加购排队。
        assertThat(normalized(sql("lockOwner")))
                .contains("pg_advisory_xact_lock(hashtextextended(#{lockName} || CAST(#{accountId} AS text), 0))");
        for (String method : List.of("findCart", "countLines", "countOffer", "setQuantity", "remove")) {
            assertThat(normalized(sql(method))).as(method).contains("account_id = #{accountId}");
        }
        assertThat(normalized(sql("findCart"))).contains("WHERE item.account_id = #{accountId}");
        assertThat(normalized(sql("setQuantity")))
                .contains("WHERE id = #{cartItemId} AND account_id = #{accountId}")
                .contains("version = version + 1")
                .contains("updated_at = #{at}");
        assertThat(normalized(sql("countOffer")))
                .contains("WHERE account_id = #{accountId} AND offer_id = #{offerId}");
        // 删除用 foreach 绑定每个 ID，不拼字符串。
        assertThat(normalized(sql("remove")))
                .startsWith("<script>")
                .endsWith("</script>")
                .contains("DELETE FROM mall_cart_item WHERE account_id = #{accountId} AND id IN")
                .contains("<foreach collection=\"cartItemIds\" item=\"id\" open=\"(\" separator=\",\" close=\")\">"
                        + " #{id} </foreach>");
        // 新行的账号只来自参数。
        assertThat(normalized(sql("add")))
                .contains("INSERT INTO mall_cart_item (account_id, offer_id, quantity, created_at, updated_at)")
                .contains("VALUES (#{accountId}, #{offerId}, #{quantity}, #{at}, #{at})");
    }

    @Test
    void addingTheSameOfferAgainMergesTheQuantityAndCapsItAtNinetyNine() {
        assertThat(normalized(sql("add")))
                .contains("ON CONFLICT (account_id, offer_id) DO UPDATE")
                .contains("SET quantity = LEAST(99, mall_cart_item.quantity + EXCLUDED.quantity)")
                .contains("version = mall_cart_item.version + 1")
                .contains("updated_at = EXCLUDED.updated_at")
                .contains("RETURNING id cart_item_id, quantity");
    }

    @Test
    void cartLinesCarryTheCurrentStateOfTheirOfferAndAreReadMostRecentFirst() {
        assertThat(normalized(sql("findCart")))
                .contains("JOIN LATERAL (")
                .contains("WHERE offer.id = item.offer_id ) offer_view ON true")
                .contains("ORDER BY item.updated_at DESC, item.id ASC");
        assertThat(sql("findCart")).contains(MallCartMapper.OFFER_COLUMNS);
        assertThat(normalized(sql("findOffer"))).endsWith("WHERE offer.id = #{offerId}");
        // 状态不在 SQL 里过滤：能不能买、为什么不能买由服务按当前城市判断。
        assertThat(MallCartMapper.OFFER_COLUMNS)
                .doesNotContain("WHERE")
                // 商家资质没审核通过（身份 V013）的供给当作已停用，页面显示失效。
                .contains("CASE WHEN merchant.listing_suspended THEN 'DISABLED' ELSE offer.status END offer_status")
                .contains("sku.status sku_status")
                .contains("p.status product_status")
                .contains("offer.city_code offer_city_code")
                .contains("offer.price_amount_minor unit_amount_minor")
                .contains("offer.express_available")
                .contains("offer.pickup_point")
                .contains("offer.shipping_fee_minor");
    }

    // 文本块拼接时两段之间漏掉空白，会把关键字粘在一起（巡检目录出过 "1FROM"）。
    @Test
    void assembledQueriesKeepTheirKeywordsApart() {
        for (String method : List.of("findOffer", "findCart")) {
            assertThat(sql(method))
                    .as(method)
                    .doesNotContainPattern("[0-9a-z_)]FROM ")
                    .doesNotContainPattern("[0-9a-z_)]WHERE ")
                    .doesNotContainPattern("[0-9a-z_)]JOIN ")
                    .doesNotContainPattern("[0-9a-z_)]ORDER BY");
        }
    }

    // 列别名按下划线转驼峰后正好是行记录的字段，顺序也一致（MyBatis 按构造参数映射）。
    @Test
    void selectedColumnsMatchTheRowRecordsInOrder() {
        List<String> offerColumns = aliases(MallCartMapper.OFFER_COLUMNS);

        assertThat(offerColumns).containsExactlyElementsOf(fields(MallCartMapper.OfferRow.class));
        List<String> cartColumns = new java.util.ArrayList<>(List.of("cartItemId", "quantity", "updatedAt"));
        cartColumns.addAll(offerColumns);
        assertThat(cartColumns).containsExactlyElementsOf(fields(MallCartMapper.CartRow.class));
        assertThat(fields(MallCartMapper.AddedRow.class)).containsExactly("cartItemId", "quantity");
    }

    @Test
    void migrationAddsDeliveryColumnsAndAOneLinePerOfferCart() throws Exception {
        String migration = normalized(migration());

        assertThat(migration)
                .contains("ALTER TABLE mall_offer ADD COLUMN express_available boolean NOT NULL DEFAULT true,"
                        + " ADD COLUMN pickup_point varchar(120),"
                        + " ADD COLUMN shipping_fee_minor bigint NOT NULL DEFAULT 0 CHECK (shipping_fee_minor >= 0);")
                // 快递和到店自提至少有一种。
                .contains("ADD CONSTRAINT ck_mall_offer_fulfilment CHECK (express_available OR pickup_point IS NOT NULL)")
                .contains("CREATE TABLE mall_cart_item (")
                .contains("account_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT")
                .contains("offer_id uuid NOT NULL REFERENCES mall_offer(id) ON DELETE RESTRICT")
                .contains("quantity integer NOT NULL CHECK (quantity BETWEEN 1 AND 99)")
                .contains("version integer NOT NULL DEFAULT 0 CHECK (version >= 0)")
                .contains("created_at timestamptz NOT NULL DEFAULT now()")
                .contains("updated_at timestamptz NOT NULL DEFAULT now()")
                .contains("UNIQUE (account_id, offer_id)")
                .contains("CREATE INDEX idx_mall_cart_item_account ON mall_cart_item (account_id, updated_at DESC, id)");
        // 购物车不存价格，也不建订单表；迁移不带数据。
        String cartTable = migration.substring(migration.indexOf("CREATE TABLE mall_cart_item"));
        assertThat(cartTable).doesNotContain("amount").doesNotContain("price");
        assertThat(migration).doesNotContain("mall_order").doesNotContain("INSERT INTO");
    }

    private static List<String> aliases(String select) {
        String columns = normalized(select);
        columns = columns.substring(columns.indexOf("SELECT ") + "SELECT ".length(), columns.indexOf(" FROM "));
        return Arrays.stream(columns.split(","))
                .map(String::trim)
                .map(column -> column.contains(" ")
                        ? column.substring(column.lastIndexOf(' ') + 1)
                        : column.substring(column.indexOf('.') + 1))
                .map(MallCartMapperContractTest::camel)
                .toList();
    }

    private static String camel(String snake) {
        StringBuilder camel = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                camel.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return camel.toString();
    }

    private static List<String> fields(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    private static Map<String, String> statements() {
        Map<String, String> statements = new LinkedHashMap<>();
        for (Method method : MallCartMapper.class.getDeclaredMethods()) {
            Select select = method.getAnnotation(Select.class);
            Update update = method.getAnnotation(Update.class);
            Delete delete = method.getAnnotation(Delete.class);
            String[] text = select != null
                    ? select.value()
                    : update != null ? update.value() : delete != null ? delete.value() : null;
            if (text != null) {
                statements.put(method.getName(), String.join("\n", text));
            }
        }
        return statements;
    }

    private static String sql(String methodName) {
        String sql = statements().get(methodName);
        assertThat(sql).as(methodName).isNotNull();
        return sql;
    }

    private String migration() throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/migration/mall/V002__mall_cart.sql")) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 去掉注释、把连续空白并成一个空格，换行和缩进的调整不影响断言。 */
    private static String normalized(String sql) {
        return Arrays.stream(sql.split("\n"))
                .map(line -> line.contains("--") ? line.substring(0, line.indexOf("--")) : line)
                .collect(Collectors.joining(" "))
                .replaceAll("\\s+", " ")
                .trim();
    }
}
