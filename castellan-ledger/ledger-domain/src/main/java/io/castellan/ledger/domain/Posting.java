package io.castellan.ledger.domain;

/** One line of a {@link DoubleEntryTransaction}: a signed movement of {@code amount} against
 * {@code accountId} on the {@code entryType} side of the ledger. {@code amount} is always
 * non-negative — direction comes entirely from {@link EntryType}, not from the sign of the
 * money, so summing "all debits" or "all credits" never has to branch on sign. */
public record Posting(AccountId accountId, EntryType entryType, Money amount) {

    public Posting {
        if (accountId == null) {
            throw new IllegalArgumentException("accountId must not be null");
        }
        if (entryType == null) {
            throw new IllegalArgumentException("entryType must not be null");
        }
        if (amount == null) {
            throw new IllegalArgumentException("amount must not be null");
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("posting amount must be positive, got " + amount.minorUnits());
        }
    }
}
