package com.iwrite.book.migration;

import com.iwrite.support.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class V25BookCollaborationMigrationIntegrationTest extends PostgresIntegrationTest {

    private static final UUID TENANT_A = UUID.fromString("25000000-0000-0000-0000-000000000001");
    private static final UUID USER_A_LATE = UUID.fromString("25000000-0000-0000-0000-000000000020");
    private static final UUID USER_A_EARLY = UUID.fromString("25000000-0000-0000-0000-000000000010");
    private static final UUID USER_A_TIE_LOW = UUID.fromString("25000000-0000-0000-0000-000000000030");
    private static final UUID USER_A_TIE_HIGH = UUID.fromString("25000000-0000-0000-0000-000000000040");
    private static final UUID BOOK_A = UUID.fromString("25000000-0000-0000-0000-000000000101");
    private static final UUID TENANT_B = UUID.fromString("25000000-0000-0000-0000-000000000002");
    private static final UUID USER_B_LOW = UUID.fromString("25000000-0000-0000-0000-000000000050");
    private static final UUID USER_B_HIGH = UUID.fromString("25000000-0000-0000-0000-000000000060");
    private static final UUID BOOK_B = UUID.fromString("25000000-0000-0000-0000-000000000102");

    @Autowired
    private DataSource dataSource;

    @Test
    void v25BackfillsOwnersAndEnforcesBookCollaboratorIntegrityFromV24() throws Exception {
        String schema = "phase_c1_v25_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);

        try {
            migrate(schema, MigrationVersion.fromVersion("24"));

            try (Connection connection = dataSource.getConnection()) {
                seedLegacyData(connection, schema);
                assertEquals("0", scalar(connection, schema, "select count(*)::text from information_schema.columns where table_schema = current_schema() and table_name = 'books' and column_name = 'owner_user_id'"));
            }

            migrate(schema, null);

            try (Connection connection = dataSource.getConnection()) {
                assertEquals(USER_A_EARLY.toString(), scalar(connection, schema, "select owner_user_id::text from books where id = '" + BOOK_A + "'"));
                assertEquals(USER_B_LOW.toString(), scalar(connection, schema, "select owner_user_id::text from books where id = '" + BOOK_B + "'"));
                assertEquals("0", scalar(connection, schema, "select count(*)::text from books where owner_user_id is null"));
                assertEquals("Legacy A,PLANNING", scalar(connection, schema, "select title || ',' || status from books where id = '" + BOOK_A + "'"));

                assertSqlFails(connection, schema, "update books set owner_user_id = '" + USER_B_LOW + "' where id = '" + BOOK_A + "'");
                assertSqlFails(connection, schema, "delete from tenant_memberships where tenant_id = '" + TENANT_A + "' and user_id = '" + USER_A_EARLY + "'");
                assertSqlFails(connection, schema, collaboratorInsert(UUID.randomUUID(), TENANT_A, BOOK_B, USER_A_LATE, USER_A_EARLY));
                assertSqlFails(connection, schema, collaboratorInsert(UUID.randomUUID(), TENANT_A, BOOK_A, USER_B_LOW, USER_A_EARLY));

                UUID collaboratorId = UUID.fromString("25000000-0000-0000-0000-000000000201");
                executeUpdate(connection, schema, collaboratorInsert(collaboratorId, TENANT_A, BOOK_A, USER_A_LATE, USER_A_EARLY));
                assertSqlFails(connection, schema, collaboratorInsert(UUID.randomUUID(), TENANT_A, BOOK_A, USER_A_LATE, USER_A_EARLY));

                executeUpdate(connection, schema, "delete from tenant_memberships where tenant_id = '" + TENANT_A + "' and user_id = '" + USER_A_LATE + "'");
                assertEquals("0", scalar(connection, schema, "select count(*)::text from book_collaborators where id = '" + collaboratorId + "'"));
            }
        } finally {
            dropSchema(schema);
        }
    }

    private void seedLegacyData(Connection connection, String schema) throws SQLException {
        executeUpdate(connection, schema, "insert into tenants (id, name, default_time_zone_id, created_at, updated_at) values ('" + TENANT_A + "', 'Tenant A', 'UTC', current_timestamp, current_timestamp)");
        executeUpdate(connection, schema, "insert into tenants (id, name, default_time_zone_id, created_at, updated_at) values ('" + TENANT_B + "', 'Tenant B', 'UTC', current_timestamp, current_timestamp)");
        insertUser(connection, schema, USER_A_LATE, "late-a@iwrite.local");
        insertUser(connection, schema, USER_A_EARLY, "early-a@iwrite.local");
        insertUser(connection, schema, USER_A_TIE_LOW, "tie-low-a@iwrite.local");
        insertUser(connection, schema, USER_A_TIE_HIGH, "tie-high-a@iwrite.local");
        insertUser(connection, schema, USER_B_LOW, "low-b@iwrite.local");
        insertUser(connection, schema, USER_B_HIGH, "high-b@iwrite.local");
        insertMembership(connection, schema, TENANT_A, USER_A_LATE, "2026-01-03 00:00:00+00");
        insertMembership(connection, schema, TENANT_A, USER_A_EARLY, "2026-01-02 00:00:00+00");
        insertMembership(connection, schema, TENANT_A, USER_A_TIE_LOW, "2026-01-02 00:00:00+00");
        insertMembership(connection, schema, TENANT_A, USER_A_TIE_HIGH, "2026-01-02 00:00:00+00");
        insertMembership(connection, schema, TENANT_B, USER_B_HIGH, "2026-02-01 00:00:00+00");
        insertMembership(connection, schema, TENANT_B, USER_B_LOW, "2026-02-01 00:00:00+00");
        executeUpdate(connection, schema, "insert into books (id, tenant_id, title, status, created_at, updated_at) values ('" + BOOK_A + "', '" + TENANT_A + "', 'Legacy A', 'PLANNING', current_timestamp, current_timestamp)");
        executeUpdate(connection, schema, "insert into books (id, tenant_id, title, status, created_at, updated_at) values ('" + BOOK_B + "', '" + TENANT_B + "', 'Legacy B', 'PLANNING', current_timestamp, current_timestamp)");
    }

    private void insertUser(Connection connection, String schema, UUID userId, String email) throws SQLException {
        executeUpdate(connection, schema, "insert into users (id, display_name, email, time_zone_id, created_at, updated_at) values ('" + userId + "', '" + email + "', '" + email + "', 'UTC', current_timestamp, current_timestamp)");
    }

    private void insertMembership(Connection connection, String schema, UUID tenantId, UUID userId, String joinedAt) throws SQLException {
        executeUpdate(connection, schema, "insert into tenant_memberships (id, tenant_id, user_id, role, joined_at) values ('" + UUID.randomUUID() + "', '" + tenantId + "', '" + userId + "', 'OWNER', timestamptz '" + joinedAt + "')");
    }

    private String collaboratorInsert(UUID id, UUID tenantId, UUID bookId, UUID userId, UUID createdByUserId) {
        return "insert into book_collaborators (id, tenant_id, book_id, user_id, created_at, created_by_user_id) values ('"
                + id + "', '" + tenantId + "', '" + bookId + "', '" + userId + "', current_timestamp, '" + createdByUserId + "')";
    }

    private void assertSqlFails(Connection connection, String schema, String sql) {
        assertThrows(SQLException.class, () -> executeUpdate(connection, schema, sql));
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
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
        }
    }

    private void dropSchema(String schema) throws SQLException {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + schema + " cascade");
        }
    }
}
