package io.castellan.ledger.infrastructure.event;

import io.castellan.ledger.domain.events.DomainEvent;
import io.castellan.ledger.domain.events.TransactionPosted;
import io.castellan.ledger.domain.ports.ConcurrencyConflictException;
import io.castellan.ledger.domain.ports.EventStore;
import io.castellan.ledger.infrastructure.projection.AccountBalanceProjection;
import io.castellan.ledger.infrastructure.projection.TransactionHistoryProjection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * The real, JDBC-backed {@link EventStore} -- the actual system of record for this whole
 * application. Every {@link #append} call does four things in one database transaction, or none
 * of them:
 * <ol>
 *   <li>lock the stream's row in {@code streams} and check its version against
 *       {@code expectedVersion} (the optimistic-concurrency check {@link EventStore#append}
 *       documents);</li>
 *   <li>insert one row per event into {@code events}, protected by a unique constraint on
 *       {@code (stream_id, version)} as a second, independent guarantee against the same race;</li>
 *   <li>for events landing in a tenant's <em>ledger</em> stream specifically (identified by the
 *       {@code "ledger-"} stream-id prefix {@code Streams.ledger} always produces -- this class
 *       can't depend on {@code ledger-application} to call {@code Streams} itself without
 *       creating a package cycle, so it recognizes the convention by its documented shape
 *       instead), mirror the event into {@code outbox} for {@link
 *       io.castellan.ledger.infrastructure.outbox.OutboxRelay} to pick up later;</li>
 *   <li>for {@link TransactionPosted} events specifically, drive both read-model projections
 *       ({@link AccountBalanceProjection}, {@link TransactionHistoryProjection}) forward.</li>
 * </ol>
 *
 * <p>The projections are updated synchronously, inside this same transaction -- not via the
 * outbox relay -- so a balance query immediately after a successful {@code append} is guaranteed
 * consistent with it, with no polling-relay lag. The outbox exists for a different reason: to let
 * something <em>outside</em> this database (a message bus, another service) learn "a ledger event
 * was durably committed" as its own, separately-reliable fact -- see {@code OutboxPublisher}'s own
 * docs for what standing in for that downstream system means in this project.
 *
 * <p>Concurrency control does not rely on the unique constraint alone: two concurrent callers
 * appending to a brand-new stream (e.g. two transfers racing to create the same tenant's in-transit
 * suspense account) could otherwise both see "no rows yet" and both attempt {@code expectedVersion
 * = 0}, which a unique constraint on {@code events} alone would not catch until the second insert
 * (leaving the {@code streams} row's version stale). Locking the {@code streams} row first with
 * {@code SELECT ... FOR UPDATE} closes that gap: the second transaction blocks until the first
 * commits, then observes the true, post-commit version and fails the check honestly.
 */
public final class JdbcEventStore implements EventStore {

    /** Every stream id {@code io.castellan.ledger.domain.Streams#ledger} produces starts with
     * this exact prefix -- see that class for the authoritative naming convention. */
    private static final String LEDGER_STREAM_PREFIX = "ledger-";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final EventJsonCodec codec;
    private final AccountBalanceProjection balanceProjection;
    private final TransactionHistoryProjection historyProjection;
    private final Clock clock;

    public JdbcEventStore(
            JdbcTemplate jdbc,
            TransactionTemplate transactionTemplate,
            EventJsonCodec codec,
            AccountBalanceProjection balanceProjection,
            TransactionHistoryProjection historyProjection,
            Clock clock) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.codec = codec;
        this.balanceProjection = balanceProjection;
        this.historyProjection = historyProjection;
        this.clock = clock;
    }

    @Override
    public void append(String streamId, long expectedVersion, List<DomainEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> appendInTransaction(streamId, expectedVersion, events));
    }

    private void appendInTransaction(String streamId, long expectedVersion, List<DomainEvent> events) {
        ensureStreamRowExists(streamId);
        long actualVersion = lockStreamVersion(streamId);
        if (actualVersion != expectedVersion) {
            throw new ConcurrencyConflictException(streamId, expectedVersion, actualVersion);
        }

        long version = expectedVersion;
        boolean isLedgerStream = streamId.startsWith(LEDGER_STREAM_PREFIX);
        Timestamp recordedAt = Timestamp.from(clock.instant());
        for (DomainEvent event : events) {
            version++;
            String tag = EventTypeRegistry.tagFor(event);
            String payload = codec.serialize(event);
            Timestamp occurredAt = Timestamp.from(event.occurredAt());

            try {
                jdbc.update(
                        "INSERT INTO events (stream_id, version, event_type, payload_json, occurred_at, recorded_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?)",
                        streamId, version, tag, payload, occurredAt, recordedAt);
            } catch (DuplicateKeyException conflict) {
                throw new ConcurrencyConflictException(streamId, expectedVersion, currentVersion(streamId));
            }

            if (isLedgerStream) {
                jdbc.update(
                        "INSERT INTO outbox (stream_id, event_type, payload_json, occurred_at, published, created_at) "
                                + "VALUES (?, ?, ?, ?, FALSE, ?)",
                        streamId, tag, payload, occurredAt, recordedAt);
            }

            if (event instanceof TransactionPosted posted) {
                balanceProjection.apply(posted.tenantId(), posted);
                historyProjection.apply(posted.tenantId(), posted);
            }
        }

        jdbc.update("UPDATE streams SET version = ? WHERE stream_id = ?", version, streamId);
    }

    private void ensureStreamRowExists(String streamId) {
        try {
            jdbc.update("INSERT INTO streams (stream_id, version) VALUES (?, 0)", streamId);
        } catch (DuplicateKeyException alreadyExists) {
        }
    }

    private long lockStreamVersion(String streamId) {
        List<Long> rows = jdbc.query(
                "SELECT version FROM streams WHERE stream_id = ? FOR UPDATE",
                (rs, i) -> rs.getLong(1), streamId);
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    @Override
    public List<DomainEvent> load(String streamId) {
        return jdbc.query(
                "SELECT event_type, payload_json FROM events WHERE stream_id = ? ORDER BY version ASC",
                (rs, i) -> codec.deserialize(rs.getString(1), rs.getString(2)),
                streamId);
    }

    @Override
    public long currentVersion(String streamId) {
        List<Long> rows = jdbc.query(
                "SELECT version FROM streams WHERE stream_id = ?", (rs, i) -> rs.getLong(1), streamId);
        return rows.isEmpty() ? 0L : rows.get(0);
    }
}
