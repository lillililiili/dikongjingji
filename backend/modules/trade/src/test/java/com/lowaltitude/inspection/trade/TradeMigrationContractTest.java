package com.lowaltitude.inspection.trade;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 没有数据库测试容器，按现有做法检查迁移文本里的关键约束；真实执行由本地后端启动时的迁移覆盖。
 */
class TradeMigrationContractTest {

    private static String migration() throws IOException {
        return migration("/db/migration/trade/V001__trade.sql");
    }

    private static String migration(String path) throws IOException {
        try (InputStream stream = TradeMigrationContractTest.class.getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        }
    }

    private static String fulfilment() throws IOException {
        return migration("/db/migration/trade/V002__trade_fulfilment.sql");
    }

    private static String reconciliation() throws IOException {
        return migration("/db/migration/trade/V003__trade_reconciliation.sql");
    }

    /** 去掉 SQL 注释（注释里的说明文字不算语句）。 */
    private static String statementsOf(String path) throws IOException {
        try (InputStream stream = TradeMigrationContractTest.class.getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .map(line -> line.contains("--") ? line.substring(0, line.indexOf("--")) : line)
                    .reduce("", (left, right) -> left + " " + right)
                    .replaceAll("\\s+", " ")
                    .trim();
        }
    }

    // ---- V005：业务来源（E09 收费活动报名） ----

    // 来源加 ACTIVITY；业务单 ID 商城为空、其余必填并且同一来源唯一；非商城订单只能不发货；商家类型只在商城必填。
    @Test
    void sourceOrdersCarryTheirBusinessReferenceAndNoDelivery() throws Exception {
        String sql = statementsOf("/db/migration/trade/V005__trade_sources.sql");

        assertThat(sql)
                .contains("ADD CONSTRAINT trade_order_source_check CHECK (source IN ('MALL', 'ACTIVITY'));")
                .contains("ALTER TABLE trade_order ADD COLUMN source_ref uuid;")
                .contains("CHECK ((source = 'MALL') = (source_ref IS NULL));")
                .contains("CREATE UNIQUE INDEX uq_trade_order_source_ref ON trade_order (source, source_ref)"
                        + " WHERE source_ref IS NOT NULL;")
                .contains("CHECK (delivery_mode IN ('EXPRESS', 'PICKUP', 'NONE'));")
                .contains("CHECK ((source = 'MALL') = (delivery_mode <> 'NONE'));")
                .contains("ALTER TABLE trade_order ALTER COLUMN merchant_kind DROP NOT NULL;")
                .contains("CHECK (source <> 'MALL' OR merchant_kind IS NOT NULL);");
        // 业务单不加外键：业务表属于别的模块。
        assertThat(sql).doesNotContain("REFERENCES");
        // 改的都是 V001 里自动命名的约束。
        assertThat(migration())
                .contains("source varchar(16) NOT NULL CHECK (source IN ('MALL')),")
                .contains("delivery_mode varchar(16) NOT NULL CHECK (delivery_mode IN ('EXPRESS', 'PICKUP')),");
    }

    // 退款发起方加 SOURCE，只有它可以不记操作人（付款到账时业务收不下的自动退款）；状态变化原因加 SOURCE_REFUND，
    // 其余原因和 V002 一样。
    @Test
    void sourceRefundsMayHaveNoRequesterAndHaveTheirOwnCause() throws Exception {
        String sql = statementsOf("/db/migration/trade/V005__trade_sources.sql");

        assertThat(sql)
                .contains("CHECK (initiator IN ('BUYER_CANCEL', 'MERCHANT', 'SOURCE'));")
                .contains("ALTER TABLE trade_refund ALTER COLUMN requested_by DROP NOT NULL;")
                .contains("CHECK (requested_by IS NOT NULL OR initiator = 'SOURCE');")
                .contains("'REFUND_QUERIED', 'MERCHANT_REFUND', 'REFUND_REJECTED', 'SOURCE_REFUND'));");
        assertThat(fulfilment()).contains("'REFUND_QUERIED', 'MERCHANT_REFUND', 'REFUND_REJECTED'));");
        // 已有的数据都是商城订单，不用改数据。
        assertThat(sql).doesNotContain("UPDATE ").doesNotContain("DELETE ");
    }

    // ---- V006：到馆预约（E10） ----

