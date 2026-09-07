package io.castellan.flow.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.castellan.bpmn.exec.ProcessState;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** JDBC-backed storage for process instances: the full externalized {@link ProcessState} as a
 * JSON blob (see {@link JsonSupport}), alongside the queryable {@code process_id}/{@code
 * definition_version}/{@code status} columns used to resolve which exact definition version to
 * interpret against on every resume (see {@link ProcessEngine}). */
public final class ProcessInstanceRepository {

    private final JdbcTemplate jdbc;

    public ProcessInstanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ProcessInstanceRecord record) {
        jdbc.update("INSERT INTO process_instances (id, process_id, definition_version, status, state_json, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?)",
                record.id(), record.processId(), record.definitionVersion(), record.status().name(),
                writeState(record.state()), Timestamp.from(record.createdAt()), Timestamp.from(record.updatedAt()));
    }

    public void updateState(String id, ProcessState state, InstanceStatus status, Instant updatedAt) {
        int rows = jdbc.update("UPDATE process_instances SET state_json = ?, status = ?, updated_at = ? WHERE id = ?",
                writeState(state), status.name(), Timestamp.from(updatedAt), id);
        if (rows == 0) {
            throw new NoSuchProcessInstanceException(id);
        }
    }

    public Optional<ProcessInstanceRecord> find(String id) {
        List<ProcessInstanceRecord> rows = jdbc.query(
                "SELECT id, process_id, definition_version, status, state_json, created_at, updated_at "
                        + "FROM process_instances WHERE id = ?",
                ProcessInstanceRepository::map, id);
        return rows.stream().findFirst();
    }

    private static ProcessInstanceRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new ProcessInstanceRecord(
                rs.getString("id"),
                rs.getString("process_id"),
                rs.getInt("definition_version"),
                InstanceStatus.valueOf(rs.getString("status")),
                readState(rs.getString("state_json")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static String writeState(ProcessState state) {
        try {
            return JsonSupport.MAPPER.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize process state", e);
        }
    }

    private static ProcessState readState(String json) {
        try {
            return JsonSupport.MAPPER.readValue(json, ProcessState.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize process state", e);
        }
    }
}
