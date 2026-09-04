package io.castellan.broker.client;

/** One record returned by {@link Consumer#poll}, carrying its own {@code topic}/{@code partition}
 * since a single poll can return records drawn from every partition currently assigned to this
 * consumer. */
public record ConsumerRecord(String topic, int partition, long offset, long timestamp, byte[] key, byte[] value) {
}
