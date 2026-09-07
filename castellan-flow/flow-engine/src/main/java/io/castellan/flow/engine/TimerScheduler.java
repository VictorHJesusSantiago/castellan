package io.castellan.flow.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Polls {@link TimerRepository} for due timers and feeds each one back into its paused token via
 * {@link ProcessEngine#deliverTimerSignal}, persisting the result. A hand-rolled {@link
 * ScheduledExecutorService} poller rather than Spring's {@code @Scheduled}: flow-engine has no
 * dependency on spring-context (only spring-jdbc, for {@code JdbcTemplate}), and a plain
 * start/stop poller is exactly as real and is usable from a non-Spring caller (tests, a future
 * CLI) as well as from flow-api, which can wrap it in a trivial {@code @PostConstruct}/{@code
 * @PreDestroy} bean.
 *
 * <p>{@link #pollOnce()} is the unit both the background loop and tests call directly — tests
 * don't wait on wall-clock scheduling to observe a timer firing; they construct a due timer, call
 * {@code pollOnce()}, and assert on the result synchronously.
 */
public final class TimerScheduler {

    private static final Logger log = LoggerFactory.getLogger(TimerScheduler.class);

    private final TimerRepository timers;
    private final ProcessEngine engine;
    private final Clock clock;
    private final ScheduledExecutorService executor;

    private volatile ScheduledFuture<?> task;

    public TimerScheduler(TimerRepository timers, ProcessEngine engine, Clock clock) {
        this.timers = timers;
        this.engine = engine;
        this.clock = clock;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "flow-timer-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void start(Duration pollInterval) {
        if (task != null) {
            return;
        }
        long millis = pollInterval.toMillis();
        task = executor.scheduleWithFixedDelay(this::pollSafely, 0, millis, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
    }

    public void shutdown() {
        stop();
        executor.shutdownNow();
    }

    private void pollSafely() {
        try {
            pollOnce();
        } catch (Exception e) {
            log.error("timer poll failed", e);
        }
    }

    /** Finds every timer due at or before now and delivers it. Returns how many fired. A failure
     * delivering one timer (e.g. its instance's pinned definition version was somehow removed) is
     * logged and skipped — it does not stop the rest of the batch from firing. */
    public int pollOnce() {
        List<TimerRepository.DueTimer> due = timers.findDue(clock.instant());
        int fired = 0;
        for (TimerRepository.DueTimer timer : due) {
            try {
                engine.deliverTimerSignal(timer.instanceId(), timer.tokenId());
                fired++;
            } catch (Exception e) {
                log.warn("failed to deliver timer {} for instance {}", timer.tokenId(), timer.instanceId(), e);
            }
        }
        return fired;
    }
}