    // 只放开来源的取值；不发货、必带业务单这些 V005 的约束对 VENUE 同样成立。
    @Test
    void venueBookingsAreTheThirdSource() throws Exception {
        String sql = statementsOf("/db/migration/trade/V006__trade_venue_source.sql");

        assertThat(sql).isEqualTo("ALTER TABLE trade_order DROP CONSTRAINT trade_order_source_check;"
                + " ALTER TABLE trade_order ADD CONSTRAINT trade_order_source_check"
                + " CHECK (source IN ('MALL', 'ACTIVITY', 'VENUE'));");
        assertThat(migration("/db/migration/trade/V005__trade_sources.sql"))
                .contains("CHECK ((source = 'MALL') = (source_ref IS NULL))")
                .contains("CHECK ((source = 'MALL') = (delivery_mode <> 'NONE'))");
    }

    // ---- V007：设备租赁（F3） ----

    @Test
    void rentalOrdersAreTheFourthSource() throws Exception {
        String sql = statementsOf("/db/migration/trade/V007__trade_rental_source.sql");

        assertThat(sql).isEqualTo("ALTER TABLE trade_order DROP CONSTRAINT trade_order_source_check;"
                + " ALTER TABLE trade_order ADD CONSTRAINT trade_order_source_check"
                + " CHECK (source IN ('MALL', 'ACTIVITY', 'VENUE', 'RENTAL'));");
    }

    // ---- V011：通用服务订单（S4-3） ----

    @Test
    void generalServiceOrdersAreASourceAndKeepTheInspectionSource() throws Exception {
        String sql = statementsOf("/db/migration/trade/V011__trade_pro_service_source.sql");

        assertThat(sql).isEqualTo("ALTER TABLE trade_order DROP CONSTRAINT trade_order_source_check;"
                + " ALTER TABLE trade_order ADD CONSTRAINT trade_order_source_check"
                + " CHECK (source IN ('MALL', 'ACTIVITY', 'VENUE', 'RENTAL', 'INSPECTION', 'PRO_SERVICE'));");
    }

    // ---- V008：巡检付款节点（S4-1） ----

    @Test
    void inspectionPaymentNodesAreTheFifthSource() throws Exception {
        String sql = statementsOf("/db/migration/trade/V008__trade_inspection_source.sql");

        assertThat(sql).isEqualTo("ALTER TABLE trade_order DROP CONSTRAINT trade_order_source_check;"
                + " ALTER TABLE trade_order ADD CONSTRAINT trade_order_source_check"
                + " CHECK (source IN ('MALL', 'ACTIVITY', 'VENUE', 'RENTAL', 'INSPECTION'));");
    }

    // ---- V004：商家名称长度 ----

    // 订单上的商家名称快照来自 identity_organization.organization_name（160），V001 只给了 120；V004 放宽到一样长。
    @Test
    void theMerchantNameSnapshotIsAsLongAsTheOrganizationName() throws Exception {
        String sql = statementsOf("/db/migration/trade/V004__trade_merchant_name.sql");

        assertThat(sql).isEqualTo("ALTER TABLE trade_order ALTER COLUMN merchant_name TYPE varchar(160);");
        assertThat(migration()).contains("merchant_name varchar(120) NOT NULL,");
        // trade 依赖 identity，它的迁移在类路径上。
        assertThat(migration("/db/migration/identity/V001__identity_rbac.sql"))
                .contains("organization_name varchar(160) NOT NULL");
    }

    // ---- V003：对账（E06） ----

    // 退款单加受理时间：可空（没受理的没有），已经受理过的（有微信退款单号的）用最后更新时间补上，只补空的。
    @Test
    void refundsGetAnAcceptanceTimeBackfilledForAcceptedOnes() throws Exception {
        String sql = statementsOf("/db/migration/trade/V003__trade_reconciliation.sql");

        assertThat(sql)
                .contains("ALTER TABLE trade_refund ADD COLUMN accepted_at timestamptz;")
                .contains("UPDATE trade_refund SET accepted_at = updated_at"
                        + " WHERE refund_id IS NOT NULL AND accepted_at IS NULL;");
        assertThat(sql).doesNotContain("accepted_at timestamptz NOT NULL");
        // V001 的 trade_refund 还没有这一列。
        assertThat(migration()).doesNotContain("accepted_at");
    }

