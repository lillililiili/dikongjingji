package com.lowaltitude.inspection.application.serviceflow;

import java.util.List;
import java.util.function.Function;

/** Builds the small jsonb payloads stored on plans and deliveries. */
final class ServiceFlowJson {

    private ServiceFlowJson() {
    }

    static String codeLabels(List<CodeLabel> items) {
        return array(items, item ->
                "{\"code\":\"" + escape(item.code())
                        + "\",\"label\":\"" + escape(item.label()) + "\"}");
    }

    static String issues(List<IssueSummary> issues) {
        return array(issues, issue ->
                "{\"groupCode\":\"" + escape(issue.groupCode())
                        + "\",\"groupName\":\"" + escape(issue.groupName())
                        + "\",\"count\":" + issue.count() + "}");
    }

    static String metrics(List<DeliveryMetric> metrics) {
        return array(metrics, metric ->
                "{\"code\":\"" + escape(metric.code())
                        + "\",\"label\":\"" + escape(metric.label())
                        + "\",\"value\":" + metric.value().toPlainString()
                        + ",\"unit\":\"" + escape(metric.unit()) + "\"}");
    }

    private static <T> String array(List<T> items, Function<T, String> element) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < items.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append(element.apply(items.get(index)));
        }
        return json.append(']').toString();
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
