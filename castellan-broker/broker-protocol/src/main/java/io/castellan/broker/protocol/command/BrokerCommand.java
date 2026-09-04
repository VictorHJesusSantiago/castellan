package io.castellan.broker.protocol.command;

/**
 * What actually rides inside a {@code broker-raft} {@code LogEntry}'s opaque {@code command}
 * byte string in this broker: every mutation that must be linearized cluster-wide goes through
 * Raft as one of these two kinds, encoded/decoded by {@link BrokerCommandCodec}.
 *
 * <p>Group <em>membership</em> (join/heartbeat/leave) deliberately does <strong>not</strong> go
 * through here — only a group's durable, cross-failover state does (committed offsets). See this
 * project's top-level report for the reasoning: replicating live membership would mean either
 * blocking a JoinGroup on a Raft round trip for every heartbeat-driven rebalance, or accepting
 * that replicated membership state is itself eventually-consistent with real wall-clock session
 * timeouts anyway — membership is already inherently a leader-local, in-memory, "reset on
 * failover" concept in this design, so putting it through consensus would add cost without adding
 * a real safety guarantee.
 */
public sealed interface BrokerCommand permits ProduceCommand, OffsetCommitCommand {
}
