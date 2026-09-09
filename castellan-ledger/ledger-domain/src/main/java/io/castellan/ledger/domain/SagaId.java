package io.castellan.ledger.domain;

import java.util.UUID;

public record SagaId(UUID value) {

    public SagaId {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
    }

    public static SagaId newId() {
        return new SagaId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
