package io.castellan.ledger.domain;

import java.util.UUID;

public record TransactionId(UUID value) {

    public TransactionId {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
    }

    public static TransactionId newId() {
        return new TransactionId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
