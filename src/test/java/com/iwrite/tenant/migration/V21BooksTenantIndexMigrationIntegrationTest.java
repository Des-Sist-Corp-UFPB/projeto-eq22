package com.iwrite.tenant.migration;

import com.iwrite.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V21BooksTenantIndexMigrationIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void createsBooksTenantIdIndex() throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     select indexdef
                     from pg_indexes
                     where schemaname = 'public'
                       and tablename = 'books'
                       and indexname = 'idx_books_tenant_id'
                     """)) {
            try (var resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals(
                        "CREATE INDEX idx_books_tenant_id ON public.books USING btree (tenant_id)",
                        resultSet.getString("indexdef")
                );
                assertFalse(resultSet.next());
            }
        }
    }
}
