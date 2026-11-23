package com.lowaltitude.inspection.account.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/**
 * 没有可用的数据库测试容器，按现有做法检查 SQL 文本：只用绑定参数；每条语句都以地址的主人为条件，读和改只碰有效地址；
 * 再检查 V001 迁移脚本的约束。真实执行由 dev 环境启动和接口联调覆盖。
 */
class AccountAddressMapperContractTest {

    @Test
    void everyStatementBindsItsParametersAndNeverInterpolatesText() {
        Map<String, String> statements = statements();

        // 接口里每个方法都带着一条语句，没有漏掉注解的。
        assertThat(statements).hasSize(AccountAddressMapper.class.getDeclaredMethods().length);
        assertThat(statements).hasSize(11);
        assertThat(statements.values()).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
    }

    @Test
    void everyStatementIsLimitedToTheOwnersActiveAddresses() {
        Map<String, String> conditioned = new LinkedHashMap<>(statements());
        // 插入语句没有条件，单独在下一个用例里检查；加锁语句只按账号取锁，在后面单独检查。
        conditioned.remove("insert");
        conditioned.remove("lockOwner");
        // 注销去标识（S5-8）连已删除的地址一起清，单独检查。
        conditioned.remove("eraseAll");
        assertThat(normalized(sql("eraseAll")))
                .contains("recipient_name = '已注销用户'")
                .contains("phone = '13000000000'")
                .endsWith("WHERE owner_id = #{ownerId}");

        assertThat(conditioned).hasSize(8);
        assertThat(conditioned).allSatisfy((method, sql) -> assertThat(normalized(sql))
                .as(method)
                .contains("owner_id = #{ownerId}")
                .contains("status = 'ACTIVE'"));
        assertThat(AccountAddressMapper.COLUMNS)
                .contains("WHERE a.owner_id = #{ownerId}")
                .contains("AND a.status = 'ACTIVE'");
        for (String method : List.of("findAll", "findOne", "findDefault")) {
            assertThat(sql(method)).as(method).startsWith(AccountAddressMapper.COLUMNS);
        }
        // 修改和删除都按 ID 加主人定位，别人的地址改不到。
        for (String method : List.of("update", "delete")) {
            assertThat(normalized(sql(method)))
                    .as(method)
                    .contains("WHERE id = #{addressId} AND owner_id = #{ownerId} AND status = 'ACTIVE'");
        }
        assertThat(normalized(sql("findOne"))).contains("AND a.id = #{addressId}");
        assertThat(normalized(sql("findDefault"))).contains("AND a.is_default");
        assertThat(normalized(sql("count"))).contains("SELECT count(*) FROM account_address");
    }

    @Test
    void writesQueueOnATransactionLockOfTheOwner() {
        assertThat(normalized(sql("lockOwner")))
                .contains("pg_advisory_xact_lock(hashtextextended(#{lockName} || CAST(#{ownerId} AS text), 0))");
    }

    @Test
    void theOwnerOfANewAddressComesOnlyFromTheParameter() {
        assertThat(normalized(sql("insert")))
                .contains("INSERT INTO account_address ( owner_id, recipient_name, phone,")
                .contains("VALUES ( #{ownerId}, #{recipientName}, #{phone},")
                .contains("#{detail}, #{isDefault}, #{at}, #{at} )")
                .contains("RETURNING id");
        // 所属账号只有绑定参数一个来源，不从别的行里复制。
        assertThat(sql("insert")).doesNotContain("SELECT");
    }

    @Test
    void defaultAddressesAreSwitchedAndPromotedWithinTheOwner() {
        assertThat(normalized(sql("clearDefault")))
                .contains("SET is_default = false")
                .contains("WHERE owner_id = #{ownerId} AND status = 'ACTIVE' AND is_default");
        assertThat(normalized(sql("delete")))
                .contains("SET status = 'DELETED', is_default = false");
        // 删掉默认后只在还没有默认时，把最早的一条设为默认。
        assertThat(normalized(sql("promoteOldestToDefault")))
                .contains("SET is_default = true")
                .contains("ORDER BY created_at ASC, id ASC LIMIT 1")
                .contains("AND NOT EXISTS ( SELECT 1 FROM account_address WHERE owner_id = #{ownerId}"
                        + " AND status = 'ACTIVE' AND is_default )");
        assertThat(normalized(sql("findAll")))
                .contains("ORDER BY a.is_default DESC, a.created_at ASC, a.id ASC");
    }

    @Test
    void everyChangeBumpsTheVersionAndTheUpdateTime() {
        for (String method : List.of("clearDefault", "update", "delete", "promoteOldestToDefault")) {
            assertThat(normalized(sql(method)))
                    .as(method)
                    .contains("version = version + 1")
                    .contains("updated_at = #{at}");
        }
    }

    @Test
    void migrationKeepsOneDefaultPerOwnerAndChecksThePhone() throws Exception {
        String migration = normalized(migration());

        assertThat(migration)
                .contains("CREATE TABLE account_address (")
                .contains("owner_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT")
                .contains("recipient_name varchar(20) NOT NULL CHECK (btrim(recipient_name) <> '')")
                .contains("phone varchar(11) NOT NULL CHECK (phone ~ '^1[3-9][0-9]{9}$')")
                .contains("detail varchar(80) NOT NULL CHECK (btrim(detail) <> '')")
                .contains("is_default boolean NOT NULL DEFAULT false")
                .contains("status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DELETED'))")
                .contains("version integer NOT NULL DEFAULT 0 CHECK (version >= 0)")
                .contains("created_at timestamptz NOT NULL DEFAULT now()")
                .contains("updated_at timestamptz NOT NULL DEFAULT now()")
                // 一个账号最多一条有效的默认地址：部分唯一索引。
                .contains("CREATE UNIQUE INDEX uq_account_address_default ON account_address (owner_id)"
                        + " WHERE is_default AND status = 'ACTIVE'")
                .contains("CREATE INDEX idx_account_address_owner ON account_address (owner_id, created_at, id)"
                        + " WHERE status = 'ACTIVE'");
        // 地区只到地级市，表里没有区县列；迁移不带数据。
        assertThat(migration).doesNotContain("district").doesNotContain("INSERT INTO");
    }

    private static Map<String, String> statements() {
        Map<String, String> statements = new LinkedHashMap<>();
        for (Method method : AccountAddressMapper.class.getDeclaredMethods()) {
            Select select = method.getAnnotation(Select.class);
            Update update = method.getAnnotation(Update.class);
            String[] text = select != null ? select.value() : update != null ? update.value() : null;
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
        try (var stream = getClass().getResourceAsStream("/db/migration/account/V001__account_address.sql")) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 去掉注释、把连续空白并成一个空格，换行和缩进的调整不影响断言。 */
    private static String normalized(String sql) {
        return Arrays.stream(sql.split("\n"))
                .map(line -> line.contains("--") ? line.substring(0, line.indexOf("--")) : line)
                .collect(Collectors.joining(" "))
                .replaceAll("\\s+", " ");
    }
}
