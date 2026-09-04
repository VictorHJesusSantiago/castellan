package io.castellan.apm.collector.latency;

import io.castellan.apm.collector.ingest.SpanRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Computes latency percentiles for one operation (a span name) from its {@code durationNanos}
 * samples, using the standard "nearest rank" method: the {@code p}-th percentile is the value at
 * sorted index {@code ceil(p * n) - 1}, clamped into range. {@link
 * SpanRepository#findRecentDurationsNanosByName} already bounds the sample to the {@code
 * sampleLimit} most recent spans, so this never sorts an unbounded amount of data regardless of
 * how much history the collector has accumulated for a hot operation.
 */
@Service
public class LatencyService {

    private final SpanRepository repository;

    public LatencyService(SpanRepository repository) {
        this.repository = repository;
    }

    public LatencyPercentiles forOperation(String operationName, int sampleLimit) {
        List<Long> durationsNanos = repository.findRecentDurationsNanosByName(operationName, sampleLimit);
        if (durationsNanos.isEmpty()) {
            return new LatencyPercentiles(operationName, 0, 0, 0, 0);
        }
        List<Long> sorted = durationsNanos.stream().sorted().toList();
        return new LatencyPercentiles(
                operationName,
                sorted.size(),
                percentileMillis(sorted, 0.50),
                percentileMillis(sorted, 0.95),
                percentileMillis(sorted, 0.99));
    }

    private static double percentileMillis(List<Long> sortedNanos, double p) {
        int index = (int) Math.ceil(p * sortedNanos.size()) - 1;
        index = Math.max(0, Math.min(index, sortedNanos.size() - 1));
        return sortedNanos.get(index) / 1_000_000.0;
    }
}
