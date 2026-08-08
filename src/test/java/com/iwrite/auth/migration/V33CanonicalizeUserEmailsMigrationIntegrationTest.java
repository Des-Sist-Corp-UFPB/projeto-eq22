package com.iwrite.auth.migration;

import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.TestDatabaseInitializer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * V33 replaces V32's {@code chk_users_email_normalized} with one built on a canonical form that is
 * actually equivalent to {@code EmailNormalizer.normalize()} (Java {@code String.trim()} +
 * lowercase). V32's own constraint was built on PostgreSQL's {@code trim()}, which only strips plain
 * spaces (0x20) — not the wider range Java's {@code trim()} strips (any code point <= 0x20: tabs, CR,
 * LF, other C0 controls). A row padded with one of those could satisfy V32's constraint while still
 * not matching what the application computes for the same address.
 *
 * <p>Each test runs against its own throwaway schema, migrated up to V31 first (before either email
 * constraint exists) and seeded with raw SQL exactly as a pre-{@code EmailNormalizer} installation
 * would look, so the full V31→latest path runs against real pre-existing state rather than data the
 * application itself already normalized on the way in — same approach as
 * {@link V32NormalizeUserEmailsMigrationIntegrationTest}.
 */
class V33CanonicalizeUserEmailsMigrationIntegrationTest extends PostgresIntegrationTest {

    private static final UUID TENANT = UUID.fromString("33000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("33000000-0000-0000-0000-000000000010");
    private static final UUID BOOK = UUID.fromString("33000000-0000-0000-0000-000000000100");

    @Autowired
    private DataSource dataSource;

    static Stream<Arguments> controlCharacterPaddedEmails() {
        return Stream.of(
                Arguments.of("TAB", "\tvictim@iwrite.local\t"),
                Arguments.of("LF", "\nvictim@iwrite.local\n"),
                Arguments.of("CRLF", "\r\nvictim@iwrite.local\r\n"),
                Arguments.of("controles e espaços combinados", " \t victim@iwrite.local \r\n ")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("controlCharacterPaddedEmails")
    void v33NormalizesControlCharacterPaddedEmailAndPreservesOwnershipAndCredential(String label, String rawEmail) throws Exception {
        String schema = "phase_c1_v33_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);

        try {
            migrate(schema, MigrationVersion.fromVersion("31")); // before either email constraint exists

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                seedLegacyUser(connection, schema, rawEmail);
            }

            migrate(schema, null); // runs V32 then V33

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                assertEquals("victim@iwrite.local", scalar(connection, schema, "select email from users where id = '" + USER + "'"));
                // Login resolves by exact match on the stored, now-canonical email (AuthSessionService /
                // EmailNormalizer) — a lookup with the plain clean address must find this exact row.
                assertEquals("1", scalar(connection, schema, "select count(*)::text from users where email = 'victim@iwrite.local'"));

                // Identity, credential, membership, tenant and book ownership are all untouched.
                assertEquals("stored-hash-unchanged", scalar(connection, schema, "select password_hash from user_credentials where user_id = '" + USER + "'"));
                assertEquals("1", scalar(connection, schema, "select count(*)::text from tenant_memberships where tenant_id = '" + TENANT + "' and user_id = '" + USER + "'"));
                assertEquals(USER.toString(), scalar(connection, schema, "select owner_user_id::text from books where id = '" + BOOK + "'"));

                // The new constraint rejects any future non-canonical write, not just backfills it —
                // including one padded with the same kind of control character just normalized away.
                assertThrows(SQLException.class, () ->
                        executeUpdate(connection, schema, "update users set email = '\tNot-Normalized@IWrite.local\t' where id = '" + USER + "'"));
            }
        } finally {
            dropSchema(schema);
        }
    }

    // #149 review: chk_users_email_ascii (added by this migration) must reject a brand-new
    // non-ASCII users.email write, not just the legacy-row case V32 now aborts on ahead of time.
    @Test
    void v33sChkUsersEmailAsciiRejectsANewNonAsciiEmailWrite() throws Exception {
        String schema = "phase_c1_v33_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);
        UUID plainUser = UUID.fromString("33000000-0000-0000-0000-000000000030");

        try {
            migrate(schema, MigrationVersion.fromVersion("31"));

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                insertMinimalUser(connection, schema, plainUser, "plain@example.com");
            }

            migrate(schema, null);

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                assertThrows(SQLException.class, () ->
                        executeUpdate(connection, schema, "update users set email = 'usuária@iwrite.local' where id = '" + plainUser + "'"));
            }
        } finally {
            dropSchema(schema);
        }
    }

    /**
     * The exact scenario V32's own constraint could not see: "user@example.com" and
     * "\tUSER@example.com\r\n" do not collide under V32's plain-space-only rule (the second row
     * would have satisfied {@code chk_users_email_normalized} as V32 defined it), so V32 itself would
     * apply cleanly against this pair. Only once V33's wider canonical form is computed does the
     * collision surface — and it must abort the whole migration, not silently keep one row.
     */
    @Test
    void v33AbortsWithoutAlteringAnythingWhenTwoRowsCollideOnceCanonicalized() throws Exception {
        String schema = "phase_c1_v33_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);
        UUID plainUser = UUID.fromString("33000000-0000-0000-0000-000000000020");
        UUID paddedUser = UUID.fromString("33000000-0000-0000-0000-000000000021");

        try {
            migrate(schema, MigrationVersion.fromVersion("31"));

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                insertMinimalUser(connection, schema, plainUser, "user@example.com");
                insertMinimalUser(connection, schema, paddedUser, "\tUSER@example.com\r\n");
            }

            assertThrows(FlywayException.class, () -> migrate(schema, null));

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                // V33 itself touched nothing — its own DDL (dropping V32's constraint) rolled back
                // along with everything else in its transaction once the collision check raised. But
                // V32 ran to completion first, as a separate, already-committed transaction: it
                // lowercases on the way in (its own UPDATE fires whenever case OR padding differs from
                // its narrower canonical form), just without ever stripping the tab/CR/LF — exactly the
                // partially-normalized, constraint-satisfying state V33 exists to close.
                assertEquals("user@example.com", scalar(connection, schema, "select email from users where id = '" + plainUser + "'"));
                assertEquals("\tuser@example.com\r\n", scalar(connection, schema, "select email from users where id = '" + paddedUser + "'"));
                assertEquals("1", scalar(connection, schema, "select count(*)::text from information_schema.table_constraints where table_schema = current_schema() and table_name = 'users' and constraint_name = 'chk_users_email_normalized'"));
            }
        } finally {
            dropSchema(schema);
        }
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
