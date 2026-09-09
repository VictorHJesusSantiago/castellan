package io.castellan.ledger.api.web.dto;

import io.castellan.ledger.domain.events.DomainEvent;
import io.castellan.ledger.infrastructure.event.EventTypeRegistry;

public record AuditEventDto(
        String eventType,
        String occurredAt,
        DomainEvent event
) {
    public static AuditEventDto of(DomainEvent event) {
        return new AuditEventDto(EventTypeRegistry.tagFor(event), event.occurredAt().toString(), event);
    }
}
