package com.iwrite.tenant.migration;

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

class V20TenantFoundationMigrationIntegrationTest extends PostgresIntegrationTest {

    private static final UUID LEGACY_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LEGACY_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID LEGACY_MEMBERSHIP_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Autowired
    private DataSource dataSource;

    @Test
    void v20BackfillsLegacyManuscriptAndEnforcesTenantConstraints() throws Exception {
        String schema = "phase1_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);

        try {
            migrate(schema, MigrationVersion.fromVersion("19"));
            insertLegacyManuscript(schema);
            migrate(schema, null);

            try (Connection connection = dataSource.getConnection()) {
                assertEquals("Personal", scalar(connection, schema, "select name from tenants where id = '" + LEGACY_TENANT_ID + "'"));
                assertEquals("America/Sao_Paulo", scalar(connection, schema, "select default_time_zone_id from tenants where id = '" + LEGACY_TENANT_ID + "'"));
                assertEquals("Carlos", scalar(connection, schema, "select display_name from users where id = '" + LEGACY_USER_ID + "'"));
                assertEquals("America/Sao_Paulo", scalar(connection, schema, "select time_zone_id from users where id = '" + LEGACY_USER_ID + "'"));
                assertEquals("OWNER", scalar(connection, schema, "select role from tenant_memberships where id = '" + LEGACY_MEMBERSHIP_ID + "'"));
                assertEquals(LEGACY_TENANT_ID.toString(), scalar(connection, schema, "select tenant_id::text from books where title = 'Preserved manuscript'"));
                assertEquals("Original scene content", scalar(connection, schema, "select content_text from scenes where title = 'Preserved scene'"));
                assertEquals("NO", scalar(
                        connection,
                        schema,
                        "select is_nullable from information_schema.columns where table_schema = '" + schema + "' and table_name = 'books' and column_name = 'tenant_id'"
                ));

                assertSqlState(connection, schema, "23505", "insert into tenant_memberships (id, tenant_id, user_id, role, joined_at) values ('10000000-0000-0000-0000-000000000001', '" + LEGACY_TENANT_ID + "', '" + LEGACY_USER_ID + "', 'OWNER', current_timestamp)");
                assertSqlState(connection, schema, "23503", "insert into books (id, tenant_id, owner_user_id, title, status, created_at, updated_at) values ('10000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000099', '" + LEGACY_USER_ID + "', 'Invalid tenant', 'PLANNING', current_timestamp, current_timestamp)");
                assertSqlState(connection, schema, "23503", "insert into tenant_memberships (id, tenant_id, user_id, role, joined_at) values ('10000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000099', '" + LEGACY_USER_ID + "', 'OWNER', current_timestamp)");
                assertSqlState(connection, schema, "23503", "insert into tenant_memberships (id, tenant_id, user_id, role, joined_at) values ('10000000-0000-0000-0000-000000000006', '" + LEGACY_TENANT_ID + "', '10000000-0000-0000-0000-000000000099', 'OWNER', current_timestamp)");
                executeUpdate(connection, schema, "insert into users (id, display_name, email, time_zone_id, created_at, updated_at) values ('10000000-0000-0000-0000-000000000004', 'Second user', 'second.user@iwrite.local', 'UTC', current_timestamp, current_timestamp)");
                assertSqlState(connection, schema, "23514", "insert into tenant_memberships (id, tenant_id, user_id, role, joined_at) values ('10000000-0000-0000-0000-000000000005', '" + LEGACY_TENANT_ID + "', '10000000-0000-0000-0000-000000000004', 'EDITOR', current_timestamp)");
            }
        } finally {
            dropSchema(schema);
        }
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

    private void insertLegacyManuscript(String schema) throws SQLException {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema);
            statement.executeUpdate("insert into books (id, title, status, created_at, updated_at) values ('20000000-0000-0000-0000-000000000001', 'Preserved manuscript', 'PLANNING', current_timestamp, current_timestamp)");
            statement.executeUpdate("insert into sections (id, book_id, title, type, sort_order, created_at, updated_at) values ('20000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000001', 'Preserved section', 'PART', 0, current_timestamp, current_timestamp)");
            statement.executeUpdate("insert into chapters (id, book_id, section_id, title, sort_order, created_at, updated_at) values ('20000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000002', 'Preserved chapter', 0, current_timestamp, current_timestamp)");
            statement.executeUpdate("insert into scenes (id, book_id, chapter_id, title, content_text, status, sort_order, word_count, created_at, updated_at) values ('20000000-0000-0000-0000-000000000004', '20000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000003', 'Preserved scene', 'Original scene content', 'DRAFT', 0, 3, current_timestamp, current_timestamp)");
        }
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

    private void assertSqlState(Connection connection, String schema, String expectedState, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema);
            SQLException exception = assertThrows(SQLException.class, () -> statement.executeUpdate(sql));
            assertEquals(expectedState, exception.getSQLState());
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
