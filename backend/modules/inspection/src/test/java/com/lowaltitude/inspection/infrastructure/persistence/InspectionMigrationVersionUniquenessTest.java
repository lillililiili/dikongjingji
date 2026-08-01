package com.lowaltitude.inspection.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class InspectionMigrationVersionUniquenessTest {

    private static final Pattern VERSIONED_MIGRATION = Pattern.compile("^V(?<version>\\d+)__.+\\.sql$");

    @Test
    void assignsEachInspectionMigrationVersionOnlyOnce() throws Exception {
        var migrationDirectory = getClass().getResource("/db/migration/inspection");
        assertThat(migrationDirectory).isNotNull();

        Map<String, List<String>> migrationsByVersion;
        try (var files = Files.list(Path.of(migrationDirectory.toURI()))) {
            migrationsByVersion = files
                    .map(path -> path.getFileName().toString())
                    .filter(fileName -> VERSIONED_MIGRATION.matcher(fileName).matches())
                    .collect(Collectors.groupingBy(
                            fileName -> {
                                var matcher = VERSIONED_MIGRATION.matcher(fileName);
                                assertThat(matcher.matches()).isTrue();
                                return matcher.group("version");
                            },
                            LinkedHashMap::new,
                            Collectors.toList()));
        }

        Map<String, List<String>> duplicates = migrationsByVersion.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(duplicates).isEmpty();
    }
}
