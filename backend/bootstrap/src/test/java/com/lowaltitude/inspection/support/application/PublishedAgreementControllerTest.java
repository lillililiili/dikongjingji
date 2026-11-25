package com.lowaltitude.inspection.support.application;

import com.lowaltitude.inspection.support.infrastructure.persistence.AgreementMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PublishedAgreementControllerTest {
    @Test void returnsNotFoundWhenNoBodyHasBeenPublished() {
        var controller = new PublishedAgreementController(doc -> List.of());
        assertThat(controller.published("PRIVACY_POLICY").getStatusCode().value()).isEqualTo(404);
    }

    @Test void servesVersionDateAndPlainTextReadFromStoreInSectionOrder() {
        LocalDate date = LocalDate.of(2026, 11, 20);
        var controller = new PublishedAgreementController(doc -> List.of(
                new AgreementMapper.SectionRow("测试文档", "v2", date, "第一章", "第一段\n第二段"),
                new AgreementMapper.SectionRow("测试文档", "v2", date, "第二章", "末段")));
        var response = controller.published("USER_AGREEMENT");
        assertThat(response.getBody()).isEqualTo(new PublishedAgreementController.Document("测试文档", "v2", date,
                List.of(new PublishedAgreementController.Section("第一章", "第一段\n第二段"),
                        new PublishedAgreementController.Section("第二章", "末段"))));
    }
}
