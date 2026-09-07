package io.castellan.broker.server;

/** What applying one committed {@link io.castellan.broker.protocol.command.BrokerCommand}
 * produced — handed back to whichever client-request-handling thread is waiting on that command's
 * commit via {@link RaftEventLoop#proposeAndAwaitApply}. */
sealed interface ApplyOutcome permits ApplyOutcome.Produced, ApplyOutcome.OffsetCommitted {

    /** {@code offset} is the position the record now occupies in the partition — for a deduped
     * idempotent retry, this is the <em>original</em> attempt's offset, not a new append. */
    record Produced(long offset) implements ApplyOutcome {
    }

    record OffsetCommitted() implements ApplyOutcome {
    }
}
