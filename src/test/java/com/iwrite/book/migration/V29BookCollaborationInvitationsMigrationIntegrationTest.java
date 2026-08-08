package com.iwrite.book.migration;

import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.TestDatabaseInitializer;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class V29BookCollaborationInvitationsMigrationIntegrationTest extends PostgresIntegrationTest {

    private static final UUID TENANT_A = UUID.fromString("29000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("29000000-0000-0000-0000-000000000002");
    private static final UUID USER_A = UUID.fromString("29000000-0000-0000-0000-000000000010");
    private static final UUID USER_A_SECOND = UUID.fromString("29000000-0000-0000-0000-000000000011");
    private static final UUID USER_B = UUID.fromString("29000000-0000-0000-0000-000000000020");
    private static final UUID BOOK_A = UUID.fromString("29000000-0000-0000-0000-000000000101");
    private static final UUID BOOK_A_SECOND = UUID.fromString("29000000-0000-0000-0000-000000000102");
    private static final UUID BOOK_B = UUID.fromString("29000000-0000-0000-0000-000000000103");

    @Autowired
    private DataSource dataSource;

    @Test
    void v29CreatesInvitationsTableWithConstraintsAndIndexesFromV28() throws Exception {
        String schema = "phase_c2_v29_" + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);

        try {
            migrate(schema, MigrationVersion.fromVersion("28"));

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                seedBaseData(connection, schema);
                assertEquals("0", scalar(connection, schema, "select count(*)::text from information_schema.tables where table_schema = current_schema() and table_name = 'book_collaboration_invitations'"));
            }

            migrate(schema, null);

            try (Connection connection = TestDatabaseInitializer.openDirectConnection()) {
                assertEquals("1", scalar(connection, schema, "select count(*)::text from information_schema.tables where table_schema = current_schema() and table_name = 'book_collaboration_invitations'"));

                UUID pendingId = UUID.randomUUID();
                executeUpdate(connection, schema, invitationInsert(pendingId, TENANT_A, BOOK_A, USER_A, "writer@example.com", tokenHash(1), "PENDING", "current_timestamp + interval '7 days'", null, null));

                // Duplicate active invitation for the same tenant/book/email/role is blocked.
                assertSqlFails(connection, schema, invitationInsert(UUID.randomUUID(), TENANT_A, BOOK_A, USER_A, "writer@example.com", tokenHash(2), "PENDING", "current_timestamp + interval '7 days'", null, null));

                // Same email is allowed on a different book and a different tenant.
                executeUpdate(connection, schema, invitationInsert(UUID.randomUUID(), TENANT_A, BOOK_A_SECOND, USER_A, "writer@example.com", tokenHash(3), "PENDING", "current_timestamp + interval '7 days'", null, null));
                executeUpdate(connection, schema, invitationInsert(UUID.randomUUID(), TENANT_B, BOOK_B, USER_B, "writer@example.com", tokenHash(4), "PENDING", "current_timestamp + interval '7 days'", null, null));

                // Terminal rows release the active-uniqueness slot.
                executeUpdate(connection, schema, "update book_collaboration_invitations set status = 'REVOKED', revoked_at = current_timestamp where id = '" + pendingId + "'");
                UUID replacementId = UUID.randomUUID();
                executeUpdate(connection, schema, invitationInsert(replacementId, TENANT_A, BOOK_A, USER_A, "writer@example.com", tokenHash(5), "PENDING", "current_timestamp + interval '7 days'", null, null));
                executeUpdate(connection, schema, "update book_collaboration_invitations set status = 'EXPIRED' where id = '" + replacementId + "'");
                executeUpdate(connection, schema, invitationInsert(UUID.randomUUID(), TENANT_A, BOOK_A, USER_A, "writer@example.com", tokenHash(6), "PENDING", "current_timestamp + interval '7 days'", null, null));

                // Token hash is globally unique, even across tenants and books.
                assertSqlFails(connection, schema, invitationInsert(UUID.randomUUID(), TENANT_B, BOOK_B, USER_B, "another@example.com", tokenHash(1), "PENDING", "current_timestamp + interval '7 days'", null, null));

                // Check constraints: status, normalized email, token hash format, lifecycle.
                assertSqlFails(connection, schema, invitationInsert(UUID.randomUUID(), TENANT_A, BOOK_A, USER_A, "status@example.com", tokenHash(7), "USED", "current_timestamp + interval '7 days'", null, null));
                assertSqlFails(connection, schema, invitationInsert(UUID.randomUUID(), TENANT_A, BOOK_A, USER_A, "Upper@Example.com", tokenHash(8), "PENDING", "current_timestamp + interval '7 days'", null, null));
                assertSqlFails(connection, schema, invitationInsert(UUID.randomUUID(), TENANT_A, BOOK_A, USER_A, " padded@example.com", tokenHash(9), "PENDING", "current_timestamp + interval '7 days'", null, null));
                assertSqlFails(connection, schema, invitationInsert(UUID.randomUUID(), TENANT_A, BOOK_A, USER_A, "no-at-sign.example.com", tokenHash(10), "PENDING", "current_timestamp + interval '7 days'", null, null));
                assertSqlFails(connection, schema, invitationInsert(UUID.randomUUID(), TENANT_A, BOOK_A, USER_A, "hash@example.com", "not-a-hash", "PENDING", "current_timestamp + interval '7 days'", null, null));
                assertSqlFails(connection, schema, invitationInsert(UUID.randomUUID(), TENANT_A, BOOK_A, USER_A, "hash2@example.com", "F".repeat(64), "PENDING", "current_timestamp + interval '7 days'", null, null));
                assertSqlFails(connection, schema, invitationInsert(UUID.randomUUID(), TENANT_A, BOOK_A, USER_A, "lifecycle1@example.com", tokenHash(12), "PENDING", "current_timestamp + interval '7 days'", null, "current_timestamp"));
                assertSqlFails(connection, schema, invitationInsert(UUID.randomUUID(), TENANT_A, BOOK_A, USER_A, "lifecycle2@example.com", tokenHash(13), "ACCEPTED", "current_timestamp + interval '7 days'", null, null));
                assertSqlFails(connection, schema, invitationInsert(UUID.randomUUID(), TENANT_A, BOOK_A, USER_A, "lifecycle3@example.com", tokenHash(14), "REVOKED", "current_timestamp + interval '7 days'", "current_timestamp", "current_timestamp"));
                assertSqlFails(connection, schema, invitationInsert(UUID.randomUUID(), TENANT_A, BOOK_A, USER_A, "role@example.com", tokenHash(15), "PENDING", "current_timestamp + interval '7 days'", null, null).replace("'COLLABORATOR'", "'OWNER'"));

                // A revoked row cannot silently return to pending while keeping its revocation timestamp.
                UUID revertId = UUID.randomUUID();
                executeUpdate(connection, schema, invitationInsert(revertId, TENANT_A, BOOK_A, USER_A, "revert@example.com", tokenHash(16), "REVOKED", "current_timestamp + interval '7 days'", null, "current_timestamp"));
                assertSqlFails(connection, schema, "update book_collaboration_invitations set status = 'PENDING' where id = '" + revertId + "'");

                // Tenant/book integrity: an invitation cannot point at another tenant's book,
                // and the inviter must be a member of the invitation's tenant.
                assertSqlFails(connection, schema, invitationInsert(UUID.randomUUID(), TENANT_A, BOOK_B, USER_A, "cross@example.com", tokenHash(17), "PENDING", "current_timestamp + interval '7 days'", null, null));
                assertSqlFails(connection, schema, invitationInsert(UUID.randomUUID(), TENANT_A, BOOK_A, USER_B, "foreign-inviter@example.com", tokenHash(18), "PENDING", "current_timestamp + interval '7 days'", null, null));

                // Inviter membership deletion is blocked while invitations reference it.
                assertSqlFails(connection, schema, "delete from tenant_memberships where tenant_id = '" + TENANT_A + "' and user_id = '" + USER_A + "'");

                // Book deletion cascades to its invitations.
                assertEquals("1", scalar(connection, schema, "select count(*)::text from book_collaboration_invitations where book_id = '" + BOOK_A_SECOND + "'"));
                executeUpdate(connection, schema, "delete from books where id = '" + BOOK_A_SECOND + "'");
                assertEquals("0", scalar(connection, schema, "select count(*)::text from book_collaboration_invitations where book_id = '" + BOOK_A_SECOND + "'"));

                // Indexes for token lookup, tenant/book listing, active uniqueness, and pending expiration.
                assertIndexExists(connection, schema, "uk_book_collaboration_invitations_token_hash");
                assertIndexExists(connection, schema, "idx_book_collaboration_invitations_tenant_book");
                String activeIndexDef = indexDefinition(connection, schema, "uk_book_collaboration_invitations_active");
                assertTrue(activeIndexDef.contains("UNIQUE"), "active invitation index must be unique");
                assertTrue(activeIndexDef.contains("WHERE"), "active invitation index must be partial on status");
                String expiresIndexDef = indexDefinition(connection, schema, "idx_book_collaboration_invitations_pending_expires_at");
                assertTrue(expiresIndexDef.contains("WHERE"), "pending expiration index must be partial on status");
            }
        } finally {
            dropSchema(schema);
        }
    }

    private void seedBaseData(Connection connection, String schema) throws SQLException {
        executeUpdate(connection, schema, "insert into tenants (id, name, default_time_zone_id, created_at, updated_at) values ('" + TENANT_A + "', 'Tenant A', 'UTC', current_timestamp, current_timestamp)");
        executeUpdate(connection, schema, "insert into tenants (id, name, default_time_zone_id, created_at, updated_at) values ('" + TENANT_B + "', 'Tenant B', 'UTC', current_timestamp, current_timestamp)");
        insertUser(connection, schema, USER_A, "owner-a@iwrite.local");
        insertUser(connection, schema, USER_A_SECOND, "second-a@iwrite.local");
        insertUser(connection, schema, USER_B, "owner-b@iwrite.local");
        insertMembership(connection, schema, TENANT_A, USER_A);
        insertMembership(connection, schema, TENANT_A, USER_A_SECOND);
        insertMembership(connection, schema, TENANT_B, USER_B);
        insertBook(connection, schema, BOOK_A, TENANT_A, USER_A, "Book A");
        insertBook(connection, schema, BOOK_A_SECOND, TENANT_A, USER_A, "Book A2");
        insertBook(connection, schema, BOOK_B, TENANT_B, USER_B, "Book B");
    }

    private void insertUser(Connection connection, String schema, UUID userId, String email) throws SQLException {
        executeUpdate(connection, schema, "insert into users (id, display_name, email, time_zone_id, created_at, updated_at) values ('" + userId + "', '" + email + "', '" + email + "', 'UTC', current_timestamp, current_timestamp)");
    }

    private void insertMembership(Connection connection, String schema, UUID tenantId, UUID userId) throws SQLException {
        executeUpdate(connection, schema, "insert into tenant_memberships (id, tenant_id, user_id, role, joined_at) values ('" + UUID.randomUUID() + "', '" + tenantId + "', '" + userId + "', 'OWNER', current_timestamp)");
    }

    private void insertBook(Connection connection, String schema, UUID bookId, UUID tenantId, UUID ownerUserId, String title) throws SQLException {
        executeUpdate(connection, schema, "insert into books (id, tenant_id, owner_user_id, title, status, created_at, updated_at) values ('" + bookId + "', '" + tenantId + "', '" + ownerUserId + "', '" + title + "', 'PLANNING', current_timestamp, current_timestamp)");
    }

    private String invitationInsert(
            UUID id,
            UUID tenantId,
            UUID bookId,
            UUID inviterUserId,
            String recipientEmail,
            String tokenHash,
            String status,
            String expiresAtSql,
            String acceptedAtSql,
            String revokedAtSql
    ) {
        return "insert into book_collaboration_invitations "
                + "(id, tenant_id, book_id, inviter_user_id, recipient_email, requested_role, token_hash, status, expires_at, accepted_at, revoked_at, created_at, updated_at, version) values ('"
                + id + "', '" + tenantId + "', '" + bookId + "', '" + inviterUserId + "', '" + recipientEmail
                + "', 'COLLABORATOR', '" + tokenHash + "', '" + status + "', " + expiresAtSql + ", "
                + (acceptedAtSql == null ? "null" : acceptedAtSql) + ", "
                + (revokedAtSql == null ? "null" : revokedAtSql) + ", current_timestamp, current_timestamp, 0)";
    }

    private String tokenHash(int seed) {
        return String.format("%064d", seed);
    }

    private void assertIndexExists(Connection connection, String schema, String indexName) throws SQLException {
        assertEquals("1", scalar(connection, schema, "select count(*)::text from pg_indexes where schemaname = current_schema() and tablename = 'book_collaboration_invitations' and indexname = '" + indexName + "'"), "missing index " + indexName);
    }

    private String indexDefinition(Connection connection, String schema, String indexName) throws SQLException {
        return scalar(connection, schema, "select indexdef from pg_indexes where schemaname = current_schema() and tablename = 'book_collaboration_invitations' and indexname = '" + indexName + "'");
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
