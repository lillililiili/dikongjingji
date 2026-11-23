package com.lowaltitude.inspection.opsimport.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import com.lowaltitude.inspection.opsimport.application.ImportTemplates.FileTemplate;
import org.junit.jupiter.api.Test;

/** 导入模板的表头和仓库里的 infra/import-templates/*.csv 一字不差，31 张一张不少。 */
class ImportTemplatesTest {

    private static final Path TEMPLATES = Path.of("..", "..", "..", "infra", "import-templates");

    @Test
    void everyTemplateHeaderMatchesTheFileInTheRepository() throws Exception {
        List<FileTemplate> all = ImportTemplates.KINDS.values().stream().flatMap(kind -> kind.files().stream())
                .toList();
        try (Stream<Path> files = Files.list(TEMPLATES)) {
            assertThat(files.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".csv")))
                    .containsExactlyInAnyOrderElementsOf(all.stream().map(FileTemplate::fileName).toList());
        }
        for (FileTemplate template : all) {
            String header = Files.readString(TEMPLATES.resolve(template.fileName()), StandardCharsets.UTF_8).strip();
            assertThat(List.of(header.split(","))).as(template.fileName()).containsExactlyElementsOf(template.header());
        }
        assertThat(all).hasSize(31);
    }

    @Test
    void fileNamesMayCarryASequenceNumber() {
        var mall = ImportTemplates.KINDS.get(OpsImportKind.MALL);
        assertThat(ImportTemplates.templateFor(mall, "03-mall-products.csv")).contains(ImportTemplates.PRODUCTS);
        assertThat(ImportTemplates.templateFor(mall, "03-mall-products-2.csv")).contains(ImportTemplates.PRODUCTS);
        assertThat(ImportTemplates.templateFor(mall, "03E-MALL-OFFERS.CSV")).contains(ImportTemplates.OFFERS);
        assertThat(ImportTemplates.templateFor(mall, "03-mall-products.xlsx")).isEmpty();
        assertThat(ImportTemplates.templateFor(mall, "01-organizations.csv")).isEmpty();
    }

    @Test
    void rulesExplainWhatIsWrong() {
        assertThat(ImportTemplates.money().check("1990")).isNull();
        assertThat(ImportTemplates.money().check("19.90")).contains("整数分");
        assertThat(ImportTemplates.money().check("-1")).isNotNull();
        assertThat(ImportTemplates.dateTime().check("2026-12-06 09:00")).isNull();
        assertThat(ImportTemplates.dateTime().check("2026-02-30 09:00")).isNotNull();
        assertThat(ImportTemplates.https().check("https://cdn.example.com/a.jpg")).isNull();
        assertThat(ImportTemplates.https().check("http://cdn.example.com/a.jpg")).isNotNull();
        assertThat(ImportTemplates.textList(5, 12).check("图形化编程|避障练习")).isNull();
        assertThat(ImportTemplates.textList(5, 12).check("a|a")).contains("重复");
        assertThat(ImportTemplates.listOf(5, "MERCHANT", "ORGANIZER").check("MERCHANT|SHOP")).isNotNull();
        assertThat(ImportTemplates.LATITUDE.check("91")).isNotNull();
        assertThat(ImportTemplates.text(3).check("四个汉字")).contains("最多 3");
    }
}
