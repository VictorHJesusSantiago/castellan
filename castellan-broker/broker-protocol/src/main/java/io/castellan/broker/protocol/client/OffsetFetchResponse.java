package io.castellan.broker.protocol.client;

/** {@code offset == -1} means no offset has ever been committed for this (group, topic,
 * partition) — the caller should fall back to its own default (earliest/latest) rather than
 * fetching from a bogus position. */
public record OffsetFetchResponse(ErrorCode errorCode, long offset) implements ClientMessage {
}
