package com.iwrite.auth.migration;

import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.TestDatabaseInitializer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * V31's own backfill matches the legacy user ({@code carlos.legacy@iwrite.local}, fixed id
 * {@code 00000000-0000-0000-0000-000000000002}, seeded by V20) by exact string equality — before
 * V32/V33 (later in this same slice) ever canonicalize case/padding differences. An installation
 * upgraded from a state where that row's stored email had such a difference is exactly the case V31
 * cannot see: V32/V33 fix the email later in the same path, but nothing retries the persona backfill
 * once they do. V34 is that retry, running after V32/V33.
 *
 * <p>Each test runs against its own throwaway schema, migrated to a specific version first and
 * mutated with raw SQL to reproduce a real pre-upgrade state, then migrated to latest — same approach
 * as {@link V33CanonicalizeUserEmailsMigrationIntegrationTest}.
 */
class V34BackfillLegacyUserPersonaAfterEmailNormalizationMigrationIntegrationTest extends PostgresIntegrationTest {

    private static final String LEGACY_USER_ID = "00000000-0000-0000-0000-000000000002";
    private static final String LEGACY_TENANT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String CANONICAL_EMAIL = "carlos.legacy@iwrite.local";

    @Autowired
    private DataSource dataSource;

    @Test
    void v34BackfillsPersonaForLegacyUserWhoseEmailV31CouldNotMatch() throws Exception {
        String schema = "phase_c1_v34_" + freshSuffix();
        createSchema(schema);

        try {
            migrate(schema, MigrationVersion.fromVersion("30")); // before V31 (or either email constraint) exists

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                // Mixed case and TAB/CRLF padding: exactly the pre-EmailNormalizer state V32/V33 exist
                // to repair, and exactly what V31's exact-equality backfill cannot match.
                executeUpdate(connection, schema,
                        "update users set email = '\t Carlos.Legacy@IWrite.Local \r\n' where id = '" + LEGACY_USER_ID + "'");
            }

            migrate(schema, MigrationVersion.fromVersion("31"));

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                // Pinned as the premise of this whole migration: V31 really did find nothing here.
                assertEquals("0", scalar(connection, schema,
                        "select count(*)::text from user_personas where user_id = '" + LEGACY_USER_ID + "'"));
            }

            migrate(schema, null); // runs V32, V33, then V34

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                assertEquals(CANONICAL_EMAIL, scalar(connection, schema, "select email from users where id = '" + LEGACY_USER_ID + "'"));

                assertEquals("1", scalar(connection, schema,
                        "select count(*)::text from user_personas where user_id = '" + LEGACY_USER_ID + "'"));
                assertEquals("WRITER", scalar(connection, schema,
                        "select persona from user_personas where user_id = '" + LEGACY_USER_ID + "'"));
                assertEquals("true", scalar(connection, schema,
                        "select is_primary::text from user_personas where user_id = '" + LEGACY_USER_ID + "'"));

                // Identity, tenant and membership are untouched — this migration only ever inserts into
                // user_personas.
                assertEquals("1", scalar(connection, schema,
                        "select count(*)::text from tenant_memberships where tenant_id = '" + LEGACY_TENANT_ID + "' and user_id = '" + LEGACY_USER_ID + "'"));
                assertEquals("1", scalar(connection, schema,
                        "select count(*)::text from tenants where id = '" + LEGACY_TENANT_ID + "'"));
            }
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void v34NaoDuplicaQuandoV31JaEncontrouOUsuarioJaCanonico() throws Exception {
        String schema = "phase_c1_v34_" + freshSuffix();
        createSchema(schema);

        try {
            // V20 seeds the legacy user with an already-canonical email; no mutation needed here.
            migrate(schema, null); // full path: V31 finds it directly, V34 must not add a second row

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                assertEquals("1", scalar(connection, schema,
                        "select count(*)::text from user_personas where user_id = '" + LEGACY_USER_ID + "'"));
            }
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void v34NaoFazNadaQuandoNaoHaUsuarioLegado() throws Exception {
        String schema = "phase_c1_v34_" + freshSuffix();
        createSchema(schema);

        try {
            // V22 (later than V20) itself requires the fixed legacy user's OWNER membership to exist
            // — unrelated to this migration, a hard precondition of V22's own writing-ownership
            // backfill — so the user can only be safely removed once every migration through V22 has
            // already run.
            migrate(schema, MigrationVersion.fromVersion("22"));

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                // Cascades away the V20-seeded membership too (fk_tenant_memberships_user ... on
                // delete cascade); the tenant itself is left behind, same as any other tenant.
                executeUpdate(connection, schema, "delete from users where id = '" + LEGACY_USER_ID + "'");
            }

            migrate(schema, null); // must not fail, and must not conjure a persona for a user that isn't there

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                assertEquals("0", scalar(connection, schema, "select count(*)::text from user_personas"));
                assertEquals("0", scalar(connection, schema, "select count(*)::text from users where id = '" + LEGACY_USER_ID + "'"));
            }
        } finally {
            dropSchema(schema);
        }
    }

    private String freshSuffix() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
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
