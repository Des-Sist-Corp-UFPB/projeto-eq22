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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * V32 normalizes legacy {@code users.email} rows (trim + lowercase) written before
 * {@code EmailNormalizer} existed, then adds a check constraint closing the gap for good (#143
 * review). Each test runs against its own throwaway schema, migrated up to V31 first and seeded
 * with raw SQL exactly as a pre-migration installation would look, so V32 runs against real
 * pre-existing state rather than data the application itself already normalized on the way in.
 */
class V32NormalizeUserEmailsMigrationIntegrationTest extends PostgresIntegrationTest {

    private static final UUID TENANT = UUID.fromString("32000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("32000000-0000-0000-0000-000000000010");
    private static final UUID BOOK = UUID.fromString("32000000-0000-0000-0000-000000000100");

    @Autowired
    private DataSource dataSource;

    @Test
    void v32NormalizesMixedCaseEmailAndPreservesOwnershipAndCredential() throws Exception {
        String schema = "phase_c1_v32_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);

        try {
            migrate(schema, MigrationVersion.fromVersion("31"));

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                seedLegacyMixedCaseUser(connection, schema, "  Victim@IWrite.LOCAL  ");
            }

            migrate(schema, null);

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                assertEquals("victim@iwrite.local", scalar(connection, schema, "select email from users where id = '" + USER + "'"));
                // Login resolves by exact match on the normalized email (AuthSessionService /
                // EmailNormalizer): once the stored value equals what any capitalization of the
                // same address normalizes to, a login with arbitrary casing finds this same row.
                assertEquals("1", scalar(connection, schema, "select count(*)::text from users where id = '" + USER + "' and email = lower(trim(' vICTIM@iwrite.LOCAL '))"));

                // Identity, credential, membership, tenant and book ownership are all untouched.
                assertEquals("stored-hash-unchanged", scalar(connection, schema, "select password_hash from user_credentials where user_id = '" + USER + "'"));
                assertEquals("1", scalar(connection, schema, "select count(*)::text from tenant_memberships where tenant_id = '" + TENANT + "' and user_id = '" + USER + "'"));
                assertEquals(USER.toString(), scalar(connection, schema, "select owner_user_id::text from books where id = '" + BOOK + "'"));

                // The new constraint rejects any future non-normalized write, not just backfills it.
                assertThrows(SQLException.class, () -> executeUpdate(connection, schema, "update users set email = 'Not-Normalized@IWrite.local' where id = '" + USER + "'"));
            }
        } finally {
            dropSchema(schema);
        }
    }

    // #149 review: a legacy row with a non-ASCII email must never be silently rewritten by V32's own
    // lower() — Java's toLowerCase(Locale.ROOT) and PostgreSQL's lower() are not guaranteed to
    // canonicalize every Unicode code point the same way (see EmailNormalizer's class doc). The
    // migration must abort before touching anything, the same way the collision guard does.
    @Test
    void v32AbortsWithoutAlteringAnythingWhenALegacyEmailIsNonAscii() throws Exception {
        String schema = "phase_c1_v32_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);
        String nonAsciiEmail = "usuária@iwrite.local";

        try {
            migrate(schema, MigrationVersion.fromVersion("31"));

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                seedLegacyMixedCaseUser(connection, schema, nonAsciiEmail);
            }

            assertThrows(FlywayException.class, () -> migrate(schema, null));

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                // Untouched: still exactly the raw, non-ASCII value it was seeded with.
                assertEquals(nonAsciiEmail, scalar(connection, schema, "select email from users where id = '" + USER + "'"));
                assertEquals("0", scalar(connection, schema, "select count(*)::text from information_schema.table_constraints where table_schema = current_schema() and table_name = 'users' and constraint_name = 'chk_users_email_normalized'"));
            }
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void v32AbortsWithoutAlteringAnythingWhenTwoRowsCollideOnceNormalized() throws Exception {
        String schema = "phase_c1_v32_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);
        UUID collidingUserA = UUID.fromString("32000000-0000-0000-0000-000000000020");
        UUID collidingUserB = UUID.fromString("32000000-0000-0000-0000-000000000021");

        try {
            migrate(schema, MigrationVersion.fromVersion("31"));

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                seedLegacyMixedCaseUser(connection, schema, "  Victim@IWrite.LOCAL  ");
                insertMinimalUser(connection, schema, collidingUserA, "clash@iwrite.local");
                insertMinimalUser(connection, schema, collidingUserB, "  CLASH@IWrite.local");
            }

            assertThrows(FlywayException.class, () -> migrate(schema, null));

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                // The migration never got past its collision check: the pre-existing mixed-case row
                // is exactly as it was seeded, and no check constraint was added.
                assertEquals("  Victim@IWrite.LOCAL  ", scalar(connection, schema, "select email from users where id = '" + USER + "'"));
                assertEquals("0", scalar(connection, schema, "select count(*)::text from information_schema.table_constraints where table_schema = current_schema() and table_name = 'users' and constraint_name = 'chk_users_email_normalized'"));
            }
        } finally {
            dropSchema(schema);
        }
    }

    private void seedLegacyMixedCaseUser(Connection connection, String schema, String rawEmail) throws SQLException {
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
