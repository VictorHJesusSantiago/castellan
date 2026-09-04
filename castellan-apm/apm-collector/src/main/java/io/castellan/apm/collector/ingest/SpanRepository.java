package io.castellan.apm.collector.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.castellan.apm.core.SpanData;
import io.castellan.apm.core.SpanKind;
import io.castellan.apm.core.SpanStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;

/**
 * Persists and queries {@link SpanData} directly — no separate DTO/entity layer, since
 * {@code SpanData} already <em>is</em> the wire format {@code apm-core}'s exporter sends (see its
 * own class docs) and this module depends on {@code apm-core} anyway.
 *
 * <p>{@link #saveAll} is a {@code MERGE} (upsert) keyed on {@code span_id}, not a plain
 * {@code INSERT}: {@code HttpSpanExporter} has no delivery-acknowledgement/retry of its own today,
 * but a well-behaved ingestion endpoint should not corrupt itself if a future retry (or a client
 * bug) re-POSTs the same span twice — re-ingesting an identical span is a no-op, matching this
 * project's general idempotency posture elsewhere (the ledger's command handler, the broker's
 * producer dedup).
 */
@Repository
public class SpanRepository {

    private static final String MERGE_SQL = """
            MERGE INTO spans (span_id, trace_id, parent_span_id, name, kind, status, error_message,
                               error_stack_trace, start_epoch_millis, end_epoch_millis, duration_nanos, attributes_json)
            KEY (span_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_COLUMNS = """
            span_id, trace_id, parent_span_id, name, kind, status, error_message,
            error_stack_trace, start_epoch_millis, end_epoch_millis, duration_nanos, attributes_json
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RowMapper<SpanData> rowMapper = this::mapRow;

    public SpanRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void saveAll(List<SpanData> spans) {
        if (spans.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(MERGE_SQL, spans, spans.size(), (ps, span) -> {
            ps.setString(1, span.spanId());
            ps.setString(2, span.traceId());
            ps.setString(3, span.parentSpanId());
            ps.setString(4, span.name());
            ps.setString(5, span.kind().name());
            ps.setString(6, span.status().name());
            if (span.errorMessage() != null) {
                ps.setString(7, span.errorMessage());
            } else {
                ps.setNull(7, Types.VARCHAR);
            }
            if (span.errorStackTrace() != null) {
                ps.setString(8, span.errorStackTrace());
            } else {
                ps.setNull(8, Types.CLOB);
            }
            ps.setLong(9, span.startEpochMillis());
            ps.setLong(10, span.endEpochMillis());
            ps.setLong(11, span.durationNanos());
            ps.setString(12, writeAttributes(span.attributes()));
        });
    }

    public List<SpanData> findByTraceId(String traceId) {
        return jdbc.query("SELECT " + SELECT_COLUMNS + " FROM spans WHERE trace_id = ?", rowMapper, traceId);
    }

    /** Every span durations recorded for {@code operationName}, most recent first, capped at
     * {@code limit} — the raw sample {@link io.castellan.apm.collector.latency.LatencyService}
     * computes percentiles over. */
    public List<Long> findRecentDurationsNanosByName(String operationName, int limit) {
        return jdbc.queryForList("""
                SELECT duration_nanos FROM spans WHERE name = ?
                ORDER BY start_epoch_millis DESC LIMIT ?
                """, Long.class, operationName, limit);
    }

    /** One row per distinct (caller span name, callee span name) pair observed where a
     * {@code SERVER} span's parent is a {@code CLIENT} span in the same trace — see
     * {@link io.castellan.apm.core.SpanKind}'s own docs for exactly why that join is what "which
     * service called which" means in this data model. */
    public List<ServiceCallEdge> findClientServerEdges() {
        return jdbc.query("""
                SELECT c.name AS caller_name, s.name AS callee_name, COUNT(*) AS call_count
                FROM spans c
                JOIN spans s ON s.trace_id = c.trace_id AND s.parent_span_id = c.span_id
                WHERE c.kind = 'CLIENT' AND s.kind = 'SERVER'
                GROUP BY c.name, s.name
                ORDER BY c.name, s.name
                """, (rs, rowNum) -> new ServiceCallEdge(
                rs.getString("caller_name"), rs.getString("callee_name"), rs.getLong("call_count")));
    }

    /** Summaries of the most recently started traces, newest first — {@code rootName} is the name
     * of whichever span in that trace has no parent (a trace pathological enough to have more than
     * one root, e.g. two independently-started spans a buggy client tagged with the same trace id,
     * arbitrarily picks one; that is a data-quality problem in the input, not something this query
     * needs to solve). */
    public List<TraceSummary> findRecentTraceSummaries(int limit) {
        return jdbc.query("""
                SELECT s.trace_id AS trace_id,
                       MIN(s.start_epoch_millis) AS start_epoch_millis,
                       COUNT(*) AS span_count,
                       MAX(s.end_epoch_millis) - MIN(s.start_epoch_millis) AS wall_duration_millis,
                       (SELECT r.name FROM spans r
                        WHERE r.trace_id = s.trace_id AND r.parent_span_id IS NULL
                        ORDER BY r.start_epoch_millis LIMIT 1) AS root_name
                FROM spans s
                GROUP BY s.trace_id
                ORDER BY start_epoch_millis DESC
                LIMIT ?
                """, (rs, rowNum) -> new TraceSummary(
                rs.getString("trace_id"),
                rs.getString("root_name") == null ? "(unknown)" : rs.getString("root_name"),
                rs.getInt("span_count"),
                rs.getLong("start_epoch_millis"),
                rs.getLong("wall_duration_millis")),
                limit);
    }

    private SpanData mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SpanData(
                rs.getString("trace_id"),
                rs.getString("span_id"),
                rs.getString("parent_span_id"),
                rs.getString("name"),
                SpanKind.valueOf(rs.getString("kind")),
                SpanStatus.valueOf(rs.getString("status")),
                rs.getString("error_message"),
                rs.getString("error_stack_trace"),
                rs.getLong("start_epoch_millis"),
                rs.getLong("end_epoch_millis"),
                rs.getLong("duration_nanos"),
                readAttributes(rs.getString("attributes_json")));
    }

    private String writeAttributes(Map<String, String> attributes) {
        try {
            return mapper.writeValueAsString(attributes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize span attributes", e);
        }
    }

    private Map<String, String> readAttributes(String json) {
        try {
            return mapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize span attributes: " + json, e);
        }
    }

    public record ServiceCallEdge(String callerName, String calleeName, long callCount) {
    }

    public record TraceSummary(String traceId, String rootName, int spanCount, long startEpochMillis, long wallDurationMillis) {
    }
}
