package io.castellan.broker.protocol.client;

import java.util.List;

/** {@code highWatermark} is one past the highest offset this node has appended locally (i.e. its
 * {@code PartitionLog.latestOffset()}) — a consumer compares its next fetch offset against this to
 * know whether it is caught up or there is more data immediately available. */
public record FetchResponse(
        ErrorCode errorCode,
        List<RecordWire> records,
        long highWatermark
) implements ClientMessage {

    public FetchResponse {
        records = List.copyOf(records);
    }
}
