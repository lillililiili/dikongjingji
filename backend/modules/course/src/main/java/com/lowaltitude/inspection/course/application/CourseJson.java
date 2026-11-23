package com.lowaltitude.inspection.course.application;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * course 表里几个 jsonb 数组列的读写：图集（字符串）、图文介绍（字符串）、课程包含（name + quantity）、参数补充行（label + value）。
 * 读到坏数据时按空处理并跳过坏的项，页面不因为一行坏数据打不开。
 */
public final class CourseJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CourseJson() {
    }

    public record Include(String name, int quantity) {
    }

    public record Spec(String label, String value) {
    }

    public static List<String> strings(String json) {
        return items(json, node -> node.isTextual() && !node.asText().isBlank() ? node.asText() : null);
    }

    public static List<Include> includes(String json) {
        return items(json, node -> node.hasNonNull("name") && node.path("quantity").canConvertToInt()
                && node.path("quantity").asInt() > 0
                ? new Include(node.get("name").asText(), node.get("quantity").asInt())
                : null);
    }

    public static List<Spec> specs(String json) {
        return items(json, node -> node.hasNonNull("label") && node.hasNonNull("value")
                ? new Spec(node.get("label").asText(), node.get("value").asText())
                : null);
    }

    public static String writeStrings(List<String> values) {
        ArrayNode array = MAPPER.createArrayNode();
        values.forEach(array::add);
        return write(array);
    }

    public static String writeIncludes(List<Include> values) {
        ArrayNode array = MAPPER.createArrayNode();
        values.forEach(value -> array.addObject().put("name", value.name()).put("quantity", value.quantity()));
        return write(array);
    }

    public static String writeSpecs(List<Spec> values) {
        ArrayNode array = MAPPER.createArrayNode();
        values.forEach(value -> array.addObject().put("label", value.label()).put("value", value.value()));
        return write(array);
    }

    private static <T> List<T> items(String json, Function<JsonNode, T> reader) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException broken) {
            return List.of();
        }
        if (!root.isArray()) {
            return List.of();
        }
        List<T> out = new ArrayList<>();
        for (JsonNode node : root) {
            T item = reader.apply(node);
            if (item != null) {
                out.add(item);
            }
        }
        return List.copyOf(out);
    }

    private static String write(ArrayNode array) {
        try {
            return MAPPER.writeValueAsString(array);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
