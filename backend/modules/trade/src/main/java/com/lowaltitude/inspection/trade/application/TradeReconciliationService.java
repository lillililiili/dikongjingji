package com.lowaltitude.inspection.trade.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.trade.application.PaymentChannel.BillLine;
import com.lowaltitude.inspection.trade.application.PaymentChannel.BillLineKind;
import com.lowaltitude.inspection.trade.application.PaymentChannel.TradeBill;
import com.lowaltitude.inspection.trade.application.TradeException.Error;
import com.lowaltitude.inspection.trade.application.TradeViews.ReconciliationDifferenceView;
import com.lowaltitude.inspection.trade.application.TradeViews.ReconciliationRunView;
import com.lowaltitude.inspection.trade.application.TradeViews.ReconciliationView;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeReconciliationMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeReconciliationMapper.BillPaymentRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeReconciliationMapper.BillRefundRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeReconciliationMapper.DifferenceRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeReconciliationMapper.ReconciliationRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 对账（E06）：按子商户下载微信某一天（北京时间自然日）的交易账单，和平台的支付单、退款单逐笔核对，记下差异。
 *
 * <ul>
 *   <li>账单里的支付行按商户订单号、退款行按商户退款单号找平台的单据：找不到是“微信有平台没有”，金额不同是“金额不一致”，
 *       平台的状态说明这笔钱没成（支付单没成功、退款单还没受理或已关闭，或账单说退款失败而平台记为到账）是“状态不一致”。</li>
 *   <li>平台记为付款成功、付款时间在这一天的支付单，和微信受理时间在这一天的退款单，账单里没有的是“平台有微信没有”。</li>
 *   <li>一天一个子商户一条结果；导入失败（账单还在生成、渠道不通、文件校验不过）记为 FAILED，下一轮再试；重跑覆盖。</li>
 * </ul>
 * 只记差异、告警，不自动改订单：差异要人工看过再处理。
 */
public class TradeReconciliationService {

    /** 微信每天 10 点后才生成前一天的账单。 */
    static final int BILL_READY_HOUR = 10;
    static final int MAX_REASON = 200;

    private static final Logger log = LoggerFactory.getLogger(TradeReconciliationService.class);
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    private final TradeReconciliationMapper mapper;
    private final Supplier<PaymentChannel> channels;
    private final TransactionOperations tx;
    private final Clock clock;

    TradeReconciliationService(
            TradeReconciliationMapper mapper,
            Supplier<PaymentChannel> channels,
            TransactionOperations tx,
            Clock clock
    ) {
        this.mapper = mapper;
        this.channels = channels;
        this.tx = tx;
        this.clock = clock;
    }

    // ---- 跑对账 ----

