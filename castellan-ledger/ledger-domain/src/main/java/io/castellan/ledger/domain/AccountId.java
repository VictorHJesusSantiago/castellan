package io.castellan.ledger.domain;

import java.util.UUID;

public record AccountId(UUID value) {

    public AccountId {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
    }

    public static AccountId newId() {
        return new AccountId(UUID.randomUUID());
    }

    public static AccountId of(String uuid) {
        return new AccountId(UUID.fromString(uuid));
    }

    /** A deterministic, well-known account id for a system-owned account (e.g. an in-transit
     * suspense account used by {@code TransferSagaOrchestrator}) — the same tenant/purpose/
     * currency combination always yields the same id, with no coordination or lookup needed. */
    public static AccountId systemAccount(TenantId tenantId, String purpose, String currencyCode) {
        String name = "system|" + tenantId.value() + "|" + purpose + "|" + currencyCode;
        return new AccountId(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
