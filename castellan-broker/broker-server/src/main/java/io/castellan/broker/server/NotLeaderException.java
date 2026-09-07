package io.castellan.broker.server;

import java.util.Optional;

/** Thrown by {@link RaftEventLoop#proposeAndAwaitApply} when this node isn't the Raft leader —
 * the request-handling layer ({@link BrokerRequestHandler}) catches this and turns it into a
 * {@code NOT_LEADER} client protocol response carrying whatever leader hint is available. */
public final class NotLeaderException extends RuntimeException {

    private final Optional<String> leaderHint;

    public NotLeaderException(Optional<String> leaderHint) {
        super("not the leader" + leaderHint.map(h -> "; last known leader: " + h).orElse(""));
        this.leaderHint = leaderHint;
    }

    public Optional<String> leaderHint() {
        return leaderHint;
    }
}
