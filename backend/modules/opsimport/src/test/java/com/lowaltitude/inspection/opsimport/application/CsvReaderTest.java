package com.lowaltitude.inspection.opsimport.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

class CsvReaderTest {

    @Test
    void readsQuotedCellsCommasNewlinesAndTheExcelBom() {
        byte[] content = ("﻿a,b,c\r\n1,\"x, y\",\"第一行\n第二行\"\r\n\r\n2,\"他说\"\"好\"\"\",\n")
                .getBytes(StandardCharsets.UTF_8);

        List<CsvReader.Line> lines = CsvReader.read(content);

        assertThat(lines.get(0).cells()).containsExactly("a", "b", "c");
        assertThat(lines.get(1).number()).isEqualTo(2);
        assertThat(lines.get(1).cells()).containsExactly("1", "x, y", "第一行\n第二行");
        assertThat(lines.get(2).blank()).isTrue();
        // 跨行的格之后，行号接着数。
        assertThat(lines.get(3).number()).isEqualTo(5);
        assertThat(lines.get(3).cells()).containsExactly("2", "他说\"好\"", "");
    }

    @Test
    void rejectsBrokenQuotesAndFilesThatAreNotUtf8() {
        assertThatThrownBy(() -> CsvReader.read("a,\"b\nc".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("没有闭合");
        assertThatThrownBy(() -> CsvReader.read("a,b\"c\"".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("引号");
        assertThatThrownBy(() -> CsvReader.read("名称".getBytes(java.nio.charset.Charset.forName("GBK"))))
                .hasMessageContaining("UTF-8");
    }
}
