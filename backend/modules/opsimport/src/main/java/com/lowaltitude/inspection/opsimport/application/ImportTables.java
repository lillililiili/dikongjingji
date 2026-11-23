package com.lowaltitude.inspection.opsimport.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.lowaltitude.inspection.opsimport.application.ImportTemplates.FileTemplate;

/** 一批文件按模板读出来的行（同一张模板的几个文件按上传顺序接在一起，每行记着自己的文件名和行号）。 */
final class ImportTables {

    private final Map<FileTemplate, List<Row>> rows = new LinkedHashMap<>();

    void add(FileTemplate template, Row row) {
        rows.computeIfAbsent(template, ignored -> new ArrayList<>()).add(row);
    }

    List<Row> rows(FileTemplate template) {
        return rows.getOrDefault(template, List.of());
    }

    boolean has(FileTemplate template) {
        return rows.containsKey(template);
    }

    /** 带了这张表（哪怕只有表头）：用来区分“没带这张表、原有的不动”和“带了空表、清空”。 */
    void declare(FileTemplate template) {
        rows.computeIfAbsent(template, ignored -> new ArrayList<>());
    }

    /** 一行：值都去掉了首尾空白，空格是 ""。 */
    record Row(String fileName, int line, Map<String, String> values) {

        String get(String column) {
            return values.getOrDefault(column, "");
        }

        boolean has(String column) {
            return !get(column).isEmpty();
        }

        Integer integer(String column) {
            return has(column) ? Integer.valueOf(get(column)) : null;
        }

        Long amount(String column) {
            return has(column) ? Long.valueOf(get(column)) : null;
        }

        boolean bool(String column) {
            return "true".equals(get(column));
        }

        int sortOrder(String column) {
            return has(column) ? Integer.parseInt(get(column)) : 0;
        }
    }
}
