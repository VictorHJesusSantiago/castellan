package io.castellan.ledger.api.web;

import io.castellan.ledger.api.tenant.TenantContext;
import io.castellan.ledger.api.web.dto.AuditEventDto;
import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.Streams;
import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.events.DomainEvent;
import io.castellan.ledger.domain.events.TransactionPosted;
import io.castellan.ledger.domain.ports.EventStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The account's full audit trail: its own lifecycle events (opened/frozen/unfrozen/closed, from
 * {@code Streams.account}) merged with every {@link TransactionPosted} in the tenant's ledger
 * stream that actually touches this account (a posting naming it in either direction), sorted
 * chronologically -- not just the lifecycle stream alone, since "what happened to this account"
 * obviously includes the money that moved through it, not only when it was opened or frozen.
 *
 * <p>Scope cut: {@code FraudFlagRaised} events aren't cross-referenced in here even when they
 * relate to a transaction touching this account (they carry a transaction id, not an account id,
 * so joining them in would mean an extra pass correlating transaction ids) -- a real deployment
 * wanting that would extend this query, not rearchitect it.
 */
@RestController
@RequestMapping("/audit/accounts")
public class AuditController {

    private final EventStore eventStore;

    public AuditController(EventStore eventStore) {
        this.eventStore = eventStore;
    }

    @GetMapping("/{id}")
    public List<AuditEventDto> history(@PathVariable("id") String id) {
        TenantId tenantId = TenantContext.current();
        AccountId accountId = AccountId.of(id);

        List<DomainEvent> events = new ArrayList<>(eventStore.load(Streams.account(tenantId, accountId)));
        for (DomainEvent event : eventStore.load(Streams.ledger(tenantId))) {
            if (event instanceof TransactionPosted posted
                    && posted.postings().stream().anyMatch(p -> p.accountId().equals(accountId))) {
                events.add(event);
            }
        }
        events.sort(Comparator.comparing(DomainEvent::occurredAt));

        return events.stream().map(AuditEventDto::of).toList();
    }
}
