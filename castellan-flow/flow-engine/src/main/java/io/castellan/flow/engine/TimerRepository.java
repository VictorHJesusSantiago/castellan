package io.castellan.flow.engine;

import io.castellan.bpmn.exec.Token;
import io.castellan.bpmn.exec.TokenStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * A normalized, indexable projection of every {@code WAITING_TIMER} token in {@code
 * process_instances.state_json}, kept in sync on every persist. The scheduler ({@link
 * TimerScheduler}) only ever queries this table — never the JSON blobs — which is what makes
 * "find every due timer across every instance" a single indexed range scan instead of a full scan
 * deserializing and inspecting every instance's state.
 */
public final class TimerRepository {

    private final JdbcTemplate jdbc;

    public TimerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Replaces every timer row for {@code instanceId} with exactly the {@code WAITING_TIMER}
     * tokens present in the just-persisted state. Called after every {@link ProcessState} write —
     * deleting and reinserting wholesale (rather than diffing) is simpler and correct because a
     * token that resolved (timer fired or its racing signal cancelled it) is, by construction, no
     * longer in the new state's token list, so it's naturally dropped here too. */
    public void replaceForInstance(String instanceId, List<Token> tokens) {
        jdbc.update("DELETE FROM timers WHERE instance_id = ?", instanceId);
        for (Token token : tokens) {
            if (token.status() != TokenStatus.WAITING_TIMER) {
                continue;
            }
            jdbc.update("INSERT INTO timers (instance_id, token_id, due_at) VALUES (?,?,?)",
                    instanceId, token.id(), Timestamp.from(token.timerDueAt()));
        }
    }

    public List<DueTimer> findDue(Instant now) {
        return jdbc.query("SELECT instance_id, token_id FROM timers WHERE due_at <= ? ORDER BY due_at",
                TimerRepository::map, Timestamp.from(now));
    }

    private static DueTimer map(ResultSet rs, int rowNum) throws SQLException {
        return new DueTimer(rs.getString("instance_id"), rs.getString("token_id"));
    }

    public record DueTimer(String instanceId, String tokenId) {
    }
}