    /**
     * 对某一天跑对账。redo 为 false 时已经导入成功的子商户跳过；为 true 时全部重新下载、覆盖。
     * 只能对“今天以前”的日期跑（北京时间）。
     */
    public ReconciliationRunView reconcile(LocalDate billDate, boolean redo) {
        if (billDate == null || !billDate.isBefore(today())) {
            throw new IllegalArgumentException("bill date must be before today");
        }
        PaymentChannel channel = channels.get();
        if (channel == null) {
            throw new TradeException(Error.CHANNEL_UNAVAILABLE);
        }
        Instant from = billDate.atStartOfDay(BEIJING).toInstant();
        Instant to = billDate.plusDays(1).atStartOfDay(BEIJING).toInstant();
        List<String> subMchids = tx.execute(status -> mapper.findSubMchidsToReconcile(from, to));

        int imported = 0;
        int failed = 0;
        int skipped = 0;
        int differences = 0;
        for (String subMchid : subMchids) {
            if (!redo) {
                ReconciliationRow existing = tx.execute(status -> mapper.findReconciliation(billDate, subMchid));
                if (existing != null && "IMPORTED".equals(existing.status())) {
                    skipped++;
                    continue;
                }
            }
            TradeBill bill;
            try {
                bill = channel.tradeBill(subMchid, billDate);
            } catch (PaymentChannelException exception) {
                log.warn("bill of {} for {} could not be imported: {} {}", subMchid, billDate, exception.kind(), exception.code());
                recordFailure(billDate, subMchid, exception.code() + " " + exception.getMessage());
                failed++;
                continue;
            } catch (RuntimeException exception) {
                log.error("bill of {} for {} failed", subMchid, billDate, exception);
                // 程序错误的消息里可能带数据，原因只记异常类名。
                recordFailure(billDate, subMchid, "INTERNAL " + exception.getClass().getSimpleName());
                failed++;
                continue;
            }
            Comparison comparison = tx.execute(status -> {
                mapper.lock(billDate, subMchid);
                Comparison result = compare(bill,
                        byKey(fetchPayments(bill), BillPaymentRow::outTradeNo),
                        byKey(fetchRefunds(bill), BillRefundRow::outRefundNo),
                        mapper.findPaymentsSucceededBetween(subMchid, from, to),
                        mapper.findRefundsAcceptedBetween(subMchid, from, to));
                Instant now = clock.instant();
                UUID id = mapper.saveReconciliation(billDate, subMchid, "IMPORTED", result.matched(), null, now);
                mapper.deleteDifferences(id);
                for (Difference difference : result.differences()) {
                    mapper.insertDifference(id, difference.kind(), difference.outTradeNo(), difference.outRefundNo(),
                            difference.platformAmountMinor(), difference.channelAmountMinor(), now);
                }
                return result;
            });
            imported++;
            differences += comparison.differences().size();
            if (!comparison.differences().isEmpty()) {
                log.warn("reconciliation of {} for {} found {} differences", subMchid, billDate,
                        comparison.differences().size());
            }
        }
        return new ReconciliationRunView(billDate, subMchids.size(), imported, failed, skipped, differences);
    }

    /** 记一次导入失败。已经导入成功的不要被一次失败的重跑盖掉。 */
    private void recordFailure(LocalDate billDate, String subMchid, String reason) {
        String truncated = truncate(reason);
        tx.executeWithoutResult(status -> {
            mapper.lock(billDate, subMchid);
            ReconciliationRow existing = mapper.findReconciliation(billDate, subMchid);
            if (existing == null || !"IMPORTED".equals(existing.status())) {
                UUID id = mapper.saveReconciliation(billDate, subMchid, "FAILED", 0, truncated, clock.instant());
                mapper.deleteDifferences(id);
            }
        });
    }

    /** 定时任务用：北京时间 10 点以后，把昨天和前天还没导入成功的补上。没有配置渠道时什么也不做。 */
    public int reconcileRecentDays() {
        if (channels.get() == null) {
            return 0;
        }
        LocalDate today = today();
        if (clock.instant().atZone(BEIJING).getHour() < BILL_READY_HOUR) {
            // 前天的账单已经有了，昨天的还没有。
            return reconcile(today.minusDays(2), false).imported();
        }
        return reconcile(today.minusDays(2), false).imported() + reconcile(today.minusDays(1), false).imported();
    }

    private List<BillPaymentRow> fetchPayments(TradeBill bill) {
        List<String> numbers = bill.lines().stream()
                .filter(line -> line.kind() == BillLineKind.PAYMENT)
                .map(BillLine::outTradeNo).distinct().toList();
        return numbers.isEmpty() ? List.of() : mapper.findPaymentsByOutTradeNos(numbers);
    }

    private List<BillRefundRow> fetchRefunds(TradeBill bill) {
        List<String> numbers = bill.lines().stream()
                .filter(line -> line.kind() == BillLineKind.REFUND)
                .map(BillLine::outRefundNo).distinct().toList();
        return numbers.isEmpty() ? List.of() : mapper.findRefundsByOutRefundNos(numbers);
    }

    // ---- 逐笔核对（纯计算） ----

    record Difference(String kind, String outTradeNo, String outRefundNo, Long platformAmountMinor,
                      Long channelAmountMinor) {
    }

    record Comparison(int matched, List<Difference> differences) {
    }

