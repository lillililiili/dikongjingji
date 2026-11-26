package com.lowaltitude.inspection.bootstrap.infrastructure;

import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** The two published identity histories converge after V016. Preserve the history first applied to each database. */
final class IdentityMigrationHistory {
    record Entry(String version, String script, String description, int checksum) {
        String canonicalScript() {
            int versionInMain = version.equals("016") ? 13 : Integer.parseInt(version) + 1;
            return "V%03d".formatted(versionInMain) + script.substring(4);
        }
    }

    static final List<Entry> LEGACY = List.of(
            new Entry("013", "V013__organization_signup_replay.sql", "organization signup replay", -1964954361),
            new Entry("014", "V014__personal_password.sql", "personal password", 271246336),
            new Entry("015", "V015__personal_password_recovery.sql", "personal password recovery", -753115496),
            new Entry("016", "V016__identity_listing_suspended.sql", "identity listing suspended", 410518088));

    static Flyway forDatabase(Flyway canonical) {
        DataSource dataSource = canonical.getConfiguration().getDataSource();
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        boolean legacy = Boolean.TRUE.equals(tx.execute(ignored -> usesLegacyHistory(new JdbcTemplate(dataSource))));
        if (!legacy) return canonical;
        return Flyway.configure().configuration(canonical.getConfiguration())
                .resourceProvider(new LegacyIdentityResources()).load();
    }

    private static boolean usesLegacyHistory(JdbcTemplate db) {
        if (!Boolean.TRUE.equals(db.queryForObject("SELECT to_regclass('flyway_history_identity') IS NOT NULL", Boolean.class))) {
            return false;
        }
        List<Entry> rows = db.query("""
                SELECT version, script, description, checksum, type, success FROM flyway_history_identity
                WHERE version ~ '^0*(13|14|15|16)$' ORDER BY installed_rank
                """, (rs, i) -> new Entry(rs.getString("version"), rs.getString("script"),
                rs.getString("description"), rs.getBoolean("success") && "SQL".equals(rs.getString("type"))
                ? rs.getInt("checksum") : 0));
        if (rows.isEmpty() || !rows.getFirst().script().equals(LEGACY.getFirst().script())) return false;
        if (rows.size() > LEGACY.size() || !rows.equals(LEGACY.subList(0, rows.size()))) {
            throw new IllegalStateException("Unrecognized V013–V016 identity migration history");
        }
        boolean hasLaterMigration = Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM flyway_history_identity WHERE success
                    AND CASE WHEN version ~ '^[0-9]+([.][0-9]+)*$'
                             THEN split_part(version, '.', 1)::numeric END >= 17)
                """, Boolean.class));
        if (!hasLaterMigration) {
            verifySchema(db, rows.size());
        } else if (rows.size() != LEGACY.size()) {
            throw new IllegalStateException("Incomplete V013–V016 identity migration history before V017");
        }
        // Later published migrations can legitimately replace a column/index from V016.
        // Their resources and every historical checksum are validated by Flyway.
        return true;
    }

    private static void verifySchema(JdbcTemplate db, int count) {
        boolean passwords = count >= 2;
        boolean recovery = count >= 3;
        boolean listing = count >= 4;
        checkColumn(db, "identity_organization", "signup_request_signature", "character varying(64)", true);
        checkColumn(db, "identity_login_session", "password_setup_until", "timestamp with time zone", passwords);
        checkColumn(db, "identity_sms_code", "setup_session_id", "uuid", passwords);
        checkColumn(db, "identity_organization", "listing_suspended", "boolean", listing);
        checkRelation(db, "identity_password_guard", passwords);
        checkRelation(db, "idx_identity_sms_setup_session", recovery);
        checkRelation(db, "idx_identity_organization_listing_suspended", listing);
        String purpose = db.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conrelid = 'identity_sms_code'::regclass AND conname = 'identity_sms_code_purpose_check'
                """, String.class);
        if (purpose == null || purpose.contains("'PASSWORD_SETUP'") != passwords
                || purpose.contains("'PASSWORD_RESET'") != recovery) {
            throw new IllegalStateException("Unexpected identity_sms_code purpose constraint for migration history");
        }
        if (passwords) {
            String deleteAction = db.queryForObject("""
                    SELECT confdeltype::text FROM pg_constraint WHERE conrelid = 'identity_sms_code'::regclass
                    AND conname = 'identity_sms_code_setup_session_id_fkey' AND confrelid = 'identity_login_session'::regclass
                    """, String.class);
            if (!(recovery ? "n" : "a").equals(deleteAction)) {
                throw new IllegalStateException("Unexpected identity_sms_code session foreign key for migration history");
            }
        }
    }

    private static void checkColumn(JdbcTemplate db, String table, String column, String type, boolean expected) {
        List<String> types = db.queryForList("""
                SELECT format_type(atttypid, atttypmod) FROM pg_attribute
                WHERE attrelid = to_regclass(?) AND attname = ? AND NOT attisdropped
                """, String.class, table, column);
        if (!types.equals(expected ? List.of(type) : List.of())) {
            throw new IllegalStateException("Unexpected " + table + "." + column + " for identity migration history");
        }
    }

    private static void checkRelation(JdbcTemplate db, String name, boolean expected) {
        if (!Boolean.valueOf(expected).equals(db.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, name))) {
            throw new IllegalStateException("Unexpected " + name + " for identity migration history");
        }
    }
}