    // 按受理时间、按付款成功时间取某一天的单据各有一个部分索引。
    @Test
    void theDayQueriesHavePartialIndexes() throws Exception {
        String sql = statementsOf("/db/migration/trade/V003__trade_reconciliation.sql");

        assertThat(sql)
                .contains("CREATE INDEX idx_trade_refund_accepted ON trade_refund (accepted_at)"
                        + " WHERE accepted_at IS NOT NULL;")
                .contains("CREATE INDEX idx_trade_payment_succeeded ON trade_payment (succeeded_at)"
                        + " WHERE status = 'SUCCEEDED';");
        // 只有 SUCCEEDED 的支付单有付款时间（V001 的约束），索引里不放 MISMATCH。
        assertThat(sql).doesNotContain("MISMATCH");
        assertThat(migration()).contains("CHECK ((status = 'SUCCEEDED') = (succeeded_at IS NOT NULL))");
        // 索引名不和前面的迁移重名。
        String earlier = migration() + fulfilment();
        assertThat(earlier).doesNotContain("idx_trade_refund_accepted").doesNotContain("idx_trade_payment_succeeded");
    }

    // V003 只加一列、补这一列、建两个索引：不建表、不删东西、不碰钱和状态的约束。
    @Test
    void theReconciliationMigrationOnlyAddsTheAcceptanceTime() throws Exception {
        String sql = statementsOf("/db/migration/trade/V003__trade_reconciliation.sql");

        assertThat(sql)
                .doesNotContain("CREATE TABLE")
                .doesNotContain("DROP ")
                .doesNotContain("INSERT INTO")
                .doesNotContain("DELETE ")
                .doesNotContain("CONSTRAINT")
                .doesNotContain("amount_minor")
                .doesNotContain("refunded_minor")
                .doesNotContain("paid_minor")
                .doesNotContain("SET status");
        assertThat(sql.split(";")).filteredOn(statement -> !statement.isBlank()).hasSize(4);
        // 对账结果和差异两张表 V001 已经建好：一天一个子商户一条，差异种类就是服务里用的四种。
        String v001 = migration();
        assertThat(v001)
                .contains("status varchar(16) NOT NULL CHECK (status IN ('IMPORTED', 'FAILED'))")
                .contains("failure_reason varchar(200)")
                .contains("UNIQUE (bill_date, sub_mchid)")
                .contains("CHECK (kind IN ('ONLY_IN_CHANNEL', 'ONLY_IN_PLATFORM', 'AMOUNT_MISMATCH', 'STATUS_MISMATCH'))");
        assertThat(reconciliation()).isNotBlank();
    }

    // ---- V002：发货、自提核销、确认收货、退款（E05） ----

    // V002 只改结构，不写数据，也不碰钱的约束。
    @Test
    void theFulfilmentMigrationChangesStructureOnly() throws Exception {
        String sql = fulfilment();

        assertThat(sql)
                .doesNotContain("INSERT INTO")
                .doesNotContain("UPDATE ")
                .doesNotContain("DELETE ")
                .doesNotContain("DROP TABLE")
                .doesNotContain("DROP COLUMN")
                .doesNotContain("refunded_minor")
                .doesNotContain("paid_minor");
    }

    // 取货码输错次数：有默认值（已有的行直接可用），0 到 10。
    @Test
    void pickupFailuresAreCountedFromZeroToTen() throws Exception {
        assertThat(fulfilment())
                .contains("ALTER TABLE trade_order_delivery ADD COLUMN pickup_failures smallint NOT NULL DEFAULT 0"
                        + " CHECK (pickup_failures BETWEEN 0 AND 10);");
        // V001 的取货码是 6 位定长。
        assertThat(migration()).contains("pickup_code char(6),");
    }

