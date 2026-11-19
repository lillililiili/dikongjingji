package com.lowaltitude.inspection.trade.infrastructure.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.trade.application.PaymentChannel.BillLine;
import com.lowaltitude.inspection.trade.application.PaymentChannel.BillLineKind;
import com.lowaltitude.inspection.trade.application.PaymentChannelException;
import com.lowaltitude.inspection.trade.application.PaymentChannelException.Kind;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 交易账单（ALL）的解析。账单按文档 4013080599 的样子组装：表头 27 列、不带反引号；明细和汇总的每个字段前带一个反引号；
 * 明细后是汇总表头（第一列“总交易单数”）和一行汇总。数据都是编的，不是真实交易。
 */
class WechatPayBillParserTest {

    /** 服务商 ALL 账单的 27 列表头。 */
    static final List<String> HEADER = List.of(
            "交易时间", "公众账号ID", "商户号", "特约商户号", "设备号", "微信订单号", "商户订单号", "用户标识", "交易类型",
            "交易状态", "付款银行", "货币种类", "应结订单金额", "代金券金额", "微信退款单号", "商户退款单号", "退款金额",
            "充值券退款金额", "退款类型", "退款状态", "商品名称", "商户数据包", "手续费", "费率", "订单金额", "申请退款金额",
            "费率备注");

    static final List<String> SUMMARY_HEADER = List.of(
            "总交易单数", "应结订单总金额", "退款总金额", "充值券退款总金额", "手续费总金额", "订单总金额", "申请退款总金额");

    static final String SUB_MCHID = "1900000101";
    static final String OUT_TRADE_NO = "P2026111700000001A1";
    static final String OUT_REFUND_NO = "R2026111700000001A1";

