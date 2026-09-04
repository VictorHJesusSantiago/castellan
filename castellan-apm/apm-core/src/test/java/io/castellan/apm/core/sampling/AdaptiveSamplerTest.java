package io.castellan.apm.core.sampling;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Drives the token bucket with a virtual (manually-advanced) clock rather than real
 * {@code Thread.sleep} calls — the whole point of the adaptive behavior is rate-dependent, and a
 * real-time-based test would be either slow (seconds of real sleeping) or flaky (scheduler jitter
 * changing which side of a threshold a call lands on). A virtual clock makes "1000 requests
 * arrived within exactly one simulated second" a deterministic, instant-to-run assertion.
 */
class AdaptiveSamplerTest {

    private static final long ONE_SECOND_NANOS = 1_000_000_000L;

    private static final class VirtualClock {
        private long nanos = 0;

        long advance(long deltaNanos) {
            nanos += deltaNanos;
            return nanos;
        }
    }

    @Test
    void lowTrafficWellUnderBudgetSamplesEverything() {
        VirtualClock clock = new VirtualClock();
        AdaptiveSampler sampler = new AdaptiveSampler(100.0, () -> clock.nanos);

        int accepted = 0;
        for (int i = 0; i < 20; i++) {
            clock.advance(ONE_SECOND_NANOS / 10);
            if (sampler.shouldSample()) {
                accepted++;
            }
        }

        assertThat(accepted).isEqualTo(20);
    }

    @Test
    void burstAtBudgetCapacityIsFullyAbsorbedByTheInitialFullBucket() {
        VirtualClock clock = new VirtualClock();
        AdaptiveSampler sampler = new AdaptiveSampler(100.0, () -> clock.nanos);

        int accepted = 0;
        for (int i = 0; i < 100; i++) {
            if (sampler.shouldSample()) {
                accepted++;
            }
        }

        assertThat(accepted).isEqualTo(100);
    }

    @Test
    void sustainedTrafficAboveBudgetSettlesToTheBudgetRateNotHigher() {
        VirtualClock clock = new VirtualClock();
        AdaptiveSampler sampler = new AdaptiveSampler(100.0, () -> clock.nanos);

        for (int i = 0; i < 100; i++) {
            sampler.shouldSample();
        }

        int accepted = 0;
        int arrivals = 10_000;
        for (int i = 0; i < arrivals; i++) {
            clock.advance(ONE_SECOND_NANOS / 1000);
            if (sampler.shouldSample()) {
                accepted++;
            }
        }

        assertThat(accepted).isCloseTo(1000, org.assertj.core.data.Offset.offset(5));
    }

    @Test
    void effectiveSampleRateFallsAsOfferedLoadRisesAboveBudget() {
        VirtualClock lowLoadClock = new VirtualClock();
        AdaptiveSampler lowLoadSampler = new AdaptiveSampler(100.0, () -> lowLoadClock.nanos);
        drain(lowLoadSampler);
        int lowLoadAccepted = simulate(lowLoadSampler, lowLoadClock, 1_000, ONE_SECOND_NANOS / 50);

        VirtualClock highLoadClock = new VirtualClock();
        AdaptiveSampler highLoadSampler = new AdaptiveSampler(100.0, () -> highLoadClock.nanos);
        drain(highLoadSampler);
        int highLoadAccepted = simulate(highLoadSampler, highLoadClock, 1_000, ONE_SECOND_NANOS / 5000);

        double lowLoadRate = lowLoadAccepted / 1000.0;
        double highLoadRate = highLoadAccepted / 1000.0;

        assertThat(lowLoadRate).isGreaterThan(0.95);
        assertThat(highLoadRate).isLessThan(0.10);
        assertThat(lowLoadRate).isGreaterThan(highLoadRate);
    }

    /** Exhausts the initial full bucket (at t=0, no simulated time elapses) so a subsequent
     * measurement reflects steady-state refill/drain behavior rather than the one-time initial
     * burst allowance every fresh {@link AdaptiveSampler} starts with. */
    private static void drain(AdaptiveSampler sampler) {
        for (int i = 0; i < 100; i++) {
            sampler.shouldSample();
        }
    }

    @Test
    void currentFillRatioReflectsHeadroomWithoutConsumingATokenDifferentlyThanShouldSample() {
        VirtualClock clock = new VirtualClock();
        AdaptiveSampler sampler = new AdaptiveSampler(100.0, () -> clock.nanos);

        assertThat(sampler.currentFillRatio()).isEqualTo(1.0);

        for (int i = 0; i < 100; i++) {
            sampler.shouldSample();
        }

        assertThat(sampler.currentFillRatio()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void rejectsNonPositiveBudget() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AdaptiveSampler(0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static int simulate(AdaptiveSampler sampler, VirtualClock clock, int arrivals, long intervalNanos) {
        int accepted = 0;
        for (int i = 0; i < arrivals; i++) {
            clock.advance(intervalNanos);
            if (sampler.shouldSample()) {
                accepted++;
            }
        }
        return accepted;
    }
}
