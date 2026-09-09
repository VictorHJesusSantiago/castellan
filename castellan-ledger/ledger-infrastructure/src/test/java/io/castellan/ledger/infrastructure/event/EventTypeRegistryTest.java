package io.castellan.ledger.infrastructure.event;

import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.IdempotencyKey;
import io.castellan.ledger.domain.SagaId;
import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.TransactionId;
import io.castellan.ledger.domain.events.AccountClosed;
import io.castellan.ledger.domain.events.AccountFrozen;
import io.castellan.ledger.domain.events.AccountOpened;
import io.castellan.ledger.domain.events.AccountUnfrozen;
import io.castellan.ledger.domain.events.DomainEvent;
import io.castellan.ledger.domain.events.FraudFlagRaised;
import io.castellan.ledger.domain.events.FundsReserved;
import io.castellan.ledger.domain.events.TransactionPosted;
import io.castellan.ledger.domain.events.TransactionReversed;
import io.castellan.ledger.domain.events.TransferCompensated;
import io.castellan.ledger.domain.events.TransferCompleted;
import io.castellan.ledger.domain.events.TransferFailed;
import io.castellan.ledger.domain.events.TransferSagaStarted;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventTypeRegistryTest {

    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    /** Every one of the 12 event kinds {@link DomainEvent} permits must round-trip through
     * tagFor/classForTag to exactly itself -- a stale or transposed switch entry here would
     * silently misfile events under the wrong tag. */
    @Test
    void everyPermittedEventKindRoundTripsThroughItsTag() {
        List<DomainEvent> sample = List.of(
                new AccountOpened(AccountId.newId(), tenant, Currency.getInstance("USD"), "Ada", now),
                new AccountClosed(AccountId.newId(), tenant, "reason", now),
                new AccountFrozen(AccountId.newId(), tenant, "reason", now),
                new AccountUnfrozen(AccountId.newId(), tenant, "reason", now),
                new TransactionPosted(TransactionId.newId(), tenant, List.of(), "desc", Map.of(),
                        new IdempotencyKey("k"), now),
                new TransactionReversed(TransactionId.newId(), TransactionId.newId(), tenant, "reason", now),
                new TransferSagaStarted(SagaId.newId(), tenant, AccountId.newId(), AccountId.newId(),
                        new io.castellan.ledger.domain.Money(100, Currency.getInstance("USD")),
                        new IdempotencyKey("k"), now),
                new FundsReserved(SagaId.newId(), tenant, TransactionId.newId(), now),
                new TransferCompleted(SagaId.newId(), tenant, TransactionId.newId(), now),
                new TransferFailed(SagaId.newId(), tenant, "reason", now),
                new TransferCompensated(SagaId.newId(), tenant, TransactionId.newId(), now),
                new FraudFlagRaised(TransactionId.newId(), tenant, "rule", "reason",
                        FraudFlagRaised.Severity.FLAG, now)
        );

        assertThat(sample).hasSize(12);

        for (DomainEvent event : sample) {
            String tag = EventTypeRegistry.tagFor(event);
            assertThat(EventTypeRegistry.classForTag(tag)).isEqualTo(event.getClass());
        }

        List<String> tags = sample.stream().map(EventTypeRegistry::tagFor).distinct().toList();
        assertThat(tags).hasSize(12);
    }

    @Test
    void unknownTagIsRejectedRatherThanClassLoadedBlindly() {
        assertThatThrownBy(() -> EventTypeRegistry.classForTag("SomeAttackerControlledClassName"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown domain event type tag");
    }
}
