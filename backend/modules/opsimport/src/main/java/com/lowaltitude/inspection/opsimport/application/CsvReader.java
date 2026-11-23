package com.lowaltitude.inspection.opsimport.application;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 读模板 CSV（RFC 4180：逗号分隔，双引号包住的格里可以有逗号、换行，两个双引号表示一个）。必须是 UTF-8，可以带 BOM
 * （Excel、WPS 另存为“CSV UTF-8”时会带）。不认识的编码、引号没闭合时抛 {@link CsvException}，由预检记成整个文件的问题。
 */
final class CsvReader {

    private CsvReader() {
    }

    /** 读出全部行（包括表头）；每行是一组格，行号从 1 开始，跨行的格按它开始的那一行算。 */
    static List<Line> read(byte[] content) {
        String text = decode(content);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        List<Line> lines = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean cellWasQuoted = false;
        int lineNumber = 1;
        int lineStart = 1;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i += 2;
                        continue;
                    }
                    quoted = false;
                } else {
                    if (c == '\n') {
                        lineNumber++;
                    }
                    if (c != '\r') {
                        cell.append(c);
                    }
                }
                i++;
                continue;
            }
            switch (c) {
                case '"' -> {
                    if (cell.isEmpty() && !cellWasQuoted) {
                        quoted = true;
                        cellWasQuoted = true;
                    } else {
                        throw new CsvException("第 " + lineNumber + " 行的引号位置不对");
                    }
                }
                case ',' -> {
                    cells.add(cell.toString());
                    cell.setLength(0);
                    cellWasQuoted = false;
                }
                case '\r' -> {
                    // \r\n 当作一个换行；单独的 \r 忽略。
                }
                case '\n' -> {
                    cells.add(cell.toString());
                    lines.add(new Line(lineStart, List.copyOf(cells)));
                    cells.clear();
                    cell.setLength(0);
                    cellWasQuoted = false;
                    lineNumber++;
                    lineStart = lineNumber;
                }
                default -> {
                    if (cellWasQuoted) {
                        throw new CsvException("第 " + lineNumber + " 行引号后面还有字");
                    }
                    cell.append(c);
                }
            }
            i++;
        }
        if (quoted) {
            throw new CsvException("第 " + lineStart + " 行开始的引号没有闭合");
        }
        if (!cell.isEmpty() || !cells.isEmpty() || cellWasQuoted) {
            cells.add(cell.toString());
            lines.add(new Line(lineStart, List.copyOf(cells)));
        }
        return lines;
    }

    private static String decode(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException notUtf8) {
            throw new CsvException("不是 UTF-8 编码（用 Excel 或 WPS 另存为“CSV UTF-8”）");
        }
    }

    /** 一行：行号和各格（原样，没去空白）。 */
    record Line(int number, List<String> cells) {

        boolean blank() {
            return cells.stream().allMatch(value -> value.strip().isEmpty());
        }
    }

    static final class CsvException extends RuntimeException {

        CsvException(String message) {
            super(message);
        }
    }
}
