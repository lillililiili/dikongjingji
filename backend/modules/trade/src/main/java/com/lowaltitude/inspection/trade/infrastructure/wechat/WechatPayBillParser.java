package com.lowaltitude.inspection.trade.infrastructure.wechat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.lowaltitude.inspection.trade.application.PaymentChannel.BillLine;
import com.lowaltitude.inspection.trade.application.PaymentChannel.BillLineKind;
import com.lowaltitude.inspection.trade.application.PaymentChannelException;
import com.lowaltitude.inspection.trade.application.PaymentChannelException.Kind;

/**
 * 解析微信支付的交易账单（ALL，文档 pay.weixin.qq.com/doc/v3/partner/4013080599）。
 *
 * <ul>
 *   <li>第一行是表头，逗号分隔；明细和汇总的每个字段前都有一个反引号（`），先去掉。</li>
 *   <li>按列名取值，不按位置：早期格式少“应结订单金额”“代金券金额”两列也能读。</li>
 *   <li>“交易状态”SUCCESS 是支付行，REFUND 是退款行（退款单号、退款金额有值，订单金额为 0）。</li>
 *   <li>金额单位是元、两位小数，这里换成分；交易时间是北京时间。</li>
 *   <li>明细后面是汇总表头（第一列“总交易单数”）和一行汇总：笔数和金额合计要和明细对得上，对不上整份不信。</li>
 * </ul>
 */
final class WechatPayBillParser {

    static final String SUMMARY_FIRST_COLUMN = "总交易单数";
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final java.util.regex.Pattern YUAN = java.util.regex.Pattern.compile("^[0-9]+(\\.[0-9]{1,2})?$");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<String> REQUIRED = List.of(
            "交易时间", "特约商户号", "微信订单号", "商户订单号", "交易状态", "货币种类",
            "微信退款单号", "商户退款单号", "退款金额");

    private WechatPayBillParser() {
    }

    static List<BillLine> parse(String text) {
        // 文件开头可能带 UTF-8 的 BOM：去掉一个，免得第一列名前面多出这个字符、找不到“交易时间”。
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        List<String> lines = text.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        if (lines.isEmpty()) {
            throw format("bill is empty");
        }
        List<String> header = fields(lines.getFirst());
        Map<String, Integer> column = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            column.putIfAbsent(header.get(i), i);
        }
        for (String required : REQUIRED) {
            if (!column.containsKey(required)) {
                throw format("bill header misses " + required);
            }
        }
        String orderAmountColumn = column.containsKey("订单金额") ? "订单金额" : "应结订单金额";
        if (!column.containsKey(orderAmountColumn)) {
            throw format("bill header misses the order amount");
        }

