package io.castellan.broker.raft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * An {@link ArrayList}-backed {@link RaftLog}: entry at 1-based index {@code i} lives at slot
 * {@code i - 1}. Not persisted across a restart — that is exactly the point of it (unit tests and
 * the deterministic multi-node simulation in this module want a log that behaves correctly without
 * ever touching disk); a real deployment uses {@code broker-storage}'s segment-file implementation
 * instead.
 */
public final class InMemoryRaftLog implements RaftLog {

    private final List<LogEntry> entries = new ArrayList<>();

    @Override
    public long lastIndex() {
        return entries.size();
    }

    @Override
    public long term(long index) {
        if (index == 0) {
            return 0;
        }
        return get(index).map(LogEntry::term)
                .orElseThrow(() -> new IllegalArgumentException("no entry at index " + index));
    }

    @Override
    public Optional<LogEntry> get(long index) {
        if (index < 1 || index > entries.size()) {
            return Optional.empty();
        }
        return Optional.of(entries.get((int) (index - 1)));
    }

    @Override
    public List<LogEntry> entriesFrom(long fromIndexInclusive) {
        if (fromIndexInclusive > lastIndex()) {
            return List.of();
        }
        long start = Math.max(fromIndexInclusive, 1);
        return Collections.unmodifiableList(new ArrayList<>(entries.subList((int) (start - 1), entries.size())));
    }

    @Override
    public void append(LogEntry entry) {
        if (entry.index() != lastIndex() + 1) {
            throw new IllegalArgumentException(
                    "expected append at index " + (lastIndex() + 1) + " but entry has index " + entry.index());
        }
        entries.add(entry);
    }

    @Override
    public void truncateFrom(long fromIndexInclusive) {
        if (fromIndexInclusive < 1) {
            throw new IllegalArgumentException("fromIndexInclusive must be >= 1, got " + fromIndexInclusive);
        }
        if (fromIndexInclusive > entries.size()) {
            return;
        }
        entries.subList((int) (fromIndexInclusive - 1), entries.size()).clear();
    }
}
