package com.iwrite.auth.migration;

import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.TestDatabaseInitializer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PostgreSQL's {@code lower()} is collation-dependent even for pure-ASCII input: on a column or
 * database using Turkish casing rules, {@code lower('I')} is {@code 'ı'} (U+0131, non-ASCII), not
 * {@code 'i'}. A legacy row like {@code "I@example.com"} passes the ASCII guard both V32 and V33
 * run before touching anything (its raw email is ASCII), but V32's old {@code lower(trim(email))}
 * would then commit a non-ASCII row — in its own, already-committed transaction — that V33's
 * {@code chk_users_email_ascii} rejects, wedging every subsequent startup on a failed migration
 * (#149 review, fresh finding). V32 and V33 now both lowercase ASCII via {@code translate()}, which
 * only ever maps the 26 ASCII letters and is therefore deterministic regardless of collation.
 *
 * <p>Each test alters the throwaway schema's {@code users.email} column to the ICU Turkish
 * collation ({@code "tr-TR-x-icu"}, part of PostgreSQL 16's bundled ICU locales) right after
 * migrating to V31 — before V32/V33 run — so the two migrations under test actually execute with
 * that collation active on the column they rewrite, the same way a real Turkish-locale deployment
 * would. Same throwaway-schema approach as {@link V32NormalizeUserEmailsMigrationIntegrationTest}
 * and {@link V33CanonicalizeUserEmailsMigrationIntegrationTest}.
 */
class V32V33LocaleIndependentAsciiLowercasingMigrationIntegrationTest extends PostgresIntegrationTest {

    private static final UUID TENANT = UUID.fromString("34000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("34000000-0000-0000-0000-000000000010");
    private static final UUID BOOK = UUID.fromString("34000000-0000-0000-0000-000000000100");

    @Autowired
    private DataSource dataSource;

    @Test
    void v32AndV33LowercaseAsciiIToLowercaseIExactlyNeverToDotlessI() throws Exception {
        String schema = "phase_c1_v32v33_tr_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);

        try {
            migrate(schema, MigrationVersion.fromVersion("31"));

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                seedLegacyUser(connection, schema, "I@example.com");
                useTurkishCollationForEmailColumn(connection, schema);
            }

            migrate(schema, null); // runs V32 then V33 with the Turkish collation active

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                String storedEmail = scalar(connection, schema, "select email from users where id = '" + USER + "'");
                assertEquals("i@example.com", storedEmail);
                assertThat(storedEmail).doesNotContain("ı"); // dotless ı — must never appear
            }
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void collisionGuardStillDetectsDuplicatesUnderTurkishCollation() throws Exception {
        String schema = "phase_c1_v32v33_tr_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);
        UUID collidingUserA = UUID.fromString("34000000-0000-0000-0000-000000000020");
        UUID collidingUserB = UUID.fromString("34000000-0000-0000-0000-000000000021");

        try {
            migrate(schema, MigrationVersion.fromVersion("31"));

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                insertMinimalUser(connection, schema, collidingUserA, "Info@Example.com");
                insertMinimalUser(connection, schema, collidingUserB, "INFO@Example.com");
                useTurkishCollationForEmailColumn(connection, schema);
            }

            assertThrows(FlywayException.class, () -> migrate(schema, null));

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                // Neither row was touched, and no constraint made it in — the collision aborted V32
                // before its own UPDATE ran.
                assertEquals("Info@Example.com", scalar(connection, schema, "select email from users where id = '" + collidingUserA + "'"));
                assertEquals("INFO@Example.com", scalar(connection, schema, "select email from users where id = '" + collidingUserB + "'"));
                assertEquals("0", scalar(connection, schema, "select count(*)::text from information_schema.table_constraints where table_schema = current_schema() and table_name = 'users' and constraint_name = 'chk_users_email_normalized'"));
            }
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void chkUsersEmailNormalizedStillRejectsMixedCaseUnderTurkishCollation() throws Exception {
        String schema = "phase_c1_v32v33_tr_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);

        try {
            migrate(schema, MigrationVersion.fromVersion("31"));

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                seedLegacyUser(connection, schema, "victim@example.com");
                useTurkishCollationForEmailColumn(connection, schema);
            }

            migrate(schema, null);

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                assertThrows(SQLException.class, () ->
                        executeUpdate(connection, schema, "update users set email = 'Mixed-Case@Example.com' where id = '" + USER + "'"));
            }
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void nonAsciiLegacyEmailStillAbortsWithoutAlterationUnderTurkishCollation() throws Exception {
        String schema = "phase_c1_v32v33_tr_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);
        String nonAsciiEmail = "usuária@iwrite.local";

        try {
            migrate(schema, MigrationVersion.fromVersion("31"));

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                seedLegacyUser(connection, schema, nonAsciiEmail);
                useTurkishCollationForEmailColumn(connection, schema);
            }

            assertThrows(FlywayException.class, () -> migrate(schema, null));

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                assertEquals(nonAsciiEmail, scalar(connection, schema, "select email from users where id = '" + USER + "'"));
                assertEquals("0", scalar(connection, schema, "select count(*)::text from information_schema.table_constraints where table_schema = current_schema() and table_name = 'users' and constraint_name = 'chk_users_email_normalized'"));
            }
        } finally {
            dropSchema(schema);
        }
    }

    /** Simulates a Turkish-locale deployment without needing a whole separate test database: ICU
     *  collations (bundled with PostgreSQL 16) can be attached to a single column. */
    private void useTurkishCollationForEmailColumn(Connection connection, String schema) throws SQLException {
        executeUpdate(connection, schema, "alter table users alter column email type varchar(255) collate \"tr-TR-x-icu\"");
    }

    private void seedLegacyUser(Connection connection, String schema, String rawEmail) throws SQLException {
        executeUpdate(connection, schema, "insert into tenants (id, name, default_time_zone_id, created_at, updated_at) values ('" + TENANT + "', 'Legacy Tenant', 'UTC', current_timestamp, current_timestamp)");
        executeUpdate(connection, schema, "insert into users (id, display_name, email, time_zone_id, created_at, updated_at) values ('" + USER + "', 'Victim', '" + rawEmail.replace("'", "''") + "', 'UTC', current_timestamp, current_timestamp)");
        executeUpdate(connection, schema, "insert into user_credentials (user_id, password_hash, created_at, updated_at) values ('" + USER + "', 'stored-hash-unchanged', current_timestamp, current_timestamp)");
        executeUpdate(connection, schema, "insert into tenant_memberships (id, tenant_id, user_id, role, joined_at) values ('" + UUID.randomUUID() + "', '" + TENANT + "', '" + USER + "', 'OWNER', current_timestamp)");
        executeUpdate(connection, schema, "insert into books (id, tenant_id, title, status, owner_user_id, created_at, updated_at) values ('" + BOOK + "', '" + TENANT + "', 'Legacy Book', 'PLANNING', '" + USER + "', current_timestamp, current_timestamp)");
    }

    private void insertMinimalUser(Connection connection, String schema, UUID userId, String rawEmail) throws SQLException {
        executeUpdate(connection, schema, "insert into users (id, display_name, email, time_zone_id, created_at, updated_at) values ('" + userId + "', 'Someone', '" + rawEmail.replace("'", "''") + "', 'UTC', current_timestamp, current_timestamp)");
    }

    private void migrate(String schema, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private String scalar(Connection connection, String schema, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema);
            try (var resultSet = statement.executeQuery(sql)) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private void executeUpdate(Connection connection, String schema, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema);
            statement.executeUpdate(sql);
        }
    }

    private void createSchema(String schema) throws SQLException {
        try (Connection connection = TestDatabaseInitializer.openDirectConnection(); var statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
        }
    }

    private void dropSchema(String schema) throws SQLException {
        try (Connection connection = TestDatabaseInitializer.openDirectConnection(); var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + schema + " cascade");
        }
    }
}
