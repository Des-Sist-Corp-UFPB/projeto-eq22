package com.iwrite.health.service;

import com.iwrite.health.controller.PingController;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DatabaseHealthServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    private final DatabaseHealthService databaseHealthService = new DatabaseHealthService(jdbcTemplate);

    @Test
    void isHealthyExecutesSelectOneAgainstTheDatabaseWithShortStatementTimeout() {
        when(jdbcTemplate.queryForObject(DatabaseHealthService.HEALTH_QUERY, Integer.class)).thenReturn(1);

        assertThat(databaseHealthService.isHealthy()).isTrue();

        verify(jdbcTemplate).setQueryTimeout(DatabaseHealthService.QUERY_TIMEOUT_SECONDS);
        verify(jdbcTemplate).queryForObject(
                eq(DatabaseHealthService.HEALTH_QUERY),
                eq(Integer.class)
        );
    }

    @Test
    void isHealthyReturnsFalseWhenTheDatabaseQueryFails() {
        String canary = "jdbc:postgresql://secret-host:5432/private?password=SUPER_SECRET_CANARY";
        when(jdbcTemplate.queryForObject(DatabaseHealthService.HEALTH_QUERY, Integer.class))
                .thenThrow(new QueryTimeoutException(canary));

        assertThat(databaseHealthService.isHealthy()).isFalse();
    }

    @Test
    void databaseExceptionCanaryDoesNotCrossTheHttpBoundary() throws Exception {
        String canary = "jdbc:postgresql://secret-host:5432/private?password=SUPER_SECRET_CANARY";
        when(jdbcTemplate.queryForObject(DatabaseHealthService.HEALTH_QUERY, Integer.class))
                .thenThrow(new QueryTimeoutException(canary));

        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new PingController(databaseHealthService))
                .build();

        String responseBody = mockMvc.perform(get("/ping"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("unavailable"))
                .andExpect(jsonPath("$.database").value("down"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(responseBody)
                .doesNotContain("secret-host")
                .doesNotContain("SUPER_SECRET_CANARY")
                .doesNotContain("jdbc:postgresql")
                .doesNotContain(canary);
    }

    @Test
    void probeDataSourceHasIndependentShortConnectionAndSocketDeadlines() {
        DataSourceProperties properties = new DataSourceProperties();
        properties.setUrl("jdbc:postgresql://localhost:5432/iwrite_health_test");
        properties.setUsername("postgres");
        properties.setPassword("postgres");
        properties.setDriverClassName("org.postgresql.Driver");

        HikariDataSource dataSource = DatabaseHealthService.createProbeDataSource(properties);
        try {
            assertThat(dataSource.getPoolName()).isEqualTo("iwrite-health-probe");
            assertThat(dataSource.getMinimumIdle()).isZero();
            assertThat(dataSource.getMaximumPoolSize()).isEqualTo(1);
            assertThat(dataSource.getConnectionTimeout())
                    .isEqualTo(DatabaseHealthService.CONNECTION_TIMEOUT_MILLIS);
            assertThat(dataSource.getValidationTimeout())
                    .isEqualTo(DatabaseHealthService.VALIDATION_TIMEOUT_MILLIS);
            assertThat(dataSource.getDataSourceProperties())
                    .containsEntry(
                            "connectTimeout",
                            Integer.toString(DatabaseHealthService.DRIVER_CONNECT_TIMEOUT_SECONDS)
                    )
                    .containsEntry(
                            "socketTimeout",
                            Integer.toString(DatabaseHealthService.DRIVER_SOCKET_TIMEOUT_SECONDS)
                    );
        } finally {
            dataSource.close();
        }
    }
}
