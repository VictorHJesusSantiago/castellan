package io.castellan.flow.engine;

import org.flywaydb.core.Flyway;

import javax.sql.DataSource;

/** Runs the Flyway migrations under {@code classpath:db/migration} against a {@link DataSource}.
 * Idempotent — safe to call every time a component wires up a database (including in tests that
 * construct a fresh {@link javax.sql.DataSource} pointed at the same underlying H2 file, to
 * simulate a process restart against durable state). */
public final class FlowSchema {

    private FlowSchema() {
    }

    public static void migrate(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .baselineOnMigrate(true)
                .load()
                .migrate();
    }
}
