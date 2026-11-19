package com.lowaltitude.inspection.trade.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

import com.lowaltitude.inspection.trade.application.PaymentChannel.BillLine;
import com.lowaltitude.inspection.trade.application.PaymentChannel.BillLineKind;
import com.lowaltitude.inspection.trade.application.PaymentChannel.TradeBill;
import com.lowaltitude.inspection.trade.application.PaymentChannelException.Kind;
import com.lowaltitude.inspection.trade.application.TradeException.Error;
import com.lowaltitude.inspection.trade.application.TradeReconciliationService.Comparison;
import com.lowaltitude.inspection.trade.application.TradeReconciliationService.Difference;
import com.lowaltitude.inspection.trade.application.TradeViews.ReconciliationDifferenceView;
import com.lowaltitude.inspection.trade.application.TradeViews.ReconciliationRunView;
import com.lowaltitude.inspection.trade.application.TradeViews.ReconciliationView;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeReconciliationMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeReconciliationMapper.BillPaymentRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeReconciliationMapper.BillRefundRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 对账的规则测试（E06）。平台的支付单、退款单、收款账户和对账结果放在一份内存数据里，TradeReconciliationMapper 的内存实现
 * 按 SQL 的条件和 V001 / V003 的约束写；渠道换成按子商户编排账单的假渠道，事务用 withoutTransaction，时钟可拨动。
 * SQL 文本本身由 TradeReconciliationMapperContractTest 检查。
 */
class TradeReconciliationServiceTest {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    /** 北京时间 2026-11-18 11:00：昨天（11-17）的账单已经出了。 */
    private static final Instant NOW = Instant.parse("2026-11-18T03:00:00Z");
    private static final LocalDate DAY = LocalDate.parse("2026-11-17");
    /** 11-17 北京时间的零点和次日零点。 */
    private static final Instant DAY_START = Instant.parse("2026-11-16T16:00:00Z");
    private static final Instant DAY_END = Instant.parse("2026-11-17T16:00:00Z");
    private static final String SUB_A = "1900000101";
    private static final String SUB_B = "1900000102";
    private static final String SUB_C = "1900000103";

    private final Store store = new Store();
    private final FakeChannel channel = new FakeChannel();
    private final MutableClock clock = new MutableClock(NOW);
    private PaymentChannel configured = channel;
    private final TradeReconciliationService service = new TradeReconciliationService(
            store, () -> configured, TransactionOperations.withoutTransaction(), clock);

    @Test
    void theAgreedConstants() {
        assertThat(TradeReconciliationService.BILL_READY_HOUR).isEqualTo(10);
        assertThat(TradeReconciliationService.MAX_REASON).isEqualTo(200);
    }

    // ---- 跑对账：哪一天、哪些子商户 ----

