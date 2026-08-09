package com.iwrite.health.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseHealthServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    private final DatabaseHealthService databaseHealthService = new DatabaseHealthService(jdbcTemplate);

    @Test
    void isHealthyExecutesSelectOneAgainstTheDatabase() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

        assertThat(databaseHealthService.isHealthy()).isTrue();

        verify(jdbcTemplate).queryForObject(eq("SELECT 1"), eq(Integer.class));
    }

    @Test
    void isHealthyReturnsFalseWhenTheDatabaseQueryFails() {
        String canary = "jdbc:postgresql://secret-host:5432/private?password=SUPER_SECRET_CANARY";
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new QueryTimeoutException(canary));

        assertThat(databaseHealthService.isHealthy()).isFalse();
    }
}
