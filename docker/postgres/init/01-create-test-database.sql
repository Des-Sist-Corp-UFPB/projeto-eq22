SELECT 'CREATE DATABASE iwrite_test'
WHERE NOT EXISTS (
    SELECT FROM pg_database WHERE datname = 'iwrite_test'
)\gexec
