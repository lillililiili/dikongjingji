package com.lowaltitude.inspection.ops;

import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class DataOriginController {
    enum Resource { PRODUCT, ACTIVITY, VENUE, COURSE, CONTENT, TOPIC, PRO_SERVICE, INSPECTION, LIVE }
    record Origin(UUID id, String origin, boolean hasRecordedChanges) {}

    private final OpsActors actors;
    private final JdbcTemplate database;

    public DataOriginController(OpsActors actors, JdbcTemplate database) {
        this.actors = actors;
        this.database = database;
    }

    @GetMapping("/api/v1/ops/data-origins/{resource}")
    List<Origin> read(Authentication authentication, @PathVariable Resource resource, @RequestParam List<UUID> ids) {
        actors.operator(authentication);
        if (ids.isEmpty() || ids.size() > 50 || ids.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("A page must contain 1 to 50 resource IDs");
        }
        return database.query("SELECT * FROM ops_resource_origins(?, ?)", ps -> {
            ps.setString(1, resource.name());
            ps.setArray(2, ps.getConnection().createArrayOf("uuid", ids.toArray()));
        }, (row, n) -> new Origin(row.getObject("resource_id", UUID.class), row.getString("origin"),
                row.getBoolean("has_recorded_changes")));
    }
}
