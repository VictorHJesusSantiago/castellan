package io.castellan.flow.engine;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JDBC-backed storage for versioned BPMN process definitions. Deploying under a {@code processId}
 * that already has versions never overwrites one — it always inserts a new, higher version
 * number, and every earlier version stays resolvable by number forever. This is a direct,
 * durable mirror of flow-rules' in-memory {@code RuleSetRegistry} (see that class's javadoc): the
 * same "latest for new work, exact version forever for anyone who already pinned one" contract,
 * backed by a table instead of a {@code TreeMap} so it survives a restart.
 */
public final class ProcessDefinitionRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ProcessDefinitionRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Synchronized for the same reason {@code RuleSetRegistry.deploy} is: computing "next
     * version" and inserting it must be one atomic step from the caller's point of view. The
     * table's {@code UNIQUE(process_id, version)} constraint is the backstop if it isn't (e.g.
     * multiple JVMs against the same database, which JVM-level synchronization can't cover). */
    public synchronized ProcessDefinitionRecord deploy(String processId, String name, String bpmnXml) {
        Integer maxVersion = jdbc.queryForObject(
                "SELECT MAX(version) FROM process_definitions WHERE process_id = ?", Integer.class, processId);
        int nextVersion = (maxVersion == null ? 0 : maxVersion) + 1;
        Instant now = clock.instant();
        jdbc.update("INSERT INTO process_definitions (process_id, version, name, bpmn_xml, deployed_at) VALUES (?,?,?,?,?)",
                processId, nextVersion, name, bpmnXml, Timestamp.from(now));
        return new ProcessDefinitionRecord(processId, nextVersion, name, bpmnXml, now);
    }

    public Optional<ProcessDefinitionRecord> latest(String processId) {
        List<ProcessDefinitionRecord> rows = jdbc.query(
                "SELECT process_id, version, name, bpmn_xml, deployed_at FROM process_definitions "
                        + "WHERE process_id = ? ORDER BY version DESC LIMIT 1",
                ProcessDefinitionRepository::map, processId);
        return rows.stream().findFirst();
    }

    public Optional<ProcessDefinitionRecord> version(String processId, int version) {
        List<ProcessDefinitionRecord> rows = jdbc.query(
                "SELECT process_id, version, name, bpmn_xml, deployed_at FROM process_definitions "
                        + "WHERE process_id = ? AND version = ?",
                ProcessDefinitionRepository::map, processId, version);
        return rows.stream().findFirst();
    }

    private static ProcessDefinitionRecord map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ProcessDefinitionRecord(
                rs.getString("process_id"),
                rs.getInt("version"),
                rs.getString("name"),
                rs.getString("bpmn_xml"),
                rs.getTimestamp("deployed_at").toInstant());
    }
}
