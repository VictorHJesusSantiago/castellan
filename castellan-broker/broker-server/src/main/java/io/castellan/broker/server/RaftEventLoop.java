package io.castellan.broker.server;

import io.castellan.broker.raft.AppendEntriesRequest;
import io.castellan.broker.raft.AppendEntriesResponse;
import io.castellan.broker.raft.Envelope;
import io.castellan.broker.raft.HandleResult;
import io.castellan.broker.raft.ProposeResult;
import io.castellan.broker.raft.RaftMessage;
import io.castellan.broker.raft.RaftNode;
import io.castellan.broker.raft.RaftRole;
import io.castellan.broker.raft.RequestVoteRequest;
import io.castellan.broker.raft.RequestVoteResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * The real "imperative shell" {@link RaftNode}'s own class docs call for: a single background
 * thread that serializes <em>every</em> call into one {@link RaftNode} instance — every inbound
 * peer RPC, every timer firing, and every client write request becomes a task submitted to this
 * one thread, never invoked directly by whatever thread first observed the event (a network
 * reader thread, a scheduled-timer thread, or a client-request-handling thread). This is what
 * makes it safe for {@link RaftNode} itself to stay unsynchronized, per that class's own
 * documented threading contract.
 *
 * <p><strong>Timers.</strong> Both the randomized election timer and the fixed-period heartbeat
 * timer are scheduled on the exact same single-threaded {@link ScheduledExecutorService} that
 * runs every other task — so a timer firing is itself just another task that can never race with,
 * say, an AppendEntries RPC arriving at the same instant; whichever was submitted first simply
 * runs to completion before the other starts. After processing any event, {@link #reconcileTimers}
 * inspects the resulting role and {@link HandleResult#resetElectionTimer()} flag and decides
 * whether to (re)schedule the election timer, cancel it, and/or start the heartbeat timer —
 * exactly reproducing {@code RaftClusterSimulationTest}'s manual
 * {@code triggerElectionTimeout}/{@code triggerHeartbeat} calls, except driven by real elapsed
 * time instead of explicit test calls. The election timer is deliberately <em>not</em> force-reset
 * on every event: {@link RaftNode}'s own docs are explicit that the decision of when a reset is
 * actually warranted is a Raft safety judgment call belonging to that class alone (granting a vote,
 * accepting/rejecting a same-term leader's AppendEntries) — this shell trusts
 * {@link HandleResult#resetElectionTimer()} completely rather than re-deriving the same judgment a
 * second time. The one exception is ensuring a timer is running at all after a leader steps down
 * (that transition alone reports {@code resetElectionTimer=false} in two of
 * {@link RaftNode}'s handlers, since discovering a higher term isn't itself one of the paper's
 * enumerated reset events) — see the {@code electionTimer == null} fallback below.
 */
final class RaftEventLoop implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(RaftEventLoop.class);

    private final RaftNode raftNode;
    private final Consumer<Envelope> outboundSink;
    private final long electionTimeoutMinMs;
    private final long electionTimeoutMaxMs;
    private final long heartbeatIntervalMs;
    private final ScheduledExecutorService executor;

    private ScheduledFuture<?> electionTimerFuture;
    private ScheduledFuture<?> heartbeatTimerFuture;
    private boolean wasLeader = false;
    private volatile Consumer<Boolean> leadershipChangeListener = leader -> { };

    RaftEventLoop(RaftNode raftNode, Consumer<Envelope> outboundSink,
                  long electionTimeoutMinMs, long electionTimeoutMaxMs, long heartbeatIntervalMs) {
        this.raftNode = raftNode;
        this.outboundSink = outboundSink;
        this.electionTimeoutMinMs = electionTimeoutMinMs;
        this.electionTimeoutMaxMs = electionTimeoutMaxMs;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "raft-event-loop-" + raftNode.selfId());
            t.setDaemon(true);
            return t;
        });
    }

    /** Invoked once at startup: schedules this node's first randomized election timer. Every
     * node starts a follower with no timer running until this call. */
    void start() {
        executeSafely(() -> reconcileTimers(true));
    }

    /** Called by {@link RaftTransport} on whatever thread decoded an inbound Raft message —
     * hands off to the event-loop thread immediately rather than touching {@link #raftNode} on
     * the caller's own thread. */
    void handleIncoming(String from, RaftMessage message) {
        executeSafely(() -> {
            HandleResult result = switch (message) {
                case RequestVoteRequest req -> raftNode.handleRequestVote(req);
                case RequestVoteResponse resp -> raftNode.handleRequestVoteResponse(from, resp);
                case AppendEntriesRequest req -> raftNode.handleAppendEntries(req);
                case AppendEntriesResponse resp -> raftNode.handleAppendEntriesResponse(from, resp);
            };
            afterHandling(result);
        });
    }

    /** Submits to {@link #executor}, swallowing rejection: once {@link #close()} has shut the
     * executor down, a message that was already in flight on a {@link RaftTransport} reader
     * thread (a peer's connection racing against this node's own shutdown — the two are never
     * coordinated) can still arrive a moment later. That is an ordinary, harmless race during
     * shutdown, not a bug to surface as an uncaught exception on a daemon thread. */
    private void executeSafely(Runnable task) {
        try {
            executor.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.debug("dropped a task submitted after shutdown for {}: {}", raftNode.selfId(), e.toString());
        }
    }

    /** Runs {@code raftNode.proposeCommand(command)} on the event-loop thread, and — if accepted
     * — blocks the <em>calling</em> thread (a client-request-handling thread, never the event-loop
     * thread itself) until that log entry is actually applied (a majority has durably replicated
     * it) or {@code timeoutMs} elapses. Registering the wait and proposing happen as one atomic
     * task on the event loop, so there is no window in which the entry could be applied before
     * anyone is waiting for it — see the class docs on why single-threaded ownership makes this
     * free rather than requiring its own lock. */
    <T> T proposeAndAwaitApply(byte[] command, CommandApplier applier, long timeoutMs)
            throws NotLeaderException, TimeoutException {
        CompletableFuture<Object> applyFuture = new CompletableFuture<>();
        CompletableFuture<ProposeOutcome> proposeFuture = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                ProposeResult result = raftNode.proposeCommand(command);
                switch (result) {
                    case ProposeResult.Accepted accepted -> {
                        applier.registerWait(accepted.index(), applyFuture);
                        HandleResult hr = raftNode.handleHeartbeatTimeout();
                        afterHandling(hr);
                        proposeFuture.complete(new ProposeOutcome.Accepted(accepted.index()));
                    }
                    case ProposeResult.NotLeader notLeader -> proposeFuture.complete(
                            new ProposeOutcome.Rejected(notLeader.leaderHint()));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            throw new NotLeaderException(Optional.empty());
        }

        ProposeOutcome outcome = proposeFuture.join();
        if (outcome instanceof ProposeOutcome.Rejected rejected) {
            throw new NotLeaderException(rejected.leaderHint());
        }
        long index = ((ProposeOutcome.Accepted) outcome).index();
        try {
            @SuppressWarnings("unchecked")
            T applied = (T) applyFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
            return applied;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting apply", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("apply listener failed", e.getCause());
        } catch (TimeoutException e) {
            applier.cancelWait(index, applyFuture);
            throw e;
        }
    }

    private sealed interface ProposeOutcome {
        record Accepted(long index) implements ProposeOutcome {
        }

        record Rejected(Optional<String> leaderHint) implements ProposeOutcome {
        }
    }

    boolean isLeader() {
        return raftNode.role() == RaftRole.LEADER;
    }

    Optional<String> currentLeaderId() {
        return raftNode.currentLeaderId();
    }

    /** Registers a callback invoked (on the event-loop thread) whenever this node's leadership
     * status changes — {@code broker-server} uses this to reset {@link GroupCoordinator} state the
     * moment leadership is lost, since that state is deliberately not Raft-replicated (see
     * {@code BrokerCommand}'s docs) and a stale copy from a previous leadership stint must never
     * be served. */
    void onLeadershipChange(Consumer<Boolean> listener) {
        this.leadershipChangeListener = listener;
    }

    private void afterHandling(HandleResult result) {
        for (Envelope envelope : result.outbound()) {
            try {
                outboundSink.accept(envelope);
            } catch (RuntimeException e) {
                log.warn("failed to send outbound envelope to {}: {}", envelope.to(), e.toString());
            }
        }
        reconcileTimers(result.resetElectionTimer());
    }

    private void reconcileTimers(boolean resetElectionTimer) {
        boolean isLeaderNow = raftNode.role() == RaftRole.LEADER;
        if (isLeaderNow != wasLeader) {
            wasLeader = isLeaderNow;
            leadershipChangeListener.accept(isLeaderNow);
        }
        if (isLeaderNow) {
            cancelElectionTimer();
            ensureHeartbeatTimerRunning();
        } else {
            cancelHeartbeatTimer();
            if (resetElectionTimer || electionTimerFuture == null) {
                rescheduleElectionTimer();
            }
        }
    }

    private void rescheduleElectionTimer() {
        cancelElectionTimer();
        long delay = ThreadLocalRandom.current().nextLong(electionTimeoutMinMs, electionTimeoutMaxMs + 1);
        electionTimerFuture = executor.schedule(this::fireElectionTimeout, delay, TimeUnit.MILLISECONDS);
    }

    private void fireElectionTimeout() {
        HandleResult result = raftNode.handleElectionTimeout();
        afterHandling(result);
    }

    private void cancelElectionTimer() {
        if (electionTimerFuture != null) {
            electionTimerFuture.cancel(false);
            electionTimerFuture = null;
        }
    }

    private void ensureHeartbeatTimerRunning() {
        if (heartbeatTimerFuture != null) {
            return;
        }
        heartbeatTimerFuture = executor.scheduleAtFixedRate(
                this::fireHeartbeatTimeout, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void fireHeartbeatTimeout() {
        HandleResult result = raftNode.handleHeartbeatTimeout();
        afterHandling(result);
    }

    private void cancelHeartbeatTimer() {
        if (heartbeatTimerFuture != null) {
            heartbeatTimerFuture.cancel(false);
            heartbeatTimerFuture = null;
        }
    }

    /** Graceful-then-forceful shutdown (the standard {@code ExecutorService} idiom), deliberately
     * <em>not</em> a bare {@code shutdownNow()}: a task already running on this thread may be
     * midway through {@link CommandApplier#onApply}, which can be blocked in a {@code FileChannel}
     * write. {@code FileChannel} implements {@code InterruptibleChannel} — interrupting that write
     * (which {@code shutdownNow()} does, to every task on the executor) closes the channel out from
     * under it as a side effect, so a later {@code PartitionLog.close()} flushing that same segment
     * fails with {@code ClosedChannelException} even though nothing was ever double-closed. Letting
     * an in-flight task finish naturally avoids that; only a task that's genuinely stuck escalates
     * to the forceful path. */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
