package io.castellan.ledger.infrastructure.support;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.UUID;

/**
 * Builds a fresh, isolated, Flyway-migrated H2 database per test. Every call gets its own
 * uniquely-named in-memory database (never a shared one) so tests can run concurrently and
 * threads within a single test can open independent physical connections (via a non-pooling
 * {@link DriverManagerDataSource}) to genuinely race against each other at the JDBC level --
 * exactly what the row-locking tests in this package need, since a pooled or single-connection
 * DataSource would serialize what should be concurrent transactions and hide real bugs.
 */
public final class TestDatabase {

    private TestDatabase() {
    }

    public static DataSource newDataSource() {
        String name = "castellan_" + UUID.randomUUID().toString().replace("-", "");
        String url = "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        return dataSource;
    }
}
