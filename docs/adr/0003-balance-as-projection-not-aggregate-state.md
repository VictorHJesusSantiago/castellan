# ADR 3 — Account balance is a read-model projection, never aggregate state

## Context

`castellan-ledger` is event-sourced: every posted transaction is an immutable `TransactionPosted`
event appended to that account's stream. Something still needs to answer "what is this account's
balance right now" — the question every other financial system answers by reading a mutable `balance`
column.

## Decision

`Account` (the domain aggregate, `ledger-domain`) never holds a balance field. Balance is computed
by `AccountBalanceProjection` (`ledger-infrastructure`), a read-model built purely by folding the
account's event stream — the same event stream that is the write side's only source of truth.

## Why

This is the actual test of whether a system is CQRS or just uses the acronym. If `Account` held a
mutable `balance` int that got incremented on every post, there would be exactly one code path
updating it, and the "read model" would just be a cache of that field — real CQRS's claimed benefit
(the read side can be rebuilt from scratch, re-derived, or even wrong-and-fixed independently of the
write side, because it is *provably* a pure function of the event log) would not actually hold. By
making balance a genuine projection with no other way to compute it, the write side (posting a
transaction) and the read side (querying a balance) are structurally incapable of silently
diverging from each other in the way a hand-maintained cached field can.

The cost is real and stated, not hidden: every balance read is a fold over that account's full event
history unless a separate materialized projection table is kept current (which
`AccountBalanceProjection` is, backed by its own JDBC table, updated by the same outbox-relay
pipeline that publishes domain events) — an unbounded, never-projected event stream would make
balance reads expensive. This project accepts that tradeoff because it is the honest one: a
"balance" field on the aggregate would be faster to read and simpler to write, and would also be
lying about what event sourcing actually buys you.

## Rejected alternative

A mutable `balance` field on `Account`, updated inline when applying a `TransactionPosted` event
during aggregate replay. Rejected because it collapses the CQRS boundary this project exists to
demonstrate — the balance would technically be "derived from events" in the sense that replay
happens to touch it, but nothing would distinguish it from a plain mutable-state ledger with an
event log bolted on as an audit trail.
