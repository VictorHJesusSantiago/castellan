package io.castellan.bpmn.model;

import java.time.Duration;
import java.time.Instant;

/**
 * A parsed {@code <timerEventDefinition>}. Supports the two common forms: {@code timeDuration}
 * (ISO-8601 duration, e.g. {@code PT10M}, resolved relative to the moment the token starts
 * waiting) and {@code timeDate} (ISO-8601 instant, an absolute deadline). {@code timeCycle}
 * (repeating timers) is out of scope — a single BPMN timer firing once is what boundary/
 * intermediate timer events need for this module's saga/timeout use cases.
 */
public record TimerDefinition(Duration duration, Instant date) {

    public TimerDefinition {
        if ((duration == null) == (date == null)) {
            throw new IllegalArgumentException("exactly one of duration or date must be set");
        }
    }

    public static TimerDefinition ofDuration(Duration duration) {
        return new TimerDefinition(duration, null);
    }

    public static TimerDefinition ofDate(Instant date) {
        return new TimerDefinition(null, date);
    }

    /** Resolves this definition to an absolute instant, relative to {@code now} for durations. */
    public Instant resolve(Instant now) {
        return duration != null ? now.plus(duration) : date;
    }
}
