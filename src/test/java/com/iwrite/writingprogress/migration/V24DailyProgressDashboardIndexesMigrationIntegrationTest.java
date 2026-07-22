package com.iwrite.writingprogress.migration;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V24DailyProgressDashboardIndexesMigrationIntegrationTest extends PostgresIntegrationTest {

    private static final UUID TENANT_ID = UUID.fromString("24000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("24000000-0000-0000-0000-000000000002");
    private static final UUID MEMBERSHIP_ID = UUID.fromString("24000000-0000-0000-0000-000000000003");
    private static final UUID BOOK_ID = UUID.fromString("24000000-0000-0000-0000-000000000004");
    private static final UUID PROGRESS_ID = UUID.fromString("24000000-0000-0000-0000-000000000005");

    @Autowired
    private DataSource dataSource;

    @Test
    void createsDashboardAnalyticsIndexesForDailyWritingProgress() throws Exception {
        assertIndexDefinition(
                "idx_daily_progress_user_date_book",
                "CREATE INDEX idx_daily_progress_user_date_book ON public.book_daily_writing_progress USING btree (user_id, progress_date, book_id)"
        );
        assertIndexDefinition(
                "idx_daily_progress_book_date_user",
                "CREATE INDEX idx_daily_progress_book_date_user ON public.book_daily_writing_progress USING btree (book_id, progress_date, user_id)"
        );
        assertUniqueConstraintColumns(
                "uk_book_daily_writing_progress_user_book_date",
                "user_id,book_id,progress_date"
        );
    }

    @Test
    void v24AppliesFromV23BaselineWithoutChangingExistingProgressRows() throws Exception {
        String schema = "phase15_b7d_v24_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);

        try {
            migrate(schema, MigrationVersion.fromVersion("23"));

            try (Connection connection = dataSource.getConnection()) {
                insertTenant(connection, schema);
                insertUser(connection, schema);
                insertMembership(connection, schema);
                insertBook(connection, schema);
                insertProgress(connection, schema);

                assertIndexAbsent(connection, schema, "idx_daily_progress_user_date_book");
                assertIndexAbsent(connection, schema, "idx_daily_progress_book_date_user");
            }

            migrate(schema, null);

            try (Connection connection = dataSource.getConnection()) {
                assertIndexMetadata(connection, schema, "idx_daily_progress_user_date_book", "user_id,progress_date,book_id");
                assertIndexMetadata(connection, schema, "idx_daily_progress_book_date_user", "book_id,progress_date,user_id");
                assertUniqueConstraintColumns(connection, schema, "uk_book_daily_writing_progress_user_book_date", "user_id,book_id,progress_date");
                assertEquals(
                        "24000000-0000-0000-0000-000000000002,24000000-0000-0000-0000-000000000004,2026-06-24,100,125,25,0",
                        scalar(connection, schema, """
                                select user_id::text || ',' ||
                                       book_id::text || ',' ||
                                       progress_date::text || ',' ||
                                       starting_manuscript_word_count::text || ',' ||
                                       ending_manuscript_word_count::text || ',' ||
                                       productive_word_count_change::text || ',' ||
                                       manuscript_adjustment_word_count::text
                                from book_daily_writing_progress
                                where id = '%s'
                                """.formatted(PROGRESS_ID))
                );
            }
        } finally {
            dropSchema(schema);
        }
    }

    private void assertIndexDefinition(String indexName, String expectedDefinition) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     select indexdef
                     from pg_indexes
                     where schemaname = 'public'
                       and tablename = 'book_daily_writing_progress'
                       and indexname = ?
                     """)) {
            statement.setString(1, indexName);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals(expectedDefinition, resultSet.getString("indexdef"));
                assertFalse(resultSet.next());
            }
        }
    }

    private void assertUniqueConstraintColumns(String constraintName, String expectedColumns) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertUniqueConstraintColumns(connection, "public", constraintName, expectedColumns);
        }
    }

    private void assertUniqueConstraintColumns(
            Connection connection,
            String schema,
            String constraintName,
            String expectedColumns
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select
                    c.contype,
                    string_agg(a.attname, ',' order by columns.ordinality) as ordered_columns
                from pg_constraint c
                join pg_class table_class on table_class.oid = c.conrelid
                join pg_namespace namespace on namespace.oid = table_class.relnamespace
                join unnest(c.conkey) with ordinality as columns(attnum, ordinality) on true
                join pg_attribute a on a.attrelid = table_class.oid and a.attnum = columns.attnum
                where namespace.nspname = ?
                  and c.conname = ?
                  and table_class.relname = 'book_daily_writing_progress'
                group by c.contype
                """)) {
            statement.setString(1, schema);
            statement.setString(2, constraintName);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals("u", resultSet.getString("contype"));
                assertEquals(expectedColumns, resultSet.getString("ordered_columns"));
                assertFalse(resultSet.next());
            }
        }
    }

    private void assertIndexMetadata(
            Connection connection,
            String schema,
            String indexName,
            String expectedColumns
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select
                    i.indisunique,
                    am.amname,
                    string_agg(a.attname, ',' order by keys.ordinality) as ordered_columns
                from pg_class table_class
                join pg_namespace namespace on namespace.oid = table_class.relnamespace
                join pg_index i on i.indrelid = table_class.oid
                join pg_class index_class on index_class.oid = i.indexrelid
                join pg_am am on am.oid = index_class.relam
                join unnest(string_to_array(i.indkey::text, ' ')) with ordinality as keys(attnum, ordinality) on keys.attnum <> '0'
                join pg_attribute a on a.attrelid = table_class.oid and a.attnum = keys.attnum::smallint
                where namespace.nspname = ?
                  and table_class.relname = 'book_daily_writing_progress'
                  and index_class.relname = ?
                group by i.indisunique, am.amname
                """)) {
            statement.setString(1, schema);
            statement.setString(2, indexName);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertFalse(resultSet.getBoolean("indisunique"));
                assertEquals("btree", resultSet.getString("amname"));
                assertEquals(expectedColumns, resultSet.getString("ordered_columns"));
                assertFalse(resultSet.next());
            }
        }
    }

    private void assertIndexAbsent(Connection connection, String schema, String indexName) throws SQLException {
        try (var statement = connection.prepareStatement("""
                     select 1
                     from pg_indexes
                     where schemaname = ?
                       and tablename = 'book_daily_writing_progress'
                       and indexname = ?
                     """)) {
            statement.setString(1, schema);
            statement.setString(2, indexName);
            try (var resultSet = statement.executeQuery()) {
                assertFalse(resultSet.next());
            }
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

    private void insertTenant(Connection connection, String schema) throws SQLException {
        executeUpdate(connection, schema,
                "insert into tenants (id, name, default_time_zone_id, created_at, updated_at) values ('" + TENANT_ID + "', 'V24 tenant', 'UTC', current_timestamp, current_timestamp)");
    }

    private void insertUser(Connection connection, String schema) throws SQLException {
        executeUpdate(connection, schema,
                "insert into users (id, display_name, email, time_zone_id, created_at, updated_at) values ('" + USER_ID + "', 'V24 user', 'v24.user@iwrite.local', 'UTC', current_timestamp, current_timestamp)");
    }

    private void insertMembership(Connection connection, String schema) throws SQLException {
        executeUpdate(connection, schema,
                "insert into tenant_memberships (id, tenant_id, user_id, role, joined_at) values ('" + MEMBERSHIP_ID + "', '" + TENANT_ID + "', '" + USER_ID + "', 'OWNER', current_timestamp)");
    }

    private void insertBook(Connection connection, String schema) throws SQLException {
        executeUpdate(connection, schema,
                "insert into books (id, tenant_id, title, status, created_at, updated_at) values ('" + BOOK_ID + "', '" + TENANT_ID + "', 'V24 migration book', 'PLANNING', current_timestamp, current_timestamp)");
    }

    private void insertProgress(Connection connection, String schema) throws SQLException {
        executeUpdate(connection, schema,
                "insert into book_daily_writing_progress (id, user_id, book_id, progress_date, starting_manuscript_word_count, ending_manuscript_word_count, productive_word_count_change, manuscript_adjustment_word_count, created_at, updated_at) values ('"
                        + PROGRESS_ID + "', '" + USER_ID + "', '" + BOOK_ID + "', date '2026-06-24', 100, 125, 25, 0, timestamp with time zone '2026-06-24 12:00:00+00', timestamp with time zone '2026-06-24 12:00:00+00')");
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
