package io.castellan.apm.core.sampling;

import java.util.function.LongSupplier;

/**
 * A token-bucket sampler that targets a configured <em>maximum spans-per-second budget</em>
 * instead of a flat "1 in N" ratio. The distinction matters operationally: a flat ratio sampler
 * still floods the collector during a traffic spike (10x traffic in = 10x sampled spans out, just
 * scaled down by a constant), and wastes visibility during quiet periods (it throws away 9 in 10
 * spans even when the collector could easily absorb all of them). A token-bucket keyed to a
 * spans/sec budget does neither: the bucket refills at a fixed rate regardless of load, so the
 * fraction of requests that get a token — the effective sample rate — falls out automatically as
 * {@code min(1, budget / currentArrivalRate)}, which is exactly "adapt to observed throughput"
 * without needing to explicitly measure a rate at all.
 *
 * <h2>Algorithm</h2>
 *
 * Classic token bucket: a bucket starts full (capacity = {@code maxSpansPerSecond}, i.e. it can
 * absorb a full second's burst immediately) and refills continuously at {@code maxSpansPerSecond}
 * tokens/sec, computed lazily on every {@link #shouldSample()} call from elapsed monotonic time
 * (no background thread ticking it). Each call either consumes one token and returns {@code true},
 * or — bucket empty — returns {@code false} and consumes nothing. There is deliberately no
 * separate "arrival rate" estimator (no EWMA of request counts): the bucket's own fill level
 * already <em>is</em> a real-time proxy for "how far under/over budget the recent past was," which
 * is simpler and cannot drift out of sync with the accept/reject decision the way a
 * separately-computed rate estimate could.
 *
 * <p>Every call — accepted or not — counts as one observed "arrival" for the purposes of the class
 * javadoc's headline claim; there is no separate counting path, which is exactly what makes the
 * decision self-consistent (see above).
 *
 * <h2>Thread safety</h2>
 *
 * Synchronized on the refill-and-consume critical section. Sampling decisions happen on every
 * single instrumented method invocation across every application thread, so this is a real hot
 * path; a {@code synchronized} block over a few field reads/writes is cheap enough (uncontended
 * fast path, no I/O, no allocation) that a lock-free CAS-retry-loop version would be premature
 * complexity for the throughput this needs to sustain.
 */
public final class AdaptiveSampler {

    private final double maxSpansPerSecond;
    private final double capacity;
    private final LongSupplier nanoClock;

    private double tokens;
    private long lastRefillNanos;

    public AdaptiveSampler(double maxSpansPerSecond) {
        this(maxSpansPerSecond, System::nanoTime);
    }

    /** Package-visible clock injection point so tests can drive the bucket with virtual time instead of {@code Thread.sleep}. */
    AdaptiveSampler(double maxSpansPerSecond, LongSupplier nanoClock) {
        if (maxSpansPerSecond <= 0) {
            throw new IllegalArgumentException("maxSpansPerSecond must be positive, was " + maxSpansPerSecond);
        }
        this.maxSpansPerSecond = maxSpansPerSecond;
        this.capacity = maxSpansPerSecond;
        this.nanoClock = nanoClock;
        this.tokens = capacity;
        this.lastRefillNanos = nanoClock.getAsLong();
    }

    public static AdaptiveSampler withBudget(double maxSpansPerSecond) {
        return new AdaptiveSampler(maxSpansPerSecond);
    }

    /**
     * Records one observed request/span-candidate "arrival" and returns whether it should be
     * sampled (i.e. actually turned into a recorded, exported {@code Span}).
     */
    public synchronized boolean shouldSample() {
        refill();
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    /** The instantaneous effective sample rate implied by the bucket's current fill level: 1.0 = full budget headroom, 0.0 = fully throttled. */
    public synchronized double currentFillRatio() {
        refill();
        return tokens / capacity;
    }

    private void refill() {
        long now = nanoClock.getAsLong();
        double elapsedSeconds = Math.max(0, now - lastRefillNanos) / 1_000_000_000.0;
        lastRefillNanos = now;
        tokens = Math.min(capacity, tokens + elapsedSeconds * maxSpansPerSecond);
    }
}
