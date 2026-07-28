package com.lowaltitude.inspection.bootstrap.infrastructure;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;

public final class ModuleFlywayFactory {

    public Flyway identity(DataSource dataSource) {
        return build(
                dataSource,
                "classpath:db/migration/identity",
                "flyway_history_identity",
                false);
    }

    public Flyway inspection(DataSource dataSource) {
        return build(
                dataSource,
                "classpath:db/migration/inspection",
                "flyway_history_inspection",
                true);
    }

    private static Flyway build(DataSource dataSource, String location, String historyTable, boolean baselineOnMigrate) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(location)
                .table(historyTable)
                .baselineOnMigrate(baselineOnMigrate)
                .baselineVersion("0")
                .load();
    }
}
