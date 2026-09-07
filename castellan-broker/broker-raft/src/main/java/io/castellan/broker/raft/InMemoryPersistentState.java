package io.castellan.broker.raft;

import java.util.Optional;

/** An in-memory {@link PersistentState} — for tests and simulation only; see that interface's
 * own docstring on why this is a real safety hazard in any deployment that can actually restart. */
public final class InMemoryPersistentState implements PersistentState {

    private long currentTerm = 0;
    private String votedFor;

    @Override
    public long currentTerm() {
        return currentTerm;
    }

    @Override
    public Optional<String> votedFor() {
        return Optional.ofNullable(votedFor);
    }

    @Override
    public void save(long currentTerm, String votedFor) {
        this.currentTerm = currentTerm;
        this.votedFor = votedFor;
    }
}
