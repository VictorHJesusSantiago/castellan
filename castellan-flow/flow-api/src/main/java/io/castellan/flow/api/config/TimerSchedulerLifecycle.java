package io.castellan.flow.api.config;

import io.castellan.flow.engine.ProcessEngine;
import io.castellan.flow.engine.TimerRepository;
import io.castellan.flow.engine.TimerScheduler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Starts/stops flow-engine's plain, non-Spring {@link TimerScheduler} alongside this
 * application's own lifecycle — exactly the trivial {@code @PostConstruct}/{@code @PreDestroy}
 * wrapper {@link TimerScheduler}'s own class docs anticipate, since the poller has no dependency
 * on Spring itself.
 */
@Component
public class TimerSchedulerLifecycle {

    private final TimerScheduler scheduler;
    private final Duration pollInterval;

    public TimerSchedulerLifecycle(TimerRepository timers, ProcessEngine engine, Clock clock,
                                    @Value("${flow.timer.poll-interval-ms:500}") long pollIntervalMs) {
        this.scheduler = new TimerScheduler(timers, engine, clock);
        this.pollInterval = Duration.ofMillis(pollIntervalMs);
    }

    @PostConstruct
    void start() {
        scheduler.start(pollInterval);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdown();
    }
}
