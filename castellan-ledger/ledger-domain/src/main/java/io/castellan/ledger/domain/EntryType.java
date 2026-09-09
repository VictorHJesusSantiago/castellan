package io.castellan.ledger.domain;

/** The two sides of a double-entry posting. Which side increases an account's balance depends on
 * the account's own nature (a DEBIT increases an asset account and decreases a liability account,
 * for instance) — this enum deliberately does not encode that; {@code AccountBalanceProjection}
 * (the read-model, in {@code ledger-infrastructure}) is where balance semantics per account type
 * belong, keeping this type a pure, symmetric statement of "which side of the balance equation".
 */
public enum EntryType {
    DEBIT,
    CREDIT
}
