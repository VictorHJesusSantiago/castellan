package io.castellan.apm.collector.servicemap;

/** One observed caller→callee relationship and how many times it was observed — see
 * {@link ServiceMapService} for exactly how "caller"/"callee" are derived from span data. */
public record ServiceMapEdge(String caller, String callee, long callCount) {
}
