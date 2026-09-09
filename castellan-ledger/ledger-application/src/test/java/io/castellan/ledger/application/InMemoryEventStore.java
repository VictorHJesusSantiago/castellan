package io.castellan.ledger.application;

import io.castellan.ledger.domain.events.DomainEvent;
import io.castellan.ledger.domain.ports.ConcurrencyConflictException;
import io.castellan.ledger.domain.ports.EventStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A test-only {@link EventStore} — {@code ledger-infrastructure} provides the real JDBC-backed
 * implementation. Enforces the exact same optimistic-concurrency contract the interface
 * documents, so tests exercising conflict/retry behavior don't need a real database to do it. */
public final class InMemoryEventStore implements EventStore {

    private final Map<String, List<DomainEvent>> streams = new LinkedHashMap<>();

    @Override
    public synchronized void append(String streamId, long expectedVersion, List<DomainEvent> events) {
        List<DomainEvent> stream = streams.computeIfAbsent(streamId, s -> new ArrayList<>());
        if (stream.size() != expectedVersion) {
            throw new ConcurrencyConflictException(streamId, expectedVersion, stream.size());
        }
        stream.addAll(events);
    }

    @Override
    public synchronized List<DomainEvent> load(String streamId) {
        return List.copyOf(streams.getOrDefault(streamId, List.of()));
    }

    @Override
    public synchronized long currentVersion(String streamId) {
        return streams.getOrDefault(streamId, List.of()).size();
    }
}
