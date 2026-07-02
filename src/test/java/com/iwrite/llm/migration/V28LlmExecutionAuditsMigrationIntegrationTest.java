package com.iwrite.llm.migration;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V28LlmExecutionAuditsMigrationIntegrationTest extends PostgresIntegrationTest {

    private static final UUID TENANT_ID = UUID.fromString("28000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("28000000-0000-0000-0000-000000000002");
    private static final UUID AUDIT_LOG_ID = UUID.fromString("28000000-0000-0000-0000-000000000003");

    @Autowired
    private DataSource dataSource;

    @Test
    void createsExpectedIndexesOnPublicSchema() throws Exception {
        assertIndexDefinition(
                "idx_llm_execution_audits_tenant_started_at",
                "CREATE INDEX idx_llm_execution_audits_tenant_started_at ON public.llm_execution_audits "
                        + "USING btree (tenant_id, started_at DESC)"
        );
        assertIndexDefinition(
                "idx_llm_execution_audits_feature_started_at",
                "CREATE INDEX idx_llm_execution_audits_feature_started_at ON public.llm_execution_audits "
                        + "USING btree (feature, started_at DESC)"
        );
        assertIndexDefinition(
                "idx_llm_execution_audits_trace_id",
                "CREATE INDEX idx_llm_execution_audits_trace_id ON public.llm_execution_audits "
                        + "USING btree (trace_id)"
        );
        assertIndexDefinition(
                "idx_llm_execution_audits_resource",
                "CREATE INDEX idx_llm_execution_audits_resource ON public.llm_execution_audits "
                        + "USING btree (tenant_id, resource_type, resource_id, started_at DESC)"
        );
    }

    @Test
    void v28AppliesFromV27BaselineWithoutTouchingExistingAuditLogs() throws Exception {
        String schema = "llm_audit_v28_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);

        try {
            migrate(schema, MigrationVersion.fromVersion("27"));

            try (Connection connection = dataSource.getConnection()) {
                execute(connection, "insert into " + schema + ".audit_logs (id, tenant_id, user_id, action, "
                        + "resource_type, resource_id, occurred_at, result) values ('" + AUDIT_LOG_ID + "', '"
                        + TENANT_ID + "', '" + USER_ID + "', 'BOOK_CREATED', 'BOOK', null, timestamp with time zone "
                        + "'2026-07-01 12:00:00+00', 'SUCCEEDED')");
                assertFalse(tableExists(connection, schema, "llm_execution_audits"));
            }

            migrate(schema, null);

            try (Connection connection = dataSource.getConnection()) {
                assertTrue(tableExists(connection, schema, "llm_execution_audits"));
                assertEquals(
                        "BOOK_CREATED,SUCCEEDED",
                        scalar(connection, "select action || ',' || result from " + schema + ".audit_logs "
                                + "where id = '" + AUDIT_LOG_ID + "'")
                );
                assertConstraintsAreEnforced(connection, schema);
            }
        } finally {
            dropSchema(schema);
        }
    }

    private void assertConstraintsAreEnforced(Connection connection, String schema) throws SQLException {
        // Valid STARTED row.
        execute(connection, insertRow(schema, UUID.randomUUID(),
                "'STARTED'", "null", "null", "null", "null", "null"));

        // Valid terminal rows with and without usage/cost.
        execute(connection, insertRow(schema, UUID.randomUUID(),
                "'SUCCEEDED'", "timestamp with time zone '2026-07-01 12:00:03+00'", "3000", "null", "1200", "null"));
        execute(connection, insertRow(schema, UUID.randomUUID(),
                "'TIMED_OUT'", "timestamp with time zone '2026-07-01 12:01:00+00'", "60000",
                "'PROVIDER_TIMEOUT'", "null", "null"));

        // Unknown status is rejected.
        assertThrows(SQLException.class, () -> execute(connection, insertRow(schema, UUID.randomUUID(),
                "'RUNNING'", "null", "null", "null", "null", "null")));

        // Terminal state without completion timestamp is rejected.
        assertThrows(SQLException.class, () -> execute(connection, insertRow(schema, UUID.randomUUID(),
                "'SUCCEEDED'", "null", "null", "null", "null", "null")));

        // Failure without a stable error category is rejected.
        assertThrows(SQLException.class, () -> execute(connection, insertRow(schema, UUID.randomUUID(),
                "'FAILED'", "timestamp with time zone '2026-07-01 12:00:03+00'", "3000", "null", "null", "null")));

        // Success carrying an error category is rejected.
        assertThrows(SQLException.class, () -> execute(connection, insertRow(schema, UUID.randomUUID(),
                "'SUCCEEDED'", "timestamp with time zone '2026-07-01 12:00:03+00'", "3000",
                "'PROVIDER_TIMEOUT'", "null", "null")));

        // Negative token counts are rejected.
        assertThrows(SQLException.class, () -> execute(connection, insertRow(schema, UUID.randomUUID(),
                "'SUCCEEDED'", "timestamp with time zone '2026-07-01 12:00:03+00'", "3000", "null", "-1", "null")));

        // Cost without a currency is rejected.
        assertThrows(SQLException.class, () -> execute(connection, insertRow(schema, UUID.randomUUID(),
                "'SUCCEEDED'", "timestamp with time zone '2026-07-01 12:00:03+00'", "3000", "null", "null",
                "0.000400")));
    }

    private String insertRow(
            String schema,
            UUID id,
            String status,
            String completedAt,
            String latencyMs,
            String errorCategory,
            String inputTokens,
            String estimatedCost
    ) {
        return "insert into " + schema + ".llm_execution_audits (id, tenant_id, user_id, feature, provider, model, "
                + "prompt_version, trace_id, resource_type, resource_id, started_at, completed_at, latency_ms, "
                + "status, error_category, input_tokens, output_tokens, total_tokens, estimated_cost, cost_currency, "
                + "fallback_used) values ('" + id + "', '" + TENANT_ID + "', '" + USER_ID + "', 'SCENE_ANALYSIS', "
                + "'fake', 'fake-model', 'scene-analysis:v1', '" + UUID.randomUUID() + "', 'SCENE', '"
                + UUID.randomUUID() + "', timestamp with time zone '2026-07-01 12:00:00+00', " + completedAt + ", "
                + latencyMs + ", " + status + ", " + errorCategory + ", " + inputTokens + ", null, null, "
                + estimatedCost + ", null, false)";
    }

    private void assertIndexDefinition(String indexName, String expectedDefinition) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     select indexdef
                     from pg_indexes
                     where schemaname = 'public'
                       and tablename = 'llm_execution_audits'
                       and indexname = ?
                     """)) {
            statement.setString(1, indexName);
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "missing index " + indexName);
                assertEquals(expectedDefinition, resultSet.getString("indexdef"));
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

    private boolean tableExists(Connection connection, String schema, String tableName) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select 1
                from information_schema.tables
                where table_schema = ?
                  and table_name = ?
                """)) {
            statement.setString(1, schema);
            statement.setString(2, tableName);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private String scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
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