        List<BillLine> result = new ArrayList<>();
        long orderSum = 0;
        long refundSum = 0;
        int index = 1;
        for (; index < lines.size(); index++) {
            List<String> row = fields(lines.get(index));
            if (SUMMARY_FIRST_COLUMN.equals(row.getFirst())) {
                break;
            }
            if (row.size() != header.size()) {
                // 商品名称里的逗号按文档会转义；列数还对不上，说明这一行没法可靠地读。
                throw format("bill row " + index + " has " + row.size() + " fields, header has " + header.size());
            }
            BillLine line = line(row, column, orderAmountColumn);
            orderSum += line.orderAmountMinor();
            refundSum += line.refundAmountMinor();
            result.add(line);
        }
        if (index + 1 >= lines.size()) {
            throw format("bill summary is missing");
        }
        List<String> summaryHeader = fields(lines.get(index));
        List<String> summary = fields(lines.get(index + 1));
        if (summary.size() != summaryHeader.size()) {
            throw format("bill summary is malformed");
        }
        Map<String, String> totals = new HashMap<>();
        for (int i = 0; i < summaryHeader.size(); i++) {
            totals.put(summaryHeader.get(i), summary.get(i));
        }
        long count;
        try {
            count = Long.parseLong(totals.getOrDefault(SUMMARY_FIRST_COLUMN, ""));
        } catch (NumberFormatException exception) {
            throw format("bill summary count is not a number");
        }
        if (count != result.size()) {
            throw format("bill summary counts " + count + " rows, the bill has " + result.size());
        }
        // 汇总列名在明细列名后面加了“总”：订单总金额、退款总金额。早期格式没有的就不比。
        String orderTotalColumn = "订单金额".equals(orderAmountColumn) ? "订单总金额" : "应结订单总金额";
        if (totals.containsKey(orderTotalColumn) && minor(totals.get(orderTotalColumn)) != orderSum) {
            throw format("bill order total does not add up");
        }
        if (totals.containsKey("退款总金额") && minor(totals.get("退款总金额")) != refundSum) {
            throw format("bill refund total does not add up");
        }
        return result;
    }

    private static BillLine line(List<String> row, Map<String, Integer> column, String orderAmountColumn) {
        String state = value(row, column, "交易状态");
        BillLineKind kind = switch (state) {
            case "SUCCESS" -> BillLineKind.PAYMENT;
            case "REFUND" -> BillLineKind.REFUND;
            default -> BillLineKind.OTHER;
        };
        Instant occurredAt;
        try {
            occurredAt = LocalDateTime.parse(value(row, column, "交易时间"), TIME).atZone(BEIJING).toInstant();
        } catch (DateTimeParseException exception) {
            throw format("bill time is malformed");
        }
        String outTradeNo = blankToNull(value(row, column, "商户订单号"));
        if (kind != BillLineKind.OTHER && outTradeNo == null) {
            throw format("bill row has no out_trade_no");
        }
        String outRefundNo = kind == BillLineKind.REFUND ? blankToNull(value(row, column, "商户退款单号")) : null;
        if (kind == BillLineKind.REFUND && outRefundNo == null) {
            throw format("bill refund row has no out_refund_no");
        }
        return new BillLine(
                kind,
                state,
                occurredAt,
                blankToNull(value(row, column, "特约商户号")),
                blankToNull(value(row, column, "微信订单号")),
                outTradeNo,
                kind == BillLineKind.REFUND ? blankToNull(value(row, column, "微信退款单号")) : null,
                outRefundNo,
                blankToNull(value(row, column, "货币种类")),
                minor(value(row, column, orderAmountColumn)),
                minor(value(row, column, "退款金额")),
                column.containsKey("退款状态") ? blankToNull(value(row, column, "退款状态")) : null);
    }

    /** 按逗号拆开，去掉每个字段前的反引号和首尾空白。 */
    static List<String> fields(String line) {
        List<String> fields = new ArrayList<>();
        for (String field : line.split(",", -1)) {
            String value = field.strip();
            if (value.startsWith("`")) {
                value = value.substring(1);
            }
            fields.add(value.strip());
        }
        return fields;
    }

    private static String value(List<String> row, Map<String, Integer> column, String name) {
        return row.get(column.get(name));
    }

    /** “0”和空串当作没有（订单行的退款单号填 0）。 */
    private static String blankToNull(String value) {
        return value == null || value.isEmpty() || "0".equals(value) ? null : value;
    }

    /** 元（非负，最多两位小数，不带符号、指数、空格和千分位）→ 分；不是这种格式的不信。 */
    static long minor(String yuan) {
        if (yuan == null || yuan.isEmpty()) {
            return 0;
        }
        if (!YUAN.matcher(yuan).matches()) {
            throw format("bill amount is malformed");
        }
        try {
            return new BigDecimal(yuan).movePointRight(2).longValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw format("bill amount is malformed");
        }
    }

    private static PaymentChannelException format(String message) {
        return new PaymentChannelException(Kind.UNTRUSTED, "FORMAT", 0, "wechat pay " + message);
    }
}
