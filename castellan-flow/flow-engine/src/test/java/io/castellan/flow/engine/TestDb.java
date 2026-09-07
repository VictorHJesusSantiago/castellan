package io.castellan.flow.engine;

import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.UUID;

/** Test-only H2 {@link DataSource} construction, migrated via {@link FlowSchema#migrate}. Two
 * flavors: {@link #inMemory()} for ordinary tests (fast, no filesystem), and {@link #file(Path)}
 * for anything simulating a process restart — an in-memory database is, by construction, gone the
 * instant its last connection closes, so it can never prove "a fresh component reads back what an
 * earlier one wrote," only a file-backed one can. */
final class TestDb {

    private TestDb() {
    }

    static DataSource inMemory() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:flow-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        FlowSchema.migrate(ds);
        return ds;
    }

    /** Points at a real file under {@code dir}, migrating it. Call this again with the same
     * {@code dir} to obtain an independent {@link DataSource} instance reading the same on-disk
     * data — exactly what simulating "a fresh JVM restarts and reconnects" requires. */
    static DataSource file(Path dir) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:file:" + dir.resolve("flowdb").toAbsolutePath());
        ds.setUser("sa");
        FlowSchema.migrate(ds);
        return ds;
    }

    static JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
