package io.castellan.broker.protocol.command;

/** A produced record, sequenced through Raft so every node's {@code PartitionLog} appends it (and
 * assigns it the same offset) in the exact same order. {@code producerId == -1} means the
 * producer isn't requesting idempotence; otherwise {@code (producerId, sequence)} is this
 * partition's dedup key — see {@code broker-server}'s {@code ProducerSequenceTable}, applied at
 * the point every node's {@code ApplyListener} handles this command, not before, since dedup must
 * itself be linearized the same way the append is. */
public record ProduceCommand(
        String topic,
        int partition,
        long producerId,
        long sequence,
        byte[] key,
        byte[] value
) implements BrokerCommand {
}
