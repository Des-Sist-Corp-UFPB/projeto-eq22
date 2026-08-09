package com.iwrite.health.service;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DatabaseHealthService {

    static final String HEALTH_QUERY = "SELECT 1";
    static final long CONNECTION_TIMEOUT_MILLIS = 2_000L;
    static final long VALIDATION_TIMEOUT_MILLIS = 1_000L;
    static final int DRIVER_CONNECT_TIMEOUT_SECONDS = 2;
    static final int DRIVER_SOCKET_TIMEOUT_SECONDS = 2;
    static final int QUERY_TIMEOUT_SECONDS = 2;

    private final HikariDataSource probeDataSource;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public DatabaseHealthService(DataSourceProperties dataSourceProperties) {
        this(createProbeDataSource(dataSourceProperties));
    }

    DatabaseHealthService(JdbcTemplate jdbcTemplate) {
        this.probeDataSource = null;
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcTemplate.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    }

    private DatabaseHealthService(HikariDataSource probeDataSource) {
        this.probeDataSource = probeDataSource;
        this.jdbcTemplate = new JdbcTemplate(probeDataSource);
        this.jdbcTemplate.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    }

    static HikariDataSource createProbeDataSource(DataSourceProperties dataSourceProperties) {
        HikariDataSource dataSource = dataSourceProperties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();

        dataSource.setPoolName("iwrite-health-probe");
        dataSource.setMinimumIdle(0);
        dataSource.setMaximumPoolSize(1);
        dataSource.setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);
        dataSource.setValidationTimeout(VALIDATION_TIMEOUT_MILLIS);
        dataSource.addDataSourceProperty(
                "connectTimeout",
                Integer.toString(DRIVER_CONNECT_TIMEOUT_SECONDS)
        );
        dataSource.addDataSourceProperty(
                "socketTimeout",
                Integer.toString(DRIVER_SOCKET_TIMEOUT_SECONDS)
        );

        return dataSource;
    }

    /** Real, minimal, read-only round trip to PostgreSQL; not a hardcoded flag. */
    public boolean isHealthy() {
        try {
            return jdbcTemplate.queryForObject(HEALTH_QUERY, Integer.class) != null;
        } catch (DataAccessException e) {
            return false;
        }
    }

    @PreDestroy
    void closeProbeDataSource() {
        if (probeDataSource != null) {
            probeDataSource.close();
        }
    }
}
