package com.iwrite.support;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public final class TestDatabaseInitializer {

    private static final String DEFAULT_TEST_DB_URL = "jdbc:postgresql://localhost:5435/iwrite_test";
    private static final String DEFAULT_MAINTENANCE_DB_URL = "jdbc:postgresql://localhost:5435/postgres";
    private static final String DEFAULT_USERNAME = "postgres";
    private static final String DEFAULT_PASSWORD = "postgres";
    private static final Pattern SAFE_TEST_DATABASE_NAME = Pattern.compile("[a-zA-Z0-9_]+_test");
    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    private TestDatabaseInitializer() {
    }

    public static void prepareDatabase() {
        if (initialized.compareAndSet(false, true)) {
            recreateTestDatabase(testDbUrl(), username(), password());
        }
    }

    public static String testDbUrl() {
        return env("TEST_DB_URL", DEFAULT_TEST_DB_URL);
    }

    public static String username() {
        return env("TEST_DB_USERNAME", DEFAULT_USERNAME);
    }

    public static String password() {
        return env("TEST_DB_PASSWORD", DEFAULT_PASSWORD);
    }

    private static void recreateTestDatabase(String testDbUrl, String username, String password) {
        String databaseName = databaseName(testDbUrl);
        if (!SAFE_TEST_DATABASE_NAME.matcher(databaseName).matches()) {
            throw new IllegalStateException("Refusing to recreate non-test database: " + databaseName);
        }

        String maintenanceDbUrl = env("TEST_DB_MAINTENANCE_URL", maintenanceDatabaseUrl(testDbUrl));

        try (var connection = DriverManager.getConnection(maintenanceDbUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS " + databaseName + " WITH (FORCE)");
            statement.executeUpdate("CREATE DATABASE " + databaseName);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to recreate PostgreSQL test database " + databaseName, exception);
        }
    }

    private static String maintenanceDatabaseUrl(String testDbUrl) {
        int lastSlashIndex = testDbUrl.lastIndexOf('/');
        if (lastSlashIndex < 0) {
            return DEFAULT_MAINTENANCE_DB_URL;
        }

        String prefix = testDbUrl.substring(0, lastSlashIndex + 1);
        String suffix = "";
        int queryIndex = testDbUrl.indexOf('?', lastSlashIndex);
        if (queryIndex >= 0) {
            prefix = testDbUrl.substring(0, lastSlashIndex + 1);
            suffix = testDbUrl.substring(queryIndex);
        }

        return prefix + "postgres" + suffix;
    }

    private static String databaseName(String jdbcUrl) {
        int lastSlashIndex = jdbcUrl.lastIndexOf('/');
        if (lastSlashIndex < 0 || lastSlashIndex == jdbcUrl.length() - 1) {
            throw new IllegalArgumentException("PostgreSQL JDBC URL does not include a database name: " + jdbcUrl);
        }

        int queryIndex = jdbcUrl.indexOf('?', lastSlashIndex);
        return queryIndex >= 0 ? jdbcUrl.substring(lastSlashIndex + 1, queryIndex) : jdbcUrl.substring(lastSlashIndex + 1);
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