    static Comparison compare(
            TradeBill bill,
            Map<String, BillPaymentRow> paymentsByNo,
            Map<String, BillRefundRow> refundsByNo,
            List<BillPaymentRow> platformPaymentsOfDay,
            List<BillRefundRow> platformRefundsOfDay
    ) {
        int matched = 0;
        List<Difference> differences = new ArrayList<>();
        Set<String> seenPayments = new HashSet<>();
        Set<String> seenRefunds = new HashSet<>();
        for (BillLine line : bill.lines()) {
            switch (line.kind()) {
                case PAYMENT -> {
                    if (!seenPayments.add(line.outTradeNo())) {
                        continue;
                    }
                    BillPaymentRow payment = paymentsByNo.get(line.outTradeNo());
                    if (payment == null || !bill.subMchid().equals(payment.subMchid())) {
                        differences.add(new Difference("ONLY_IN_CHANNEL", line.outTradeNo(), null, null,
                                line.orderAmountMinor()));
                    } else if (!"SUCCEEDED".equals(payment.status())) {
                        differences.add(new Difference("STATUS_MISMATCH", line.outTradeNo(), null,
                                payment.amountMinor(), line.orderAmountMinor()));
                    } else if (payment.amountMinor() != line.orderAmountMinor()) {
                        differences.add(new Difference("AMOUNT_MISMATCH", line.outTradeNo(), null,
                                payment.amountMinor(), line.orderAmountMinor()));
                    } else {
                        matched++;
                    }
                }
                case REFUND -> {
                    if (!seenRefunds.add(line.outRefundNo())) {
                        continue;
                    }
                    BillRefundRow refund = refundsByNo.get(line.outRefundNo());
                    if (refund == null || !bill.subMchid().equals(refund.subMchid())) {
                        differences.add(new Difference("ONLY_IN_CHANNEL", line.outTradeNo(), line.outRefundNo(), null,
                                line.refundAmountMinor()));
                    } else if (refund.amountMinor() != line.refundAmountMinor()) {
                        differences.add(new Difference("AMOUNT_MISMATCH", line.outTradeNo(), line.outRefundNo(),
                                refund.amountMinor(), line.refundAmountMinor()));
                    } else if ("REQUESTED".equals(refund.status()) || "CLOSED".equals(refund.status())
                            || ("FAIL".equals(line.refundStatus()) && "SUCCEEDED".equals(refund.status()))) {
                        // 微信受理了、平台却以为没受理或已关闭；或者账单说退款失败、平台记为到账。
                        differences.add(new Difference("STATUS_MISMATCH", line.outTradeNo(), line.outRefundNo(),
                                refund.amountMinor(), line.refundAmountMinor()));
                    } else {
                        matched++;
                    }
                }
                case OTHER -> {
                    // 付款码撤销等，App 支付不会有。
                }
            }
        }
        for (BillPaymentRow payment : platformPaymentsOfDay) {
            if (!seenPayments.contains(payment.outTradeNo())) {
                differences.add(new Difference("ONLY_IN_PLATFORM", payment.outTradeNo(), null, payment.amountMinor(), null));
            }
        }
        for (BillRefundRow refund : platformRefundsOfDay) {
            if (!seenRefunds.contains(refund.outRefundNo())) {
                differences.add(new Difference("ONLY_IN_PLATFORM", refund.outTradeNo(), refund.outRefundNo(),
                        refund.amountMinor(), null));
            }
        }
        return new Comparison(matched, differences);
    }

    // ---- 读结果 ----

