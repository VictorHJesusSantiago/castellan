package io.castellan.ledger.domain.ports;

/** Thrown by {@link EventStore#append} when the stream's actual version doesn't match the
 * caller's {@code expectedVersion} — someone else committed to this same aggregate first. The
 * correct response is almost always "reload the aggregate and retry the command", not "propagate
 * as a fatal error"; {@code PostTransactionHandler} does exactly that for a bounded number of
 * attempts before giving up. */
public final class ConcurrencyConflictException extends RuntimeException {

    private final String streamId;
    private final long expectedVersion;
    private final long actualVersion;

    public ConcurrencyConflictException(String streamId, long expectedVersion, long actualVersion) {
        super("concurrency conflict on stream " + streamId + ": expected version " + expectedVersion
                + " but actual version is " + actualVersion);
        this.streamId = streamId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public String streamId() {
        return streamId;
    }

    public long expectedVersion() {
        return expectedVersion;
    }

    public long actualVersion() {
        return actualVersion;
    }
}
