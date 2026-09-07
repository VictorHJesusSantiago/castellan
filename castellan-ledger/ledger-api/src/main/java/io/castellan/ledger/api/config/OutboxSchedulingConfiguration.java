package io.castellan.ledger.api.config;

import io.castellan.ledger.infrastructure.outbox.OutboxRelay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link OutboxRelay#relay()} on a fixed delay -- see that class's own docs for why this is
 * deliberately a separate, independently-scheduled process rather than something invoked inline
 * from the request thread that appended the event.
 */
@Component
public class OutboxSchedulingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OutboxSchedulingConfiguration.class);

    private final OutboxRelay outboxRelay;

    public OutboxSchedulingConfiguration(OutboxRelay outboxRelay) {
        this.outboxRelay = outboxRelay;
    }

    @Scheduled(fixedDelay = 5000)
    public void relayOutbox() {
        int published = outboxRelay.relay();
        if (published > 0) {
            log.debug("outbox relay published {} event(s)", published);
        }
    }
}
