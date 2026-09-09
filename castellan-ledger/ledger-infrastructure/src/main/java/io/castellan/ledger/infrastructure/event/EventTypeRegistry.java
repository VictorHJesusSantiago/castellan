package io.castellan.ledger.infrastructure.event;

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

/**
 * The single place a {@link DomainEvent} instance maps to and from the short, stable string tag
 * stored alongside its JSON payload in the {@code events}/{@code outbox} tables. Both directions
 * are plain {@code switch} expressions, not {@code Class.forName} on a stored string: the
 * tag-to-class direction is an explicit, closed list of the 11 known event kinds (an unrecognized
 * tag is a clear {@link IllegalArgumentException}, never arbitrary class loading of
 * attacker-controlled input), and the class-to-tag direction is exhaustiveness-checked by the
 * compiler against {@link DomainEvent}'s sealed permits clause, exactly like every other
 * {@code switch} over this hierarchy elsewhere in the codebase -- a twelfth event kind added to
 * {@code ledger-domain} without updating this class fails to compile here, rather than silently
 * losing the ability to persist it.
 */
public final class EventTypeRegistry {

    private EventTypeRegistry() {
    }

    public static String tagFor(DomainEvent event) {
        return switch (event) {
            case AccountOpened e -> "AccountOpened";
            case AccountClosed e -> "AccountClosed";
            case AccountFrozen e -> "AccountFrozen";
            case AccountUnfrozen e -> "AccountUnfrozen";
            case TransactionPosted e -> "TransactionPosted";
            case TransactionReversed e -> "TransactionReversed";
            case TransferSagaStarted e -> "TransferSagaStarted";
            case FundsReserved e -> "FundsReserved";
            case TransferCompleted e -> "TransferCompleted";
            case TransferFailed e -> "TransferFailed";
            case TransferCompensated e -> "TransferCompensated";
            case FraudFlagRaised e -> "FraudFlagRaised";
        };
    }

    public static Class<? extends DomainEvent> classForTag(String tag) {
        return switch (tag) {
            case "AccountOpened" -> AccountOpened.class;
            case "AccountClosed" -> AccountClosed.class;
            case "AccountFrozen" -> AccountFrozen.class;
            case "AccountUnfrozen" -> AccountUnfrozen.class;
            case "TransactionPosted" -> TransactionPosted.class;
            case "TransactionReversed" -> TransactionReversed.class;
            case "TransferSagaStarted" -> TransferSagaStarted.class;
            case "FundsReserved" -> FundsReserved.class;
            case "TransferCompleted" -> TransferCompleted.class;
            case "TransferFailed" -> TransferFailed.class;
            case "TransferCompensated" -> TransferCompensated.class;
            case "FraudFlagRaised" -> FraudFlagRaised.class;
            default -> throw new IllegalArgumentException("unknown domain event type tag: " + tag);
        };
    }
}
