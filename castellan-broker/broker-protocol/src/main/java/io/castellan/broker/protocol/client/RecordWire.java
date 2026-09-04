package io.castellan.broker.protocol.client;

/** The wire shape of one {@code broker-storage} {@code Record} returned by a fetch — a separate
 * type from {@code Record} itself so this module has no compile-time dependency on
 * {@code broker-storage} (only {@code broker-server} needs to know how to convert between the
 * two, at the point where a fetch result crosses from storage into a {@link FetchResponse}). */
public record RecordWire(long offset, long timestamp, byte[] key, byte[] value) {
}