    static Map<String, String> payment() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("交易时间", "2026-11-17 10:05:12");
        row.put("公众账号ID", "wxd678efh567hg6787");
        row.put("商户号", "1900000001");
        row.put("特约商户号", SUB_MCHID);
        row.put("设备号", "");
        row.put("微信订单号", "4200000985202611171234567890");
        row.put("商户订单号", OUT_TRADE_NO);
        row.put("用户标识", "oUpF8uMuAJO_M2pxb1Q9zNjWeS6o");
        row.put("交易类型", "APP");
        row.put("交易状态", "SUCCESS");
        row.put("付款银行", "CMC");
        row.put("货币种类", "CNY");
        row.put("应结订单金额", "2760.00");
        row.put("代金券金额", "0.00");
        row.put("微信退款单号", "0");
        row.put("商户退款单号", "0");
        row.put("退款金额", "0.00");
        row.put("充值券退款金额", "0.00");
        row.put("退款类型", "");
        row.put("退款状态", "");
        row.put("商品名称", "编程机器人套装");
        row.put("商户数据包", "");
        row.put("手续费", "16.56");
        row.put("费率", "0.60%");
        row.put("订单金额", "2760.00");
        row.put("申请退款金额", "0.00");
        row.put("费率备注", "");
        return row;
    }

    static Map<String, String> refund() {
        Map<String, String> row = payment();
        row.put("交易时间", "2026-11-17 15:20:00");
        row.put("交易状态", "REFUND");
        row.put("应结订单金额", "0.00");
        row.put("微信退款单号", "50000000382019052709732678859");
        row.put("商户退款单号", OUT_REFUND_NO);
        row.put("退款金额", "1380.00");
        row.put("退款类型", "ORIGINAL");
        row.put("退款状态", "SUCCESS");
        row.put("手续费", "-8.28");
        row.put("订单金额", "0.00");
        row.put("申请退款金额", "1380.00");
        return row;
    }

    static Map<String, String> summary(String count, String orderTotal, String refundTotal) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("总交易单数", count);
        row.put("应结订单总金额", orderTotal);
        row.put("退款总金额", refundTotal);
        row.put("充值券退款总金额", "0.00");
        row.put("手续费总金额", "8.28");
        row.put("订单总金额", orderTotal);
        row.put("申请退款总金额", refundTotal);
        return row;
    }

    /** 表头一行（不带反引号）。 */
    static String header(List<String> columns) {
        return String.join(",", columns);
    }

    /** 一行明细或汇总：按列名取值，每个字段前加反引号。 */
    static String row(List<String> columns, Map<String, String> values) {
        return columns.stream().map(column -> "`" + values.getOrDefault(column, "")).collect(Collectors.joining(","));
    }

    static String bill(List<String> columns, List<Map<String, String>> rows, Map<String, String> summary) {
        List<String> lines = new ArrayList<>();
        lines.add(header(columns));
        rows.forEach(values -> lines.add(row(columns, values)));
        lines.add(header(SUMMARY_HEADER));
        lines.add(row(SUMMARY_HEADER, summary));
        return String.join("\r\n", lines) + "\r\n";
    }

    static String standardBill() {
        return bill(HEADER, List.of(payment(), refund()), summary("2", "2760.00", "1380.00"));
    }

    // ---- 正常的账单 ----

    @Test
    void aPaymentAndARefundAreReadByColumnName() {
        List<BillLine> lines = WechatPayBillParser.parse(standardBill());

        assertThat(lines).containsExactly(
                new BillLine(BillLineKind.PAYMENT, "SUCCESS", Instant.parse("2026-11-17T02:05:12Z"), SUB_MCHID,
                        "4200000985202611171234567890", OUT_TRADE_NO, null, null, "CNY", 276000, 0, null),
                new BillLine(BillLineKind.REFUND, "REFUND", Instant.parse("2026-11-17T07:20:00Z"), SUB_MCHID,
                        "4200000985202611171234567890", OUT_TRADE_NO, "50000000382019052709732678859", OUT_REFUND_NO,
                        "CNY", 0, 138000, "SUCCESS"));
    }

    // 文档的 27 列和这里的组装方式一致（防止测试自己的表头写错）。
    @Test
    void theTestBillHasTheDocumentedShape() {
        assertThat(HEADER).hasSize(27).doesNotHaveDuplicates();
        assertThat(SUMMARY_HEADER.getFirst()).isEqualTo(WechatPayBillParser.SUMMARY_FIRST_COLUMN);
        String[] lines = standardBill().split("\r\n");
        assertThat(lines).hasSize(5);
        assertThat(lines[1].split(",", -1)).hasSize(27).allSatisfy(field -> assertThat(field).startsWith("`"));
        assertThat(lines[0]).doesNotContain("`");
    }

    // 列的位置不重要：按列名取。
    @Test
    void theColumnOrderDoesNotMatter() {
        List<String> shuffled = new ArrayList<>(HEADER);
        Collections.reverse(shuffled);

        assertThat(WechatPayBillParser.parse(bill(shuffled, List.of(payment(), refund()), summary("2", "2760.00", "1380.00"))))
                .isEqualTo(WechatPayBillParser.parse(standardBill()));
    }

    // 表头带反引号、字段前后有空白、换行是 LF 还是 CRLF、中间有空行，都照样读。
    @Test
    void backticksBlanksAndLineEndingsAreTolerated() {
        String text = standardBill().replace("\r\n", "\n\n")
                .replace("交易时间,公众账号ID", "`交易时间, `公众账号ID")
                .replace("`" + OUT_TRADE_NO, " `" + OUT_TRADE_NO + " ");

        assertThat(WechatPayBillParser.parse(text)).isEqualTo(WechatPayBillParser.parse(standardBill()));
    }

    // 早期格式少“应结订单金额”“代金券金额”两列：按列名读，订单金额照样有。
    @Test
    void theEarlyFormatWithoutTwoColumnsIsRead() {
        List<String> early = HEADER.stream().filter(column -> !column.equals("应结订单金额") && !column.equals("代金券金额")).toList();
        assertThat(early).hasSize(25);

        List<BillLine> lines = WechatPayBillParser.parse(bill(early, List.of(payment(), refund()), summary("2", "2760.00", "1380.00")));

        assertThat(lines).isEqualTo(WechatPayBillParser.parse(standardBill()));
    }

    // 没有“订单金额”列时用“应结订单金额”，和“应结订单总金额”核对。
    @Test
    void withoutAnOrderAmountColumnTheSettlementAmountIsUsed() {
        List<String> settlementOnly = HEADER.stream().filter(column -> !column.equals("订单金额")).toList();
        Map<String, String> paid = payment();
        paid.put("应结订单金额", "2700.00");
        Map<String, String> totals = summary("1", "2700.00", "0.00");
        totals.put("订单总金额", "9999.00");

        List<BillLine> lines = WechatPayBillParser.parse(bill(settlementOnly, List.of(paid), totals));

        assertThat(lines).singleElement().satisfies(line -> assertThat(line.orderAmountMinor()).isEqualTo(270000));

        totals.put("应结订单总金额", "2760.00");
        assertFormat(() -> WechatPayBillParser.parse(bill(settlementOnly, List.of(paid), totals)));
    }

    // 有“订单金额”列时用它（不是应结订单金额），和“订单总金额”核对。
    @Test
    void theOrderAmountColumnWinsOverTheSettlementAmount() {
        Map<String, String> paid = payment();
        paid.put("应结订单金额", "2700.00");
        Map<String, String> totals = summary("1", "2760.00", "0.00");
        totals.put("应结订单总金额", "2700.00");

        assertThat(WechatPayBillParser.parse(bill(HEADER, List.of(paid), totals)))
                .singleElement().satisfies(line -> assertThat(line.orderAmountMinor()).isEqualTo(276000));
    }

    // 退款状态列没有时为 null；“0”和空串当作没有。
    @Test
    void zeroAndBlankMeanNothing() {
        List<String> withoutRefundStatus = HEADER.stream().filter(column -> !column.equals("退款状态")).toList();
        Map<String, String> paid = payment();
        paid.put("微信订单号", "0");
        paid.put("货币种类", "");

        BillLine line = WechatPayBillParser.parse(bill(withoutRefundStatus, List.of(paid), summary("1", "2760.00", "0.00")))
                .getFirst();

        assertThat(line.refundStatus()).isNull();
        assertThat(line.transactionId()).isNull();
        assertThat(line.currency()).isNull();
        assertThat(line.refundId()).isNull();
        assertThat(line.outRefundNo()).isNull();
    }

    // 支付行上即使填了退款单号也不读（只有退款行有）。
    @Test
    void aPaymentRowCarriesNoRefundNumbers() {
        Map<String, String> paid = payment();
        paid.put("微信退款单号", "50000000000000000000000000001");
        paid.put("商户退款单号", "R0000000001");

        BillLine line = WechatPayBillParser.parse(bill(HEADER, List.of(paid), summary("1", "2760.00", "0.00"))).getFirst();

        assertThat(line.refundId()).isNull();
        assertThat(line.outRefundNo()).isNull();
    }

    // 别的交易状态（比如付款码撤销 REVOKED）记为 OTHER，不要求单号；笔数照样算进汇总。
    @Test
    void otherStatesAreKeptAsOther() {
        Map<String, String> revoked = payment();
        revoked.put("交易状态", "REVOKED");
        revoked.put("商户订单号", "");
        revoked.put("订单金额", "0.00");

        List<BillLine> lines = WechatPayBillParser.parse(bill(HEADER, List.of(payment(), revoked),
                summary("2", "2760.00", "0.00")));

        assertThat(lines).extracting(BillLine::kind).containsExactly(BillLineKind.PAYMENT, BillLineKind.OTHER);
        assertThat(lines.get(1).rawState()).isEqualTo("REVOKED");
        assertThat(lines.get(1).outTradeNo()).isNull();
    }

    // 只有汇总、没有明细（笔数 0）：空账单。
    @Test
    void aBillWithOnlyTheSummaryIsEmpty() {
        assertThat(WechatPayBillParser.parse(bill(HEADER, List.of(), summary("0", "0.00", "0.00")))).isEmpty();
    }

    // 汇总里没有金额合计列（早期格式）：只核对笔数。
    @Test
    void aSummaryWithoutTotalsIsCheckedByCountOnly() {
        String text = header(HEADER) + "\n" + row(HEADER, payment()) + "\n总交易单数,手续费总金额\n`1,`16.56\n";

        assertThat(WechatPayBillParser.parse(text)).hasSize(1);
        assertFormat(() -> WechatPayBillParser.parse(text.replace("`1,`16.56", "`2,`16.56")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2760", "2760.0", "2760.00", "0002760.00"})
    void amountsHaveAtMostTwoDecimals(String amount) {
        assertThat(WechatPayBillParser.minor(amount)).isEqualTo(276000);
    }

    @Test
    void smallAmountsAreExactInCents() {
        assertThat(WechatPayBillParser.minor("0.01")).isEqualTo(1);
        assertThat(WechatPayBillParser.minor("0.1")).isEqualTo(10);
        assertThat(WechatPayBillParser.minor("")).isZero();
        assertThat(WechatPayBillParser.minor(null)).isZero();
        assertThat(WechatPayBillParser.minor("92233720368547758.07")).isEqualTo(Long.MAX_VALUE);
    }

    // 只认“数字 + 最多两位小数”：带空格的不收（字段在拆行时已经去过首尾空白，到这里还有空格就是格式不对）。
    @ParameterizedTest
    @ValueSource(strings = {" 1.00", "1.00 ", "1 000.00", "1.0 0", "\t1"})
    void amountsWithBlanksAreRefused(String amount) {
        assertFormat(() -> WechatPayBillParser.minor(amount));
    }

    @Test
    void fieldsLoseOneLeadingBacktickAndTheBlanksAroundIt() {
        assertThat(WechatPayBillParser.fields("`a, `b ,c,,`")).containsExactly("a", "b", "c", "", "");
    }

    // ---- 不能信的账单 ----

    @ParameterizedTest
    @ValueSource(strings = {"2760.001", "27.600", "abc", "2,760.00", "92233720368547758.08", "¥2760.00", "1e2", "1E+2",
            "-1.00", "+1.00", "2760.", ".50", "0x10", "1_000", "１００"})
    void amountsThatAreNotYuanWithTwoDecimalsAreRefused(String amount) {
        assertFormat(() -> WechatPayBillParser.minor(amount));
        Map<String, String> paid = payment();
        paid.put("订单金额", amount);
        // 带逗号的金额会多出一列，同样不收。
        assertFormat(() -> WechatPayBillParser.parse(bill(HEADER, List.of(paid), summary("1", "2760.00", "0.00"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026/11/17 10:05:12", "2026-11-17T10:05:12", "2026-11-17", "2026-11-17 25:00:00", ""})
    void aMalformedTimeIsRefused(String time) {
        Map<String, String> paid = payment();
        paid.put("交易时间", time);

        assertFormat(() -> WechatPayBillParser.parse(bill(HEADER, List.of(paid), summary("1", "2760.00", "0.00"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"交易时间", "特约商户号", "微信订单号", "商户订单号", "交易状态", "货币种类", "微信退款单号",
            "商户退款单号", "退款金额"})
    void everyRequiredColumnMustBeThere(String missing) {
        List<String> columns = HEADER.stream().filter(column -> !column.equals(missing)).toList();

        assertFormat(() -> WechatPayBillParser.parse(bill(columns, List.of(payment()), summary("1", "2760.00", "0.00"))));
    }

    @Test
    void anOrderAmountColumnOfSomeKindMustBeThere() {
        List<String> columns = HEADER.stream().filter(column -> !column.equals("订单金额") && !column.equals("应结订单金额")).toList();

        assertFormat(() -> WechatPayBillParser.parse(bill(columns, List.of(payment()), summary("1", "2760.00", "0.00"))));
    }

    // 每行的列数必须等于表头（商品名称里没转义的逗号、截断的行）。
    @Test
    void aRowWithAnotherNumberOfFieldsIsRefused() {
        Map<String, String> paid = payment();
        paid.put("商品名称", "编程机器人套装,含电池");
        assertFormat(() -> WechatPayBillParser.parse(bill(HEADER, List.of(paid), summary("1", "2760.00", "0.00"))));

        String shortRow = standardBill().replace(",`" + "编程机器人套装", "");
        assertFormat(() -> WechatPayBillParser.parse(shortRow));
    }

    @Test
    void aPaymentOrRefundRowWithoutItsNumberIsRefused() {
        Map<String, String> paid = payment();
        paid.put("商户订单号", "");
        assertFormat(() -> WechatPayBillParser.parse(bill(HEADER, List.of(paid), summary("1", "2760.00", "0.00"))));

        Map<String, String> refunded = refund();
        refunded.put("商户订单号", "0");
        assertFormat(() -> WechatPayBillParser.parse(bill(HEADER, List.of(refunded), summary("1", "0.00", "1380.00"))));

        Map<String, String> unnumbered = refund();
        unnumbered.put("商户退款单号", "0");
        assertFormat(() -> WechatPayBillParser.parse(bill(HEADER, List.of(unnumbered), summary("1", "0.00", "1380.00"))));
    }

    @Test
    void anEmptyBillOrOneWithoutItsSummaryIsRefused() {
        assertFormat(() -> WechatPayBillParser.parse(""));
        assertFormat(() -> WechatPayBillParser.parse(" \r\n \n"));
        // 只有表头和明细。
        assertFormat(() -> WechatPayBillParser.parse(header(HEADER) + "\n" + row(HEADER, payment()) + "\n"));
        // 有汇总表头、没有汇总行。
        assertFormat(() -> WechatPayBillParser.parse(header(HEADER) + "\n" + row(HEADER, payment()) + "\n"
                + header(SUMMARY_HEADER) + "\n"));
        // 汇总行的列数和汇总表头不同。
        assertFormat(() -> WechatPayBillParser.parse(header(HEADER) + "\n" + row(HEADER, payment()) + "\n"
                + header(SUMMARY_HEADER) + "\n`1,`2760.00\n"));
    }

    @Test
    void aSummaryThatDoesNotAddUpIsRefused() {
        // 笔数不对。
        assertFormat(() -> WechatPayBillParser.parse(bill(HEADER, List.of(payment(), refund()), summary("3", "2760.00", "1380.00"))));
        assertFormat(() -> WechatPayBillParser.parse(bill(HEADER, List.of(payment(), refund()), summary("1", "2760.00", "1380.00"))));
        // 笔数不是数。
        assertFormat(() -> WechatPayBillParser.parse(bill(HEADER, List.of(payment(), refund()), summary("两", "2760.00", "1380.00"))));
        // 订单总金额差一分。
        assertFormat(() -> WechatPayBillParser.parse(bill(HEADER, List.of(payment(), refund()), summary("2", "2760.01", "1380.00"))));
        // 退款总金额差一分。
        assertFormat(() -> WechatPayBillParser.parse(bill(HEADER, List.of(payment(), refund()), summary("2", "2760.00", "1379.99"))));
        // 汇总金额本身格式不对。
        assertFormat(() -> WechatPayBillParser.parse(bill(HEADER, List.of(payment(), refund()), summary("2", "2760.000", "1380.00"))));
    }

    // 汇总的“总交易单数”列名不能错（否则汇总行会被当成明细读，列数对不上）。
    @Test
    void aSummaryUnderAnotherHeaderIsNotASummary() {
        String text = standardBill().replace("总交易单数", "总笔数");

        assertFormat(() -> WechatPayBillParser.parse(text));
    }

    // 文件开头的 UTF-8 BOM 去掉一个，照常读；只去开头的一个，第二个还在就找不到“交易时间”。
    @Test
    void aLeadingByteOrderMarkIsSkipped() {
        assertThat(WechatPayBillParser.parse("\uFEFF" + standardBill())).isEqualTo(WechatPayBillParser.parse(standardBill()));
        assertFormat(() -> WechatPayBillParser.parse("\uFEFF\uFEFF" + standardBill()));
    }

    private static void assertFormat(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PaymentChannelException.class, exception -> {
            assertThat(exception.kind()).as(exception.getMessage()).isEqualTo(Kind.UNTRUSTED);
            assertThat(exception.code()).isEqualTo("FORMAT");
            assertThat(exception.httpStatus()).isZero();
        });
    }
}
