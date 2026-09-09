package io.castellan.ledger.domain;

import java.util.UUID;

/** Every aggregate and every event in this system belongs to exactly one tenant — multi-tenancy
 * here means strict data partitioning by {@code TenantId}, enforced at the port boundary
 * ({@code EventStore} stream ids and {@code AccountRepository} lookups are always tenant-scoped),
 * not a column applications remember to filter by. */
public record TenantId(UUID value) {

    public TenantId {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
    }

    public static TenantId of(String uuid) {
        return new TenantId(UUID.fromString(uuid));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
