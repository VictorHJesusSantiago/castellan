package io.castellan.apm.collector.latency;

/** {@code p50Millis}/{@code p95Millis}/{@code p99Millis} are {@code 0} when {@code sampleCount ==
 * 0} (no span named {@code operationName} has ever been recorded) rather than {@code NaN} or a
 * thrown exception — an operation nobody has ever called has no latency to report, which is a
 * legitimate answer, not an error. */
public record LatencyPercentiles(String operationName, int sampleCount, double p50Millis, double p95Millis, double p99Millis) {
}
