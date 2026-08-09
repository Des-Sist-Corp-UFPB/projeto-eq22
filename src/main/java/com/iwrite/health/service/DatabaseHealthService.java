package com.iwrite.health.service;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DatabaseHealthService {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseHealthService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Real, minimal, read-only round trip to PostgreSQL; not a hardcoded flag. */
    public boolean isHealthy() {
        try {
            return jdbcTemplate.queryForObject("SELECT 1", Integer.class) != null;
        } catch (DataAccessException e) {
            return false;
        }
    }
}
