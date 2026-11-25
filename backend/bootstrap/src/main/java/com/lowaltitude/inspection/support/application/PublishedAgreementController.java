package com.lowaltitude.inspection.support.application;

import com.lowaltitude.inspection.bootstrap.api.ApiError;
import com.lowaltitude.inspection.support.infrastructure.persistence.AgreementMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class PublishedAgreementController {
    private final AgreementMapper agreements;

    public PublishedAgreementController(AgreementMapper agreements) {
        this.agreements = agreements;
    }

    @GetMapping("/api/v1/platform/agreements/{doc}")
    public ResponseEntity<?> published(@PathVariable String doc) {
        var rows = agreements.published(doc);
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).body(new ApiError("COMMON_NOT_FOUND", "协议尚未发布", UUID.randomUUID().toString()));
        }
        var document = rows.get(0);
        var sections = rows.stream().map(row -> new Section(row.sectionTitle(), row.body())).toList();
        return ResponseEntity.ok(new Document(document.title(), document.version(), document.publishedOn(), sections));
    }

    public record Document(String title, String version, LocalDate publishedOn, List<Section> sections) {}
    public record Section(String title, String body) {}
}
