package io.castellan.broker.protocol.client;

/**
 * Appends one record to {@code (topic, partition)}. {@code producerId}/{@code sequence} carry the
 * idempotent-producer dedup key (Kafka's own design): {@code producerId == -1} means "no
 * idempotence requested" (the broker appends unconditionally, every retry becomes a new record);
 * otherwise the broker treats {@code (producerId, partition, sequence)} as a dedup key and a retry
 * of an already-applied sequence number is answered with the original assigned offset rather than
 * appended a second time — see {@code broker-server}'s {@code ProducerSequenceTable}.
 */
public record ProduceRequest(
        String topic,
        int partition,
        long producerId,
        long sequence,
        byte[] key,
        byte[] value
) implements ClientMessage {
}