    // 只能对北京时间今天以前的日子跑：北京时间已经是 11-18 的时候，UTC 还是 11-17，但 11-17 可以跑、11-18 不行。
    @Test
    void onlyDaysBeforeTodayInBeijingCanBeReconciled() {
        clock.now = Instant.parse("2026-11-17T16:30:00Z");
        store.account(SUB_A, true);
        channel.empty(SUB_A);

        assertThat(service.reconcile(DAY, false).imported()).isEqualTo(1);

        for (LocalDate date : new LocalDate[] {LocalDate.parse("2026-11-18"), LocalDate.parse("2026-11-19"), null}) {
            assertThatThrownBy(() -> service.reconcile(date, false)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(channel.calls).containsExactly(SUB_A + "@" + DAY);
    }

    @Test
    void withoutAChannelNothingIsReconciled() {
        configured = null;
        store.account(SUB_A, true);

        assertThatThrownBy(() -> service.reconcile(DAY, false))
                .isInstanceOfSatisfying(TradeException.class, refused ->
                        assertThat(refused.error()).isEqualTo(Error.CHANNEL_UNAVAILABLE));
        assertThat(store.results).isEmpty();
        assertThat(store.log).isEmpty();
    }

    // 要对的子商户：这一天结束前登记、现在启用的收款账户，加上那天有付款成功或退款受理的（停用的账户、别的子商户有钱进出的也要对）；
    // 一天是北京时间的 [零点, 次日零点)。
    @Test
    void theMerchantsAreTheRegisteredOnesAndThoseWithMoneyThatDay() {
        store.account(SUB_A, true);
        store.account(SUB_B, false);
        store.account(SUB_C, false);
        // 这一天结束时（或之后）才登记的账户不算这一天的。
        store.account("1900000105", true, DAY_END);
        store.account("1900000106", true, DAY_END.plusSeconds(86_400));
        store.payment("P0000000001", SUB_B, 100, "SUCCEEDED", DAY_START);
        store.payment("P0000000002", SUB_C, 100, "SUCCEEDED", DAY_END);
        store.payment("P0000000003", SUB_C, 100, "SUCCEEDED", DAY_START.minusSeconds(1));
        store.refund("R0000000001", "1900000104", 100, "PROCESSING", DAY_END.minusSeconds(1));
        store.refund("R0000000002", SUB_C, 100, "PROCESSING", DAY_END);
        store.refund("R0000000003", SUB_C, 100, "REQUESTED", null);
        for (String sub : List.of(SUB_A, SUB_B, "1900000104")) {
            channel.bills.put(sub, date -> bill(sub));
        }
        channel.bills.put(SUB_B, date -> bill(SUB_B, pay("P0000000001", 100)));
        channel.bills.put("1900000104", date -> bill("1900000104", refundLine("P0000000009", "R0000000001", 100, "SUCCESS")));

        ReconciliationRunView run = service.reconcile(DAY, false);

        assertThat(channel.calls).containsExactly(SUB_A + "@" + DAY, SUB_B + "@" + DAY, "1900000104@" + DAY);
        assertThat(run).isEqualTo(new ReconciliationRunView(DAY, 3, 3, 0, 0, 0));
        assertThat(store.results.keySet()).extracting(Store.Key::subMchid)
                .containsExactlyInAnyOrder(SUB_A, SUB_B, "1900000104");
    }

    // ---- 跑对账：结果的记法 ----

    @Test
    void aMatchingBillIsImportedWithItsMatchedCountAndNoDifferences() {
        store.account(SUB_A, true);
        store.payment("P0000000001", SUB_A, 276_000, "SUCCEEDED", DAY_START.plusSeconds(3600));
        store.refund("R0000000001", SUB_A, 138_000, "SUCCEEDED", DAY_START.plusSeconds(7200));
        channel.bills.put(SUB_A, date -> bill(SUB_A, pay("P0000000001", 276_000),
                refundLine("P0000000001", "R0000000001", 138_000, "SUCCESS")));

        ReconciliationRunView run = service.reconcile(DAY, false);

        assertThat(run).isEqualTo(new ReconciliationRunView(DAY, 1, 1, 0, 0, 0));
        Store.Result result = store.result(DAY, SUB_A);
        assertThat(result.status).isEqualTo("IMPORTED");
        assertThat(result.matched).isEqualTo(2);
        assertThat(result.reason).isNull();
        assertThat(result.importedAt).isEqualTo(NOW);
        assertThat(store.differences).isEmpty();
        // 先锁这一天、这个子商户，再读平台的单据、写结果。
        assertThat(store.log).containsSubsequence("lock:" + DAY + ":" + SUB_A, "findPaymentsByOutTradeNos",
                "findRefundsByOutRefundNos", "findPaymentsSucceededBetween", "findRefundsAcceptedBetween",
                "save:" + SUB_A + ":IMPORTED", "deleteDifferences");
        // 平台这边按北京时间的这一天取单据。
        assertThat(store.ranges).containsOnly(List.of(DAY_START, DAY_END));
    }

    // 账单里没有支付或退款时不去按单号查（空的 IN 列表在 SQL 里是语法错误）。
    @Test
    void anEmptyBillLooksUpNoNumbers() {
        store.account(SUB_A, true);
        channel.empty(SUB_A);

        service.reconcile(DAY, false);

        assertThat(store.log).doesNotContain("findPaymentsByOutTradeNos", "findRefundsByOutRefundNos");
        assertThat(store.result(DAY, SUB_A).status).isEqualTo("IMPORTED");
        assertThat(store.result(DAY, SUB_A).matched).isZero();
    }

    @Test
    void differencesAreRecordedAndCounted() {
        store.account(SUB_A, true);
        store.payment("P0000000001", SUB_A, 276_000, "SUCCEEDED", DAY_START.plusSeconds(60));
        store.payment("P0000000002", SUB_A, 100_000, "SUCCEEDED", DAY_START.plusSeconds(120));
        channel.bills.put(SUB_A, date -> bill(SUB_A, pay("P0000000001", 275_000), pay("P0000000099", 5_000)));

        ReconciliationRunView run = service.reconcile(DAY, false);

        assertThat(run.differences()).isEqualTo(3);
        assertThat(store.result(DAY, SUB_A).matched).isZero();
        UUID id = store.result(DAY, SUB_A).id;
        assertThat(store.differences).extracting(Store.Diff::reconciliationId).containsOnly(id);
        assertThat(store.differences)
                .extracting(Store.Diff::kind, Store.Diff::outTradeNo, Store.Diff::outRefundNo,
                        Store.Diff::platformAmountMinor, Store.Diff::channelAmountMinor, Store.Diff::createdAt)
                .containsExactly(
                        tuple("AMOUNT_MISMATCH", "P0000000001", null, 276_000L, 275_000L, NOW),
                        tuple("ONLY_IN_CHANNEL", "P0000000099", null, null, 5_000L, NOW),
                        tuple("ONLY_IN_PLATFORM", "P0000000002", null, 100_000L, null, NOW));
    }

    // 不重做时已经导入成功的跳过，不再下载；导入失败的再试。
    @Test
    void withoutRedoImportedMerchantsAreSkippedAndFailedOnesRetried() {
        store.account(SUB_A, true);
        store.account(SUB_B, true);
        channel.empty(SUB_A);
        channel.failing(SUB_B, Kind.RETRYABLE, "STATEMENT_CREATING");
        service.reconcile(DAY, false);
        channel.calls.clear();
        channel.empty(SUB_B);
        clock.now = NOW.plusSeconds(1800);

        ReconciliationRunView run = service.reconcile(DAY, false);

        assertThat(channel.calls).containsExactly(SUB_B + "@" + DAY);
        assertThat(run).isEqualTo(new ReconciliationRunView(DAY, 2, 1, 0, 1, 0));
        assertThat(store.result(DAY, SUB_A).importedAt).isEqualTo(NOW);
        assertThat(store.result(DAY, SUB_B).status).isEqualTo("IMPORTED");
        assertThat(store.result(DAY, SUB_B).reason).isNull();
        assertThat(store.result(DAY, SUB_B).importedAt).isEqualTo(NOW.plusSeconds(1800));
    }

    // 重做：全部重新下载，覆盖结果（同一条，ID 不变）和差异。
    @Test
    void aRedoDownloadsAgainAndOverwritesTheResultAndItsDifferences() {
        store.account(SUB_A, true);
        store.payment("P0000000001", SUB_A, 276_000, "SUCCEEDED", DAY_START.plusSeconds(60));
        channel.bills.put(SUB_A, date -> bill(SUB_A, pay("P0000000001", 275_000)));
        service.reconcile(DAY, false);
        UUID id = store.result(DAY, SUB_A).id;
        assertThat(store.differences).hasSize(1);

        channel.bills.put(SUB_A, date -> bill(SUB_A, pay("P0000000001", 276_000)));
        clock.now = NOW.plusSeconds(60);
        ReconciliationRunView run = service.reconcile(DAY, true);

        assertThat(run).isEqualTo(new ReconciliationRunView(DAY, 1, 1, 0, 0, 0));
        assertThat(store.results).hasSize(1);
        assertThat(store.result(DAY, SUB_A).id).isEqualTo(id);
        assertThat(store.result(DAY, SUB_A).matched).isEqualTo(1);
        assertThat(store.result(DAY, SUB_A).importedAt).isEqualTo(NOW.plusSeconds(60));
        assertThat(store.differences).isEmpty();
        assertThat(channel.calls).hasSize(2);
    }

    // 下载失败（账单还在生成、渠道不通、文件校验不过）：记 FAILED 和原因（渠道错误码 + 说明），先锁后写；接着对下一个。
    @ParameterizedTest
    @CsvSource({"RETRYABLE,STATEMENT_CREATING", "UNTRUSTED,BILL_HASH", "REJECTED,NO_AUTH"})
    void aFailedDownloadIsRecordedAndTheOthersGoOn(Kind kind, String code) {
        store.account(SUB_A, true);
        store.account(SUB_B, true);
        channel.failing(SUB_A, kind, code);
        channel.empty(SUB_B);

        ReconciliationRunView run = service.reconcile(DAY, false);

        assertThat(run).isEqualTo(new ReconciliationRunView(DAY, 2, 1, 1, 0, 0));
        Store.Result failed = store.result(DAY, SUB_A);
        assertThat(failed.status).isEqualTo("FAILED");
        assertThat(failed.matched).isZero();
        assertThat(failed.reason).isEqualTo(code + " wechat pay " + code);
        assertThat(failed.importedAt).isEqualTo(NOW);
        assertThat(store.result(DAY, SUB_B).status).isEqualTo("IMPORTED");
        assertThat(store.log).containsSubsequence("lock:" + DAY + ":" + SUB_A, "save:" + SUB_A + ":FAILED",
                "deleteDifferences");
    }

    // 失败原因截到 200 个字符（表上是 varchar(200)）。
    @Test
    void aLongFailureReasonIsCutAt200() {
        store.account(SUB_A, true);
        channel.bills.put(SUB_A, date -> {
            throw new PaymentChannelException(Kind.RETRYABLE, "SYSTEM_ERROR", 500, "x".repeat(400));
        });

        service.reconcile(DAY, false);

        assertThat(store.result(DAY, SUB_A).reason).hasSize(200).startsWith("SYSTEM_ERROR x");
    }

    // 已经导入成功的，重做时下载失败不覆盖它（也不删它的差异）；之前失败的，再失败就更新原因。
    @Test
    void aFailedRedoDoesNotOverwriteAnImportedResult() {
        store.account(SUB_A, true);
        store.account(SUB_B, true);
        store.payment("P0000000001", SUB_A, 276_000, "SUCCEEDED", DAY_START.plusSeconds(60));
        channel.bills.put(SUB_A, date -> bill(SUB_A, pay("P0000000001", 1)));
        channel.failing(SUB_B, Kind.RETRYABLE, "STATEMENT_CREATING");
        service.reconcile(DAY, false);
        Store.Result imported = store.result(DAY, SUB_A).copy();

        channel.failing(SUB_A, Kind.RETRYABLE, "SYSTEM_ERROR");
        channel.failing(SUB_B, Kind.RETRYABLE, "SYSTEM_ERROR");
        clock.now = NOW.plusSeconds(60);
        ReconciliationRunView run = service.reconcile(DAY, true);

        assertThat(run).isEqualTo(new ReconciliationRunView(DAY, 2, 0, 2, 0, 0));
        assertThat(store.result(DAY, SUB_A)).usingRecursiveComparison().isEqualTo(imported);
        assertThat(store.differences).hasSize(1);
        assertThat(store.result(DAY, SUB_B).status).isEqualTo("FAILED");
        assertThat(store.result(DAY, SUB_B).reason).startsWith("SYSTEM_ERROR");
        assertThat(store.result(DAY, SUB_B).importedAt).isEqualTo(NOW.plusSeconds(60));
    }

    // 不是渠道异常的失败（程序错误）：同样记 FAILED，原因只写异常类名（消息里可能带数据），接着对下一个。
    @Test
    void anUnexpectedErrorIsRecordedAsFailedWithItsClassNameOnly() {
        store.account(SUB_A, true);
        store.account(SUB_B, true);
        channel.bills.put(SUB_A, date -> {
            throw new IllegalStateException("boom 13800000000");
        });
        channel.empty(SUB_B);

        ReconciliationRunView run = service.reconcile(DAY, false);

        assertThat(run).isEqualTo(new ReconciliationRunView(DAY, 2, 1, 1, 0, 0));
        Store.Result failed = store.result(DAY, SUB_A);
        assertThat(failed.status).isEqualTo("FAILED");
        assertThat(failed.reason).isEqualTo("INTERNAL IllegalStateException");
        assertThat(failed.matched).isZero();
        assertThat(failed.importedAt).isEqualTo(NOW);
        assertThat(store.log).containsSubsequence("lock:" + DAY + ":" + SUB_A, "save:" + SUB_A + ":FAILED",
                "deleteDifferences");
        assertThat(store.result(DAY, SUB_B).status).isEqualTo("IMPORTED");
        assertNotReady();
    }

    // 程序错误也不覆盖已经导入成功的结果。
    @Test
    void anUnexpectedErrorDoesNotOverwriteAnImportedResult() {
        store.account(SUB_A, true);
        channel.bills.put(SUB_A, date -> bill(SUB_A));
        service.reconcile(DAY, false);
        Store.Result imported = store.result(DAY, SUB_A).copy();
        channel.bills.put(SUB_A, date -> {
            throw new NullPointerException();
        });
        clock.now = NOW.plusSeconds(60);

        assertThat(service.reconcile(DAY, true)).isEqualTo(new ReconciliationRunView(DAY, 1, 0, 1, 0, 0));
        assertThat(store.result(DAY, SUB_A)).usingRecursiveComparison().isEqualTo(imported);
    }

    // 平台单据在锁里读：两个人同时跑同一天，后到的在锁后读到的是最新的单据。这里只验证读在锁之后。
    @Test
    void thePlatformSideIsReadAfterTheLock() {
        store.account(SUB_A, true);
        channel.bills.put(SUB_A, date -> bill(SUB_A, pay("P0000000001", 1)));

        service.reconcile(DAY, false);

        int lock = store.log.indexOf("lock:" + DAY + ":" + SUB_A);
        assertThat(lock).isNotNegative();
        assertThat(store.log.indexOf("findPaymentsByOutTradeNos")).isGreaterThan(lock);
        assertThat(store.log.indexOf("findPaymentsSucceededBetween")).isGreaterThan(lock);
    }

    // ---- 逐笔核对 ----

    @Test
    void aBillPaymentIsMatchedByItsNumberMerchantStatusAndAmount() {
        Comparison comparison = compare(
                List.of(pay("P1", 100), pay("P2", 200), pay("P3", 300), pay("P4", 400), pay("P5", 500), pay("P6", 600)),
                List.of(paid("P1", SUB_A, 100, "SUCCEEDED"),
                        paid("P2", SUB_B, 200, "SUCCEEDED"),
                        paid("P3", SUB_A, 300, "MISMATCH"),
                        paid("P4", SUB_A, 401, "SUCCEEDED"),
                        paid("P5", SUB_A, 501, "CLOSED")),
                List.of(), List.of(), List.of());

        assertThat(comparison.matched()).isEqualTo(1);
        assertThat(comparison.differences()).containsExactly(
                // 子商户不同：对这个子商户来说平台没有这一单。
                new Difference("ONLY_IN_CHANNEL", "P2", null, null, 200L),
                new Difference("STATUS_MISMATCH", "P3", null, 300L, 300L),
                new Difference("AMOUNT_MISMATCH", "P4", null, 401L, 400L),
                // 状态先于金额判断。
                new Difference("STATUS_MISMATCH", "P5", null, 501L, 500L),
                new Difference("ONLY_IN_CHANNEL", "P6", null, null, 600L));
    }

    @ParameterizedTest
    @CsvSource({
            "PROCESSING,SUCCESS,",
            "PROCESSING,PROCESSING,",
            "PROCESSING,FAIL,",
            "SUCCEEDED,SUCCESS,",
            "SUCCEEDED,PROCESSING,",
            "SUCCEEDED,CHANGE,",
            "SUCCEEDED,,",
            "ABNORMAL,SUCCESS,",
            "ABNORMAL,FAIL,",
            "REQUESTED,SUCCESS,STATUS_MISMATCH",
            "CLOSED,SUCCESS,STATUS_MISMATCH",
            "CLOSED,FAIL,STATUS_MISMATCH",
            "SUCCEEDED,FAIL,STATUS_MISMATCH"})
    void aBillRefundIsMatchedByItsStatus(String platformStatus, String billStatus, String expected) {
        Comparison comparison = compare(
                List.of(refundLine("P1", "R1", 100, billStatus)),
                List.of(),
                List.of(refunded("R1", SUB_A, 100, platformStatus)), List.of(), List.of());

        if (expected == null) {
            assertThat(comparison.matched()).isEqualTo(1);
            assertThat(comparison.differences()).isEmpty();
        } else {
            assertThat(comparison.matched()).isZero();
            assertThat(comparison.differences()).containsExactly(new Difference(expected, "P1", "R1", 100L, 100L));
        }
    }

    @Test
    void aBillRefundIsLookedUpByItsNumberAndMerchantAndAmountComesFirst() {
        Comparison comparison = compare(
                List.of(refundLine("P1", "R1", 100, "SUCCESS"), refundLine("P2", "R2", 200, "SUCCESS"),
                        refundLine("P3", "R3", 300, "SUCCESS")),
                List.of(),
                List.of(refunded("R2", SUB_B, 200, "SUCCEEDED"), refunded("R3", SUB_A, 299, "REQUESTED")),
                List.of(), List.of());

        assertThat(comparison.matched()).isZero();
        assertThat(comparison.differences()).containsExactly(
                new Difference("ONLY_IN_CHANNEL", "P1", "R1", null, 100L),
                new Difference("ONLY_IN_CHANNEL", "P2", "R2", null, 200L),
                // 金额先于状态判断。
                new Difference("AMOUNT_MISMATCH", "P3", "R3", 299L, 300L));
    }

    // 平台当天付款成功、受理的单据账单里没有：平台有、微信没有（退款带原支付单的商户订单号）。
    @Test
    void whatThePlatformHasForTheDayButTheBillDoesNotIsOnlyInPlatform() {
        Comparison comparison = compare(
                List.of(pay("P1", 100), refundLine("P1", "R1", 50, "SUCCESS")),
                List.of(paid("P1", SUB_A, 100, "SUCCEEDED")),
                List.of(refunded("R1", SUB_A, 50, "PROCESSING")),
                List.of(paid("P1", SUB_A, 100, "SUCCEEDED"), paid("P2", SUB_A, 200, "SUCCEEDED")),
                List.of(refunded("R1", SUB_A, 50, "PROCESSING"), refunded("R2", SUB_A, 70, "CLOSED")));

        assertThat(comparison.matched()).isEqualTo(2);
        assertThat(comparison.differences()).containsExactly(
                new Difference("ONLY_IN_PLATFORM", "P2", null, 200L, null),
                // 退款带上原支付单的商户订单号，好对到订单。
                new Difference("ONLY_IN_PLATFORM", "P2", "R2", 70L, null));
    }

    // 同一个单号重复出现的行只算一次（第二行即使金额不同也不看）；“其他”行不核对。
    @Test
    void repeatedNumbersCountOnceAndOtherLinesAreIgnored() {
        BillLine other = new BillLine(BillLineKind.OTHER, "REVOKED", DAY_START, SUB_A, null, null, null, null, "CNY", 0, 0, null);
        Comparison comparison = compare(
                List.of(pay("P1", 100), pay("P1", 100), pay("P1", 999), other,
                        refundLine("P1", "R1", 50, "SUCCESS"), refundLine("P1", "R1", 51, "SUCCESS")),
                List.of(paid("P1", SUB_A, 100, "SUCCEEDED")),
                List.of(refunded("R1", SUB_A, 50, "SUCCEEDED")),
                List.of(), List.of());

        assertThat(comparison.matched()).isEqualTo(2);
        assertThat(comparison.differences()).isEmpty();
    }

    // 一个单号同时出现在支付行和退款行：两边各自核对，互不影响。
    @Test
    void paymentsAndRefundsAreSeenSeparately() {
        Comparison comparison = compare(
                List.of(refundLine("P1", "R1", 50, "SUCCESS")),
                List.of(),
                List.of(refunded("R1", SUB_A, 50, "SUCCEEDED")),
                List.of(paid("P1", SUB_A, 100, "SUCCEEDED")),
                List.of());

        assertThat(comparison.matched()).isEqualTo(1);
        assertThat(comparison.differences()).containsExactly(new Difference("ONLY_IN_PLATFORM", "P1", null, 100L, null));
    }

    // ---- 读结果 ----

    @Test
    void aDayIsReadyOnlyWhenEveryMerchantIsImported() {
        store.account(SUB_A, true);
        store.account(SUB_B, true);
        assertNotReady();

        channel.empty(SUB_A);
        channel.failing(SUB_B, Kind.RETRYABLE, "STATEMENT_CREATING");
        service.reconcile(DAY, false);
        assertNotReady();

        channel.empty(SUB_B);
        service.reconcile(DAY, false);
        assertThat(service.read(DAY).differences()).isEmpty();

        // 这一天以后才登记的收款账户不影响这一天（新登记一个商家，历史日期不会变成未就绪）。
        store.account(SUB_C, true, DAY_END);
        store.account("1900000105", true, NOW);
        assertThat(service.read(DAY).differences()).isEmpty();

        // 这一天结束前登记、却还没导入的（比如之前停用后重新启用）：不完整。
        store.account("1900000106", true, DAY_END.minusSeconds(1));
        assertNotReady();
    }

    @Test
    void theResultAddsUpTheMerchantsAndSortsTheDifferences() {
        store.account(SUB_B, true);
        store.account(SUB_A, true);
        store.payment("P0000000005", SUB_A, 500, "SUCCEEDED", DAY_START.plusSeconds(10));
        store.payment("P0000000001", SUB_A, 100, "SUCCEEDED", DAY_START.plusSeconds(20));
        store.payment("P0000000002", SUB_A, 200, "SUCCEEDED", DAY_START.plusSeconds(30));
        store.refund("R0000000009", SUB_A, 90, "PROCESSING", DAY_START.plusSeconds(40));
        store.payment("P0000000003", SUB_B, 300, "SUCCEEDED", DAY_START.plusSeconds(50));
        store.payment("P0000000004", SUB_B, 400, "SUCCEEDED", DAY_START.plusSeconds(60));
        channel.bills.put(SUB_A, date -> bill(SUB_A, pay("P0000000002", 201), pay("P0000000007", 7),
                refundLine("P0000000002", "R0000000008", 80, "SUCCESS")));
        channel.bills.put(SUB_B, date -> bill(SUB_B, pay("P0000000003", 300), pay("P0000000004", 400)));
        service.reconcile(DAY, false);
        clock.now = NOW.plusSeconds(600);
        channel.bills.put(SUB_B, date -> bill(SUB_B, pay("P0000000003", 300)));
        service.reconcile(DAY, true);
        store.diffOrder = Comparator.comparing((Store.Diff diff) -> diff.kind).reversed();

        ReconciliationView view = service.read(DAY);

        assertThat(view.date()).isEqualTo(DAY);
        assertThat(view.importedAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(view.matchedCount()).isEqualTo(1);
        assertThat(view.differences()).containsExactly(
                new ReconciliationDifferenceView("AMOUNT_MISMATCH", SUB_A, "P0000000002", null, 200L, 201L),
                new ReconciliationDifferenceView("ONLY_IN_CHANNEL", SUB_A, "P0000000002", "R0000000008", null, 80L),
                new ReconciliationDifferenceView("ONLY_IN_CHANNEL", SUB_A, "P0000000007", null, null, 7L),
                new ReconciliationDifferenceView("ONLY_IN_PLATFORM", SUB_A, "P0000000001", null, 100L, null),
                new ReconciliationDifferenceView("ONLY_IN_PLATFORM", SUB_A, "P0000000005", null, 500L, null),
                // 退款的差异按原支付单的商户订单号排。
                new ReconciliationDifferenceView("ONLY_IN_PLATFORM", SUB_A, "P0000000009", "R0000000009", 90L, null),
                new ReconciliationDifferenceView("ONLY_IN_PLATFORM", SUB_B, "P0000000004", null, 400L, null));
    }

    @Test
    void readingNeedsADate() {
        assertThatThrownBy(() -> service.read(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // 疑点（现状）：一天里一个要对的子商户都没有（没登记收款账户、也没有交易）时，对账什么也不记，这一天永远“未就绪”。
    @Test
    void aDayWithNothingToReconcileIsNeverReady() {
        ReconciliationRunView run = service.reconcile(DAY, false);

        assertThat(run).isEqualTo(new ReconciliationRunView(DAY, 0, 0, 0, 0, 0));
        assertThat(channel.calls).isEmpty();
        assertNotReady();
    }

    // ---- CSV ----

    @Test
    void theCsvHasABomAHeaderCrlfLinesAndAmountsInYuan() {
        ReconciliationView view = new ReconciliationView(DAY, NOW, 3, List.of(
                new ReconciliationDifferenceView("AMOUNT_MISMATCH", SUB_A, "P0000000001", null, 276_000L, 275_001L),
                new ReconciliationDifferenceView("ONLY_IN_CHANNEL", SUB_A, "P0000000001", "R0000000001", null, 1L),
                // 平台有、微信没有的退款带原支付单的商户订单号。
                new ReconciliationDifferenceView("ONLY_IN_PLATFORM", SUB_B, "P0000000002", "R0000000002", 0L, null),
                new ReconciliationDifferenceView("STATUS_MISMATCH", SUB_B, "P0000000003", null, 10L, 10L)));

        String csv = TradeReconciliationService.csv(view);

        assertThat(csv).startsWith("﻿").endsWith("\r\n");
        assertThat(csv.substring(1).split("\r\n", -1)).containsExactly(
                "日期,类型,说明,子商户号,商户订单号,商户退款单号,平台金额（元）,微信金额（元）",
                "2026-11-17,AMOUNT_MISMATCH,金额不一致,1900000101,P0000000001,,2760.00,2750.01",
                "2026-11-17,ONLY_IN_CHANNEL,微信有、平台没有,1900000101,P0000000001,R0000000001,,0.01",
                "2026-11-17,ONLY_IN_PLATFORM,平台有、微信没有,1900000102,P0000000002,R0000000002,0.00,",
                "2026-11-17,STATUS_MISMATCH,状态不一致,1900000102,P0000000003,,0.10,0.10",
                "");
        // 只有 CRLF，没有单独的 LF。
        assertThat(csv.replace("\r\n", "")).doesNotContain("\n").doesNotContain("\r");
    }

    @Test
    void aCsvWithoutDifferencesIsJustTheHeader() {
        assertThat(TradeReconciliationService.csv(new ReconciliationView(DAY, NOW, 0, List.of())))
                .isEqualTo("﻿日期,类型,说明,子商户号,商户订单号,商户退款单号,平台金额（元）,微信金额（元）\r\n");
    }

    // 以 = + - @ 开头的格前加单引号（防表格软件当公式）；含逗号、引号、换行的格加双引号，引号双写。
    @Test
    void cellsAreEscaped() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("=1+1", "'=1+1");
        expected.put("+86", "'+86");
        expected.put("-5", "'-5");
        expected.put("@SUM(A1)", "'@SUM(A1)");
        expected.put("a,b", "\"a,b\"");
        expected.put("a\"b", "\"a\"\"b\"");
        expected.put("=a,b", "\"'=a,b\"");
        expected.put("a'b", "a'b");
        expected.put("P0000000001", "P0000000001");
        expected.forEach((value, cell) -> {
            String csv = TradeReconciliationService.csv(new ReconciliationView(DAY, NOW, 0, List.of(
                    new ReconciliationDifferenceView("ONLY_IN_CHANNEL", SUB_A, value, null, null, 1L))));

            assertThat(csv.split("\r\n")[1]).as(value)
                    .isEqualTo("2026-11-17,ONLY_IN_CHANNEL,微信有、平台没有,1900000101," + cell + ",,,0.01");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\nb", "a\rb", "a\r\nb"})
    void lineBreaksInACellAreQuoted(String value) {
        String csv = TradeReconciliationService.csv(new ReconciliationView(DAY, NOW, 0, List.of(
                new ReconciliationDifferenceView("ONLY_IN_CHANNEL", SUB_A, value, null, null, 1L))));

        assertThat(csv).contains(",\"" + value + "\",");
    }

    // 不认识的差异类型原样写在“说明”里（同样转义）。
    @Test
    void anUnknownKindIsDescribedByItself() {
        String csv = TradeReconciliationService.csv(new ReconciliationView(DAY, NOW, 0, List.of(
                new ReconciliationDifferenceView("-NEW", SUB_A, "P1", null, 1L, 1L))));

        assertThat(csv.split("\r\n")[1]).isEqualTo("2026-11-17,'-NEW,'-NEW,1900000101,P1,,0.01,0.01");
    }

    // ---- 定时补跑 ----

    // 北京时间 10 点以前，昨天的账单还没出：只补前天；10 点起补前天和昨天。
    @ParameterizedTest
    @CsvSource({
            "2026-11-18T01:59:59Z,2026-11-16",
            "2026-11-17T16:00:00Z,2026-11-16",
            "2026-11-18T02:00:00Z,2026-11-16 2026-11-17",
            "2026-11-18T15:59:59Z,2026-11-16 2026-11-17"})
    void theJobCatchesUpOnTheDaysWhoseBillsAreOut(String now, String days) {
        clock.now = Instant.parse(now);
        store.account(SUB_A, true);
        channel.empty(SUB_A);

        int imported = service.reconcileRecentDays();

        List<String> expected = java.util.Arrays.stream(days.split(" ")).map(day -> SUB_A + "@" + day).toList();
        assertThat(channel.calls).containsExactlyElementsOf(expected);
        assertThat(imported).isEqualTo(expected.size());
    }

    // 已经导入成功的不再下载；数的是这次新导入的。
    @Test
    void theJobOnlyCountsWhatItImportedThisTime() {
        store.account(SUB_A, true);
        channel.empty(SUB_A);
        assertThat(service.reconcileRecentDays()).isEqualTo(2);
        channel.calls.clear();

        assertThat(service.reconcileRecentDays()).isZero();
        assertThat(channel.calls).isEmpty();
    }

    @Test
    void theJobDoesNothingWithoutAChannel() {
        configured = null;
        store.account(SUB_A, true);

        assertThat(service.reconcileRecentDays()).isZero();
        assertThat(store.log).isEmpty();
    }

    // ---- 辅助 ----

    private void assertNotReady() {
        assertThatThrownBy(() -> service.read(DAY))
                .isInstanceOfSatisfying(TradeException.class, refused ->
                        assertThat(refused.error()).isEqualTo(Error.RECONCILIATION_NOT_READY));
    }

    private static Comparison compare(List<BillLine> lines, List<BillPaymentRow> payments, List<BillRefundRow> refunds,
                                      List<BillPaymentRow> paymentsOfDay, List<BillRefundRow> refundsOfDay) {
        Map<String, BillPaymentRow> paymentsByNo = new LinkedHashMap<>();
        payments.forEach(row -> paymentsByNo.putIfAbsent(row.outTradeNo(), row));
        Map<String, BillRefundRow> refundsByNo = new LinkedHashMap<>();
        refunds.forEach(row -> refundsByNo.putIfAbsent(row.outRefundNo(), row));
        return TradeReconciliationService.compare(new TradeBill(SUB_A, DAY, lines), paymentsByNo, refundsByNo,
                paymentsOfDay, refundsOfDay);
    }

    private static BillPaymentRow paid(String outTradeNo, String subMchid, long amount, String status) {
        return new BillPaymentRow(outTradeNo, subMchid, amount, status);
    }

    private static BillRefundRow refunded(String outRefundNo, String subMchid, long amount, String status) {
        return new BillRefundRow(outRefundNo, orderOf(outRefundNo), subMchid, amount, status);
    }

    /** 测试里退款单的原支付单号：把开头的 R 换成 P。 */
    private static String orderOf(String outRefundNo) {
        return "P" + outRefundNo.substring(1);
    }

    private static BillLine pay(String outTradeNo, long amount) {
        return new BillLine(BillLineKind.PAYMENT, "SUCCESS", DAY_START.plusSeconds(60), SUB_A, "42" + outTradeNo,
                outTradeNo, null, null, "CNY", amount, 0, null);
    }

    private static BillLine refundLine(String outTradeNo, String outRefundNo, long amount, String status) {
        return new BillLine(BillLineKind.REFUND, "REFUND", DAY_START.plusSeconds(120), SUB_A, "42" + outTradeNo,
                outTradeNo, "50" + outRefundNo, outRefundNo, "CNY", 0, amount, status);
    }

    private static TradeBill bill(String subMchid, BillLine... lines) {
        return new TradeBill(subMchid, DAY, List.of(lines));
    }

    /** 每个子商户的账单按日期编排；没编排的子商户让测试失败（AssertionError 不会被服务当作渠道异常吞掉）。 */
    private static final class FakeChannel implements PaymentChannel {

        final List<String> calls = new ArrayList<>();
        final Map<String, Function<LocalDate, TradeBill>> bills = new HashMap<>();

        void empty(String subMchid) {
            bills.put(subMchid, date -> new TradeBill(subMchid, date, List.of()));
        }

        void failing(String subMchid, Kind kind, String code) {
            bills.put(subMchid, date -> {
                throw new PaymentChannelException(kind, code, 0, "wechat pay " + code);
            });
        }

        @Override
        public TradeBill tradeBill(String subMchid, LocalDate billDate) {
            calls.add(subMchid + "@" + billDate);
            Function<LocalDate, TradeBill> answer = bills.get(subMchid);
            if (answer == null) {
                throw new AssertionError("unexpected bill of " + subMchid);
            }
            return answer.apply(billDate);
        }

        @Override
        public String providerId() {
            return "1900000001";
        }

        @Override
        public String createAppPayment(PrepayRequest request) {
            throw new AssertionError("reconciliation does not pay");
        }

        @Override
        public AppPayParams appPayParams(String prepayId) {
            throw new AssertionError("reconciliation does not pay");
        }

        @Override
        public PaymentQuery queryPayment(String subMchid, String outTradeNo) {
            throw new AssertionError("reconciliation does not query payments");
        }

        @Override
        public void closePayment(String subMchid, String outTradeNo) {
            throw new AssertionError("reconciliation does not close payments");
        }

        @Override
        public RefundAccepted applyRefund(RefundRequest request) {
            throw new AssertionError("reconciliation does not refund");
        }

        @Override
        public RefundQuery queryRefund(String subMchid, String outRefundNo) {
            throw new AssertionError("reconciliation does not query refunds");
        }

        @Override
        public ChannelNotification parseNotification(Map<String, String> headers, String body) {
            throw new AssertionError("reconciliation does not take notifications");
        }
    }

    private static final class MutableClock extends Clock {

        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /**
     * 收款账户、支付单、退款单和对账结果的一份内存数据，按 SQL 的条件和表上的约束实现 TradeReconciliationMapper。
     * 每次调用记到 log 里，好检查先锁后读写。
     */
    private static final class Store implements TradeReconciliationMapper {

        record Key(LocalDate billDate, String subMchid) {
        }

        record Account(String subMchid, boolean active, Instant createdAt) {
        }

        record Payment(String outTradeNo, String subMchid, long amountMinor, String status, Instant succeededAt) {
        }

        record Refund(String outRefundNo, String outTradeNo, String subMchid, long amountMinor, String status,
                      Instant acceptedAt) {
        }

        static final class Result {
            UUID id;
            String status;
            int matched;
            String reason;
            Instant importedAt;

            Result copy() {
                Result copy = new Result();
                copy.id = id;
                copy.status = status;
                copy.matched = matched;
                copy.reason = reason;
                copy.importedAt = importedAt;
                return copy;
            }
        }

        record Diff(UUID reconciliationId, String kind, String outTradeNo, String outRefundNo, Long platformAmountMinor,
                    Long channelAmountMinor, Instant createdAt) {
        }

        final List<Account> accounts = new ArrayList<>();
        final List<Payment> payments = new ArrayList<>();
        final List<Refund> refunds = new ArrayList<>();
        final Map<Key, Result> results = new LinkedHashMap<>();
        final List<Diff> differences = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        final List<List<Instant>> ranges = new ArrayList<>();
        /** 差异读出来的顺序（SQL 是按结果 ID、差异 ID，都是随机 UUID）；服务自己排序，测试可以打乱。 */
        Comparator<Diff> diffOrder = null;

        /** 默认在这一天之前很久就登记了。 */
        void account(String subMchid, boolean active) {
            account(subMchid, active, DAY_START.minus(java.time.Duration.ofDays(30)));
        }

        void account(String subMchid, boolean active, Instant createdAt) {
            accounts.add(new Account(subMchid, active, createdAt));
        }

        void payment(String outTradeNo, String subMchid, long amount, String status, Instant succeededAt) {
            // trade_payment：付款成功 ⇔ 有付款时间。
            if ("SUCCEEDED".equals(status) != (succeededAt != null)) {
                throw new IllegalStateException("trade_payment check violated");
            }
            payments.add(new Payment(outTradeNo, subMchid, amount, status, succeededAt));
        }

        /** 原支付单的商户订单号由退款单号推出来：R0000000009 → P0000000009。 */
        void refund(String outRefundNo, String subMchid, long amount, String status, Instant acceptedAt) {
            refunds.add(new Refund(outRefundNo, orderOf(outRefundNo), subMchid, amount, status, acceptedAt));
        }

        Result result(LocalDate date, String subMchid) {
            Result result = results.get(new Key(date, subMchid));
            assertThat(result).as(date + " " + subMchid).isNotNull();
            return result;
        }

        private static boolean within(Instant at, Instant from, Instant to) {
            return at != null && !at.isBefore(from) && at.isBefore(to);
        }

        @Override
        public Integer lock(LocalDate billDate, String subMchid) {
            log.add("lock:" + billDate + ":" + subMchid);
            return 1;
        }

        @Override
        public List<String> findSubMchidsToReconcile(Instant from, Instant to) {
            log.add("findSubMchidsToReconcile");
            ranges.add(List.of(from, to));
            Set<String> subs = new TreeSet<>();
            // WHERE active AND created_at < #{to}
            accounts.stream().filter(account -> account.active() && account.createdAt().isBefore(to))
                    .forEach(account -> subs.add(account.subMchid()));
            payments.stream().filter(payment -> within(payment.succeededAt(), from, to))
                    .forEach(payment -> subs.add(payment.subMchid()));
            refunds.stream().filter(refund -> within(refund.acceptedAt(), from, to))
                    .forEach(refund -> subs.add(refund.subMchid()));
            return List.copyOf(subs);
        }

        @Override
        public List<BillPaymentRow> findPaymentsByOutTradeNos(List<String> outTradeNos) {
            log.add("findPaymentsByOutTradeNos");
            assertThat(outTradeNos).as("IN () is not valid SQL").isNotEmpty().doesNotHaveDuplicates();
            return payments.stream().filter(payment -> outTradeNos.contains(payment.outTradeNo()))
                    .map(payment -> new BillPaymentRow(payment.outTradeNo(), payment.subMchid(), payment.amountMinor(),
                            payment.status()))
                    .toList();
        }

        @Override
        public List<BillPaymentRow> findPaymentsSucceededBetween(String subMchid, Instant from, Instant to) {
            log.add("findPaymentsSucceededBetween");
            ranges.add(List.of(from, to));
            return payments.stream()
                    .filter(payment -> payment.subMchid().equals(subMchid) && "SUCCEEDED".equals(payment.status())
                            && within(payment.succeededAt(), from, to))
                    .sorted(Comparator.comparing(Payment::outTradeNo))
                    .map(payment -> new BillPaymentRow(payment.outTradeNo(), payment.subMchid(), payment.amountMinor(),
                            payment.status()))
                    .toList();
        }

        @Override
        public List<BillRefundRow> findRefundsByOutRefundNos(List<String> outRefundNos) {
            log.add("findRefundsByOutRefundNos");
            assertThat(outRefundNos).as("IN () is not valid SQL").isNotEmpty().doesNotHaveDuplicates();
            return refunds.stream().filter(refund -> outRefundNos.contains(refund.outRefundNo()))
                    .map(refund -> new BillRefundRow(refund.outRefundNo(), refund.outTradeNo(), refund.subMchid(), refund.amountMinor(),
                            refund.status()))
                    .toList();
        }

        @Override
        public List<BillRefundRow> findRefundsAcceptedBetween(String subMchid, Instant from, Instant to) {
            log.add("findRefundsAcceptedBetween");
            ranges.add(List.of(from, to));
            return refunds.stream()
                    .filter(refund -> refund.subMchid().equals(subMchid) && within(refund.acceptedAt(), from, to))
                    .sorted(Comparator.comparing(Refund::outRefundNo))
                    .map(refund -> new BillRefundRow(refund.outRefundNo(), refund.outTradeNo(), refund.subMchid(), refund.amountMinor(),
                            refund.status()))
                    .toList();
        }

        @Override
        public ReconciliationRow findReconciliation(LocalDate billDate, String subMchid) {
            Result result = results.get(new Key(billDate, subMchid));
            return result == null ? null : row(new Key(billDate, subMchid), result);
        }

        @Override
        public List<ReconciliationRow> findReconciliations(LocalDate billDate) {
            return results.entrySet().stream()
                    .filter(entry -> entry.getKey().billDate().equals(billDate))
                    .sorted(Map.Entry.comparingByKey(Comparator.comparing(Key::subMchid)))
                    .map(entry -> row(entry.getKey(), entry.getValue()))
                    .toList();
        }

        private static ReconciliationRow row(Key key, Result result) {
            return new ReconciliationRow(result.id, key.billDate(), key.subMchid(), result.status, result.matched,
                    result.reason, result.importedAt);
        }

        @Override
        public UUID saveReconciliation(LocalDate billDate, String subMchid, String status, int matchedCount,
                                       String failureReason, Instant at) {
            log.add("save:" + subMchid + ":" + status);
            // trade_reconciliation 的约束。
            if (!Set.of("IMPORTED", "FAILED").contains(status) || matchedCount < 0
                    || (failureReason != null && failureReason.length() > 200) || at == null) {
                throw new IllegalStateException("trade_reconciliation check violated");
            }
            Result result = results.computeIfAbsent(new Key(billDate, subMchid), key -> {
                Result created = new Result();
                created.id = UUID.randomUUID();
                return created;
            });
            result.status = status;
            result.matched = matchedCount;
            result.reason = failureReason;
            result.importedAt = at;
            return result.id;
        }

        @Override
        public int deleteDifferences(UUID reconciliationId) {
            log.add("deleteDifferences");
            int before = differences.size();
            differences.removeIf(diff -> diff.reconciliationId().equals(reconciliationId));
            return before - differences.size();
        }

        @Override
        public int insertDifference(UUID reconciliationId, String kind, String outTradeNo, String outRefundNo,
                                    Long platformAmountMinor, Long channelAmountMinor, Instant at) {
            log.add("insertDifference");
            // trade_reconciliation_difference 的约束。
            if (!Set.of("ONLY_IN_CHANNEL", "ONLY_IN_PLATFORM", "AMOUNT_MISMATCH", "STATUS_MISMATCH").contains(kind)
                    || (outTradeNo != null && outTradeNo.length() > 32)
                    || (outRefundNo != null && outRefundNo.length() > 64)
                    || results.values().stream().noneMatch(result -> result.id.equals(reconciliationId))) {
                throw new IllegalStateException("trade_reconciliation_difference check violated");
            }
            differences.add(new Diff(reconciliationId, kind, outTradeNo, outRefundNo, platformAmountMinor,
                    channelAmountMinor, at));
            return 1;
        }

        @Override
        public List<DifferenceRow> findDifferences(List<UUID> reconciliationIds) {
            assertThat(reconciliationIds).as("IN () is not valid SQL").isNotEmpty();
            List<Diff> found = new ArrayList<>(differences.stream()
                    .filter(diff -> reconciliationIds.contains(diff.reconciliationId())).toList());
            if (diffOrder != null) {
                found.sort(diffOrder);
            }
            return found.stream()
                    .map(diff -> new DifferenceRow(diff.reconciliationId(), diff.kind(), diff.outTradeNo(),
                            diff.outRefundNo(), diff.platformAmountMinor(), diff.channelAmountMinor()))
                    .toList();
        }
    }
}
