package io.castellan.broker.storage;

/**
 * One record in a partition's data log: the producer-supplied {@code key} (nullable — a
 * null-keyed record is never eligible for log compaction, matching Kafka's own compacted-topic
 * semantics, see {@link PartitionLog#compact()}) and {@code value}, plus the two fields the log
 * itself assigns on append: the monotonically increasing {@code offset} within the partition, and
 * the wall-clock {@code timestamp} (millis since epoch) the broker appended it at.
 *
 * <p>This is deliberately a different type from Raft's {@code LogEntry}: a {@code LogEntry} is one
 * step of the replication protocol (a term + an opaque command byte string), while a {@code Record}
 * is the actual producer-visible unit of data a partition's log is made of. In this broker, a
 * single committed {@code LogEntry}'s command payload decodes to (among other things) the key/value
 * that becomes exactly one {@code Record} appended here — see
 * {@code broker-server}'s apply-listener wiring.
 */
public record Record(long offset, long timestamp, byte[] key, byte[] value) {

    public Record {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0, got " + offset);
        }
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
    }

    public boolean hasKey() {
        return key != null;
    }
}