    // 状态迁移原因的约束换成新的一份：V001 的原因一个不少，再加上渠道当场拒绝退款的 REFUND_REJECTED。
    @Test
    void theTransitionCausesKeepTheOldOnesAndAddARejectedRefund() throws Exception {
        String v001 = migration();
        String v002 = fulfilment();
        String oldCauses = v001.substring(v001.indexOf("CHECK (cause IN ("), v001.indexOf("actor_id uuid"));
        String newCauses = v002.substring(v002.indexOf("CHECK (cause IN ("), v002.indexOf("-- 退款的定时兜底"));

        // V001 的约束写在列上没起名，PostgreSQL 默认叫 <表>_<列>_check，V002 按这个名字删。
        assertThat(v001).contains("cause varchar(24) NOT NULL CHECK (cause IN (");
        assertThat(v002)
                .contains("ALTER TABLE trade_state_transition DROP CONSTRAINT trade_state_transition_cause_check;")
                .contains("ALTER TABLE trade_state_transition ADD CONSTRAINT trade_state_transition_cause_check CHECK");
        for (String cause : List.of("SUBMIT", "PAYMENT_NOTIFIED", "PAYMENT_QUERIED", "BUYER_CANCEL", "TIMEOUT",
                "SHIPMENT", "PICKUP", "RECEIPT", "AUTO_RECEIPT", "REFUND_NOTIFIED", "REFUND_QUERIED",
                "MERCHANT_REFUND")) {
            assertThat(oldCauses).as(cause).contains("'" + cause + "'");
            assertThat(newCauses).as(cause).contains("'" + cause + "'");
        }
        assertThat(oldCauses).doesNotContain("REFUND_REJECTED");
        assertThat(newCauses).contains("'REFUND_REJECTED'");
        // 原因列 varchar(24) 放得下。
        assertThat("REFUND_REJECTED".length()).isLessThanOrEqualTo(24);
    }

    // 定时兜底用的部分索引正好覆盖要重提和要查询的三种状态。
    @Test
    void openRefundsHaveAPartialIndexForTheBackstop() throws Exception {
        assertThat(fulfilment())
                .contains("CREATE INDEX idx_trade_refund_open ON trade_refund (status, updated_at)"
                        + " WHERE status IN ('REQUESTED', 'PROCESSING', 'ABNORMAL');");
    }

    @Test
    void createsTheTradeTablesAndNoData() throws Exception {
        String sql = migration();

        for (String table : new String[] {
                "trade_merchant_account", "trade_order", "trade_order_line", "trade_order_delivery",
                "trade_payment", "trade_refund", "trade_channel_notification", "trade_reconciliation",
                "trade_reconciliation_difference", "trade_state_transition"}) {
            assertThat(sql).contains("CREATE TABLE " + table + " (");
        }
        assertThat(sql).doesNotContain("INSERT INTO");
    }

    // 金额存分；应付等于商品加运费；已付要么是 0 要么等于应付；退款不超过已付。
    @Test
    void moneyRulesAreConstraints() throws Exception {
        String sql = migration();

        assertThat(sql)
                .contains("CHECK (payable_minor = goods_minor + shipping_minor)")
                .contains("CHECK (paid_minor = 0 OR paid_minor = payable_minor)")
                .contains("CHECK (refunded_minor <= paid_minor)")
                .contains("subtotal_minor bigint NOT NULL CHECK (subtotal_minor = unit_amount_minor * quantity)")
                .doesNotContain("numeric")
                .doesNotContain("double");
    }

    // 微信的单号格式；一张订单同时只有一张没关闭的支付单、最多一张付款成功的支付单；同一通知只处理一次。
    @Test
    void channelIdentifiersAreUniqueAndShapedAsTheChannelRequires() throws Exception {
        String sql = migration();

        assertThat(sql)
                .contains("out_trade_no varchar(32) NOT NULL UNIQUE CHECK (out_trade_no ~ '^[0-9A-Za-z_*-]{6,32}$')")
                .contains("out_refund_no varchar(64) NOT NULL UNIQUE")
                .contains("CREATE UNIQUE INDEX uq_trade_payment_open ON trade_payment (order_id) "
                        + "WHERE status IN ('CREATED', 'PREPAID')")
                .contains("CREATE UNIQUE INDEX uq_trade_payment_succeeded ON trade_payment (order_id) "
                        + "WHERE status = 'SUCCEEDED'")
                .contains("UNIQUE (channel, notification_id)")
                .contains("UNIQUE (buyer_id, idempotency_key)");
    }

    // 回调表只存摘要，不存解密后的明文；敏感的收货资料只在配送快照里。
    @Test
    void notificationsKeepNoDecryptedPayload() throws Exception {
        String sql = migration();
        String notifications = sql.substring(
                sql.indexOf("CREATE TABLE trade_channel_notification"),
                sql.indexOf("CREATE TABLE trade_reconciliation ("));

        assertThat(notifications)
                .doesNotContain("ciphertext")
                .doesNotContain("payload")
                .doesNotContain("phone");
    }
}
