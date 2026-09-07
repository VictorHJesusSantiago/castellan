package io.castellan.rules;

/**
 * Identifies one fact inserted into a {@link RuleEngine}'s working memory, independent of the
 * fact's own {@code equals}/{@code hashCode}. Rete matching is identity-based, not value-based —
 * two facts that are equal by value but inserted separately (e.g. two {@code TransactionRequest}
 * objects with the same fields) are two distinct facts, each capable of independently joining
 * with other facts and independently being retracted. A handle is what makes that identity
 * addressable without relying on Java object reference equality leaking into the network's public
 * API (tests and callers retract/update by handle, not by "the same object reference").
 */
public final class FactHandle {

    private final long id;
    private final Class<?> factType;

    FactHandle(long id, Class<?> factType) {
        this.id = id;
        this.factType = factType;
    }

    public long id() {
        return id;
    }

    public Class<?> factType() {
        return factType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FactHandle other)) return false;
        return id == other.id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public String toString() {
        return "FactHandle#" + id + "(" + factType.getSimpleName() + ")";
    }
}