    /**
     * 某一天的对账结果。这一天要对账的子商户全部导入成功才给；有没导入或导入失败的回 RECONCILIATION_NOT_READY。
     */
    public ReconciliationView read(LocalDate billDate) {
        if (billDate == null) {
            throw new IllegalArgumentException("bill date is required");
        }
        Instant from = billDate.atStartOfDay(BEIJING).toInstant();
        Instant to = billDate.plusDays(1).atStartOfDay(BEIJING).toInstant();
        return tx.execute(status -> {
            List<ReconciliationRow> rows = mapper.findReconciliations(billDate);
            Map<String, ReconciliationRow> bySub = rows.stream()
                    .collect(Collectors.toMap(ReconciliationRow::subMchid, Function.identity()));
            List<String> expected = mapper.findSubMchidsToReconcile(from, to);
            if (rows.isEmpty() && expected.isEmpty() && billDate.isBefore(today())) {
                return new ReconciliationView(billDate, null, 0, List.of());
            }
            boolean complete = !rows.isEmpty()
                    && rows.stream().allMatch(row -> "IMPORTED".equals(row.status()))
                    && bySub.keySet().containsAll(expected);
            if (!complete) {
                throw new TradeException(Error.RECONCILIATION_NOT_READY);
            }
            Map<UUID, String> subOf = rows.stream()
                    .collect(Collectors.toMap(ReconciliationRow::id, ReconciliationRow::subMchid));
            List<ReconciliationDifferenceView> differences = mapper.findDifferences(List.copyOf(subOf.keySet())).stream()
                    .map((DifferenceRow row) -> new ReconciliationDifferenceView(row.kind(), subOf.get(row.reconciliationId()),
                            row.outTradeNo(), row.outRefundNo(), row.platformAmountMinor(), row.channelAmountMinor()))
                    .sorted(Comparator.comparing(ReconciliationDifferenceView::subMchid)
                            .thenComparing(ReconciliationDifferenceView::kind)
                            .thenComparing(view -> view.outTradeNo() == null ? "" : view.outTradeNo())
                            .thenComparing(view -> view.outRefundNo() == null ? "" : view.outRefundNo()))
                    .toList();
            Instant importedAt = rows.stream().map(ReconciliationRow::importedAt).max(Comparator.naturalOrder()).orElseThrow();
            int matched = rows.stream().mapToInt(ReconciliationRow::matchedCount).sum();
            return new ReconciliationView(billDate, importedAt, matched, differences);
        });
    }

    /** CSV（UTF-8，带 BOM 方便 Excel 打开）。以 = + - @ 开头的格可能被表格软件当成公式，前面加单引号。 */
    public static String csv(ReconciliationView view) {
        StringBuilder out = new StringBuilder("﻿");
        out.append("日期,类型,说明,子商户号,商户订单号,商户退款单号,平台金额（元）,微信金额（元）\r\n");
        for (ReconciliationDifferenceView difference : view.differences()) {
            out.append(String.join(",",
                    cell(view.date().toString()),
                    cell(difference.kind()),
                    cell(describe(difference.kind())),
                    cell(difference.subMchid()),
                    cell(difference.outTradeNo()),
                    cell(difference.outRefundNo()),
                    cell(yuan(difference.platformAmountMinor())),
                    cell(yuan(difference.channelAmountMinor()))));
            out.append("\r\n");
        }
        return out.toString();
    }

    private static String describe(String kind) {
        return switch (kind) {
            case "ONLY_IN_CHANNEL" -> "微信有、平台没有";
            case "ONLY_IN_PLATFORM" -> "平台有、微信没有";
            case "AMOUNT_MISMATCH" -> "金额不一致";
            case "STATUS_MISMATCH" -> "状态不一致";
            default -> kind;
        };
    }

    private static String yuan(Long minor) {
        if (minor == null) {
            return "";
        }
        return java.math.BigDecimal.valueOf(minor, 2).toPlainString();
    }

    private static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String safe = value;
        if ("=+-@".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            safe = "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    // ---- 小工具 ----

    private LocalDate today() {
        return clock.instant().atZone(BEIJING).toLocalDate();
    }

    private static <T> Map<String, T> byKey(List<T> rows, Function<T, String> key) {
        Map<String, T> map = new HashMap<>();
        for (T row : rows) {
            map.putIfAbsent(key.apply(row), row);
        }
        return map;
    }

    private static String truncate(String value) {
        return value == null || value.length() <= MAX_REASON ? value : value.substring(0, MAX_REASON);
    }
}
