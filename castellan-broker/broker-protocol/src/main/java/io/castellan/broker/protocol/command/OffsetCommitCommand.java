package io.castellan.broker.protocol.command;

/** A consumer group's offset commit, sequenced through Raft so a committed offset survives a
 * leader failover (unlike group membership itself — see {@link BrokerCommand}'s docs). */
public record OffsetCommitCommand(
        String groupId,
        String topic,
        int partition,
        long offset
) implements BrokerCommand {
}
