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

    public Flyway activity(DataSource dataSource) {
        return build(
                dataSource,
                "classpath:db/migration/activity",
                "flyway_history_activity",
                true);
    }

    public Flyway experience(DataSource dataSource) {
        return build(
                dataSource,
                "classpath:db/migration/experience",
                "flyway_history_experience",
                true);
    }

    public Flyway notification(DataSource dataSource) {
        return build(
                dataSource,
                "classpath:db/migration/notification",
                "flyway_history_notification",
                true);
    }

    public Flyway account(DataSource dataSource) {
        return build(
                dataSource,
                "classpath:db/migration/account",
                "flyway_history_account",
                true);
    }

    public Flyway trade(DataSource dataSource) {
        return build(
                dataSource,
                "classpath:db/migration/trade",
                "flyway_history_trade",
                true);
    }

    public Flyway mall(DataSource dataSource) {
        return build(
                dataSource,
                "classpath:db/migration/mall",
                "flyway_history_mall",
                true);
    }

    public Flyway demand(DataSource dataSource) {
        return build(
                dataSource,
                "classpath:db/migration/demand",
                "flyway_history_demand",
                true);
    }

    public Flyway proservice(DataSource dataSource) {
        return build(
                dataSource,
                "classpath:db/migration/proservice",
                "flyway_history_proservice",
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
