# Architecture

## Monorepo shape

One Maven reactor, four aggregator POMs (`castellan-ledger`, `castellan-broker`, `castellan-apm`,
`castellan-flow`), twenty leaf modules. Every leaf module follows the same layering discipline
regardless of domain: a pure-Java core with zero framework dependency at the bottom (`ledger-domain`,
`broker-raft`, `flow-rules`, `apm-core`), an application/orchestration layer above it, and — only
where the system genuinely needs one — a Spring Boot module at the top wiring real JDBC adapters
and REST controllers to the layers beneath. See [ADR 0001](adr/0001-monorepo-four-independent-systems.md).

Shared conventions across all four: Java 21 records for every value type, sealed interfaces +
exhaustive `switch` for every closed variant set (Raft's four RPC shapes, the ledger's eleven
domain events, the client protocol's sixteen message types), narrow exception hierarchies (one
class per real failure category, never a bare `RuntimeException`), and — the house style this
project leans on hardest — class-level javadoc that states *why*, including the scope cuts, not
just what a class does. That javadoc is the authoritative design record; this file is a map, not a
duplicate of it.

---

## `castellan-broker` — a distributed log broker

### Module chain

```
broker-raft        RaftNode: the consensus state machine, zero I/O, zero threads
broker-storage     PartitionLog (data), FileRaftLog/FilePersistentState (consensus durability)
broker-protocol    Frame framing + codecs for both the Raft RPCs and the client protocol
broker-server      RaftEventLoop (the imperative shell), network listeners, GroupCoordinator
broker-client      Producer, Consumer, LeaderRouter
```

### `broker-raft`

A from-scratch implementation of the Raft paper's Figure 2 — leader election, log replication,
safety — deliberately built as a pure, deterministic state machine rather than an actor or a
thread-per-node model (see [ADR 0002](adr/0002-raft-from-figure-2-not-a-library.md)): every public method (`handleRequestVote`, `handleAppendEntries`,
`handleAppendEntriesResponse`, `handleElectionTimeout`, `handleHeartbeatTimeout`,
`proposeCommand`) takes the current event as input and returns a `HandleResult` — the outbound
`Envelope`s to send and whether the election timer should reset — with **all** I/O and timers
pushed to the caller. This mirrors etcd's own `raft.Ready()` pattern and is what makes `RaftNode`
itself testable without a network, a clock, or a thread: `RaftClusterSimulationTest` drives a
3-node cluster to a real, verified leader election and log replication purely by feeding
`HandleResult`s back into peer nodes' `handle*` methods in a test-controlled order.

Two correctness properties worth calling out because they are exactly where a naive Raft
implementation gets subtly wrong:

- **Fast backtrack, not linear**: a rejected `AppendEntries` carries `conflictTerm`/`conflictIndex`
  in its response, letting the leader jump straight to the first index of the follower's conflicting
  term rather than decrementing `nextIndex` one at a time — the paper's own suggested optimization,
  actually implemented rather than left as a comment.
- **Never commit a prior-term entry directly**: `advanceCommitIndexIfPossible` only ever commits
  based on a replicated majority of the **current term**'s entries (§5.4.2's safety argument) — an
  older-term entry becomes committed only as a side effect of a current-term entry after it also
  reaching a majority, exactly the subtlety a naive "commit whatever a majority has" implementation
  gets wrong and that causes silent, hard-to-reproduce data loss on leader change.

One real bug was caught and fixed during this project by `RaftClusterSimulationTest`, not written
in from a spec: `advanceCommitIndexIfPossible` originally returned `void`; a follower only learned
its `commitIndex` had advanced on the leader's *next* heartbeat, a real (if bounded) commit-visibility
lag. Fixed by having it return whether the commit index moved and broadcasting a fresh
`AppendEntries` round to every peer immediately when it does.

Scope cut, stated plainly: no cluster membership changes (joint consensus, §6) and no log
snapshotting (§7) — membership is static for a cluster's lifetime, and the log is retained in full
rather than compacted via snapshot (see `broker-storage`'s own, separate retention/compaction, which
covers the *data* log, not the *Raft* log).

### `broker-storage`

`PartitionLog`: Kafka's own segment design — an ordered sequence of `<baseOffset>.log`/`.index`
file pairs, the newest ("active") accepting appends and rotating once it hits a size limit, a dense
index (one entry per record, not sparse — a deliberate simplification that trades index-file size
for "every lookup is one binary search, no secondary scan"). Real crash recovery: `Segment.openOrCreate`
re-scans and re-validates every record's CRC32C on open, truncating both files back to the last
known-good record boundary on a torn trailing write (the standard log-segment recovery technique).
Real retention (time- and size-bound, oldest segments first, active segment always exempt) and real
Kafka-style compaction (closed segments only, keyed by the record's own `key` field, survivors keep
their original offsets).

`FileRaftLog`/`FilePersistentState`: the separate, *fsync'd-per-entry* durability layer
`broker-raft` needs for its own log and `(currentTerm, votedFor)` — unlike `PartitionLog`, which
trades per-record fsync for throughput (a lost unflushed produce is resendable; a lost
already-acknowledged Raft entry is a consensus safety violation).

### `broker-protocol` and `broker-server`

A custom binary framing (`[4-byte length][1-byte type][payload]`, message types 1–9 for the four
Raft RPCs and 10–29 for the sixteen client-protocol messages, kept on separate listening ports so
the accepting side never needs to sniff which codec applies) rather than reusing an existing
serialization library — deliberate, since one of this project's stated goals was a genuine
from-scratch protocol, not a demonstration of picking a library well.

`RaftEventLoop` is the "imperative shell" `RaftNode`'s own docs call for: one single-threaded
`ScheduledExecutorService` per node that serializes every inbound RPC, every timer firing, and
every client write request into calls on one `RaftNode` instance — what makes it safe for
`RaftNode` to stay unsynchronized. `GroupCoordinator` implements consumer-group membership and a
deterministic round-robin partition assignment, deliberately **not** Raft-replicated (membership is
cheap to rebuild — every member simply rejoins the new leader after a failover) while offset
commits **are** replicated, via the same command log a produce uses.

A real bug was caught by `broker-client`'s own test suite, not by `broker-server`'s: `RaftEventLoop.close()`
originally called `executor.shutdownNow()`, which interrupts any in-flight task — including one
mid-write inside `CommandApplier.onApply`. Java NIO's `FileChannel` implements `InterruptibleChannel`,
so an interrupted write silently closes the channel as a side effect; a subsequent
`PartitionLog.close()` flush on that same segment then failed with `ClosedChannelException`, not
because anything was double-closed but because the shutdown path itself broke it. Fixed with the
standard graceful-then-forceful `ExecutorService` shutdown idiom (`shutdown()` + bounded
`awaitTermination()`, escalating to `shutdownNow()` only if that times out).

### `broker-client`

`LeaderRouter` is the shared plumbing behind both `Producer` and `Consumer`: a cache of known node
addresses (seeded from bootstrap, grown from every `MetadataResponse` seen) and a cached current
leader, invalidated and rediscovered on a `NOT_LEADER` response's `leaderHint` or on an outright
connection failure — proven by a test that bootstraps a producer from *only* follower addresses and
shows it still finds the leader. `Producer`'s idempotent mode assigns one random `producerId` for
its lifetime and a monotonically increasing per-`(topic, partition)` sequence, driving the
server-side dedup described above. `Consumer.heartbeat()` transparently rejoins on
`REBALANCE_IN_PROGRESS` rather than surfacing it to the caller, and `Consumer.leave()`/a rebalance
notify an optional `RebalanceListener` with revocation always reported before assignment.

---

## `castellan-ledger` — an event-sourced core banking ledger

### Module chain

```
ledger-domain           Money, Account, DoubleEntryTransaction, domain events, EventStore port
ledger-application       command handlers, TransferSaga, fraud rule engine
ledger-infrastructure     JDBC EventStore, IdempotencyStore, OutboxRelay, read-model projections
ledger-api                Spring Boot REST API, multi-tenancy resolution
```

### Event sourcing, and where CQRS actually bites

Every account is an append-only stream of `DomainEvent`s (`TransactionPosted`, `AccountOpened`,
`AccountFrozen`, `TransferSagaStarted`, `FraudFlagRaised`, ...) in a per-stream `EventStore` with
optimistic concurrency via `expectedVersion` — a concurrent writer racing the same stream gets a
`ConcurrencyConflictException`, not a silently lost update. The one design point worth calling out
explicitly: **an account's current balance is never aggregate state** — it is a read-model
projection (`AccountBalanceProjection`), computed from the event stream, never held as a mutable
field anywhere that could drift out of sync with the events that are supposed to be its source of
truth. This is CQRS taken seriously rather than as a buzzword: the write side (posting a
transaction) and the read side (asking what a balance currently is) are structurally different code
paths that only ever agree because one is derived from the other, never because both were updated
in the same place by hand. See [ADR 0003](adr/0003-balance-as-projection-not-aggregate-state.md).

`DoubleEntryTransaction`'s invariant — every posting set sums to zero — is enforced in its
constructor, not by a later validation pass: it is structurally impossible to construct an
unbalanced transaction object in this codebase.

### The crash-safe idempotent command handler

`PostTransactionHandler` is the one place a client's retried request (after a timeout whose
*response* was lost, not whose write was lost) must not double-post. The mechanism: a
deterministic transaction id derived from the client's own idempotency key (not a fresh random
one per attempt), plus an explicit "is this exact transaction already posted?" re-check against the
event store before appending — so a retry that arrives after the original write already committed
recognizes its own prior work and returns the same result instead of appending a duplicate.

### Saga orchestration for transfers

`TransferSagaOrchestrator` runs reserve → capture-or-fail → compensate as a real state machine
(`TransferSagaState`), backed by its own event stream so it survives a process restart mid-transfer
— not an in-memory coroutine. Compensation moves funds through an explicit suspense/in-transit
account rather than attempting to "undo" a posted transaction in place, which is the same pattern
real payment systems use for exactly the reason it's needed here: a partially-completed transfer
must leave an audit-visible trail of what was reserved and what was returned, not a transaction that
silently never happened.

### Antifraud, outbox, multi-tenancy

`FraudRuleEngine` runs a small set of composable rules (`LargeAmountRule`, `VelocityRule`,
`NewAccountLargeTransferRule`) against every posting attempt; a block still records a
`FraudFlagRaised` audit event even though the transaction itself never posts — the audit trail
exists independent of the outcome. `OutboxRelay` is the standard outbox pattern: events are written
to an outbox table in the same transaction as the ledger append, then relayed to
`LoggingOutboxPublisher` (a stated stand-in for a real message bus) on a separate, independently
retryable schedule — so "did the ledger write succeed" and "did downstream get notified" are never
the same atomic question. `TenantResolvingFilter` rejects any request without a valid `X-Tenant-Id`
before it reaches a controller, and every repository query is scoped by tenant at the SQL layer, not
left to controller-level discipline.

---

## `castellan-apm` — a bytecode-instrumenting Java APM agent

### Module chain

```
apm-core         Span, Tracer, AdaptiveSampler, SpanExporter (HTTP + logging)
apm-agent        java.lang.instrument entry point, the ASM weaver (primary), a Byte Buddy JDBC-only transformer (secondary)
apm-collector    Spring Boot: span ingestion, trace reassembly, service-map derivation, latency percentiles
```

### Two independent instrumentation strategies, on purpose

The primary path (`CastellanClassFileTransformer` + `ProbeClassVisitor`/`SpanWeavingClassVisitor`,
hand-written ASM bytecode generation) instruments JDBC, outbound `HttpURLConnection`, and Spring MVC
handlers by rewriting method bodies directly — wrapping each matched method in a span, injecting
`traceparent` propagation at the one legally-correct point for `HttpURLConnection`
(`connect()`, before headers may no longer be set), and reading an inbound `traceparent` only for
handlers that take an explicit `HttpServletRequest` parameter (stated scope cut — most real Spring
MVC handlers don't). A secondary, `transformer=bytebuddy` Byte Buddy transformer covers JDBC only,
kept specifically as a second, independent implementation of the same instrumentation point for
comparison — not because the ASM path is incomplete. See
[ADR 0004](adr/0004-asm-primary-bytebuddy-secondary.md).

Two real bugs surfaced building the Byte Buddy path, both about HotSpot's actual retransformation
semantics rather than anything Byte Buddy's API hides: (1) a test manually called
`retransformClasses()` *after* `AgentBuilder.installOn()` had already retransformed the same
already-loaded class automatically under `RedefinitionStrategy.RETRANSFORMATION`, and the redundant
second retransform of an already-woven class failed with "attempted to add a method"; (2) once that
redundant call was removed, the *automatic* retransform was found to be silently failing on its own
— Byte Buddy's default Advice-weaving structurally adds a method, which HotSpot retransformation
categorically forbids — fixed by adding `.disableClassFormatChanges()` to the `AgentBuilder` chain
(confirmed via `javap` on the actual installed jar that the API existed before using it).

### `apm-collector`

There is no `service.name` concept anywhere in this project's span data model — `apm-agent` never
stamps one. `ServiceMapService` derives a service map purely from the one link the instrumentation
actually wires end to end: a `SERVER` span whose `parentSpanId` is a `CLIENT` span's id in the same
trace, joined via SQL directly (`JOIN spans s ON s.parent_span_id = c.span_id`) rather than an
in-memory graph walk. `TraceAssembler` reassembles a trace's parent/child span tree from a flat,
arbitrarily-ordered batch, with two defensive properties worth stating: a span whose parent was
never itself exported (an unsampled ancestor — `Tracer.startSpan` returns `null` for an unsampled
span, which is therefore never itself sent) becomes its own root rather than silently vanishing, and
a cyclic `parentSpanId` chain (malformed or duplicate-id input from a buggy or malicious poster) is
cut off by a visited-id guard rather than recursing forever — proven directly by a constructed
duplicate-spanId test case in `TraceAssemblerTest`, not just asserted. `LatencyService` computes
percentiles via the standard nearest-rank method over the most recent N durations per operation name.

One real test bug (not implementation) was caught in `apm-core`'s `AdaptiveSamplerTest`: a test
measuring the sampler's steady-state rate under high load didn't first drain the token bucket's
initial full burst capacity, so the one-time burst dominated the measured rate. Fixed by adding an
explicit drain step before measurement, mirroring a sibling test's existing pattern.

---

## `castellan-flow` — a BPMN process engine with a Rete rule engine

### Module chain

```
flow-rules       the Rete engine: alpha/beta network, working memory, agenda, from scratch
flow-bpmn         BPMN 2.0 XML parser (StAX) + a token-based process interpreter
flow-engine       durable process/instance/timer repositories, versioning, the BPMN<->Rete bridge
flow-api           Spring Boot REST API, a background timer scheduler
```

### `flow-rules` — Rete from scratch

A genuine Rete network (`AlphaNode`/`AlphaEntry`, `JoinNode`/`Tuple`, `TerminalNode`), not a
decision-table interpreter dressed up as one: facts flow through alpha nodes (single-pattern
filters) into join nodes (multi-pattern correlation via a `BiPredicate<Tuple, Object>`), activating
terminal nodes onto an `Agenda` that resolves conflicts by salience-then-insertion-order. Supports
up to three joined patterns via a typed builder (`RuleBuilder1/2/3`), retraction (removing a fact
retracts every activation it produced), and rule-set versioning mirroring `flow-engine`'s own
process-definition versioning: a session pinned to version N keeps resolving N forever, even after
a newer version deploys.

### `flow-bpmn` — token-based interpretation

`ProcessState` is a fully externalizable record (`variables`, `tokens`, `forkExpectedArrivals`,
`completedActivityIds`, `compensatedActivityIds`) — the entire execution state of a process
instance, serializable to JSON and rehydrated with zero in-memory state left over, which is what
makes `flow-engine` durable across a restart rather than merely persisted-looking.
`ProcessInterpreter` handles exclusive/parallel/inclusive gateways, fork/join via wave-tracking
(`forkId`), and — the one genuinely subtle piece — **race groups** for interrupting boundary timers:
a `userTask` with a boundary timer produces two tokens (one `WAITING_SIGNAL`, one `WAITING_TIMER`)
sharing a `raceGroupId`, and whichever resolves first cancels the other, proven directly by
`BoundaryTimerInterpretationTest` and (at the REST layer) `FlowApiIntegrationTest`'s background-timer
test, which asserts a real 100ms-polling scheduler fires the timer with zero explicit API calls.

### `flow-engine` — the BPMN<->Rete bridge, and durability

`JsonRuleDefinition` lets a `serviceTask` invoke a named rule set declared as JSON over REST rather
than requiring Java code — deliberately scoped to a single pattern (no joins), since a join
predicate is an arbitrary `BiPredicate`, which has no textual form; joined rule sets still go
through `flow-rules`' typed builder directly. See
[ADR 0005](adr/0005-json-rule-sets-single-pattern-only.md). `TimerRepository` maintains a
normalized, indexed projection of every `WAITING_TIMER` token so `TimerScheduler`'s poll is a single
indexed range scan rather than a full deserialize-and-inspect of every instance's JSON state.
`ProcessEngine` re-parses the pinned BPMN XML on every resume rather than caching the parsed graph —
a deliberate simplicity choice (the parse is cheap, and it sidesteps a cache-invalidation story
entirely), not an oversight.

`RestartResumptionTest` is the module's central durability proof: a process instance parked on a
timer is persisted by one set of engine/repository/scheduler objects, then picked up by a
*completely independent*, freshly constructed set pointed at the same on-disk H2 file — proving
nothing about "what happens next" secretly lived in Java heap state rather than in the database.

### `flow-api`

A thin Spring Boot layer: `ProcessEngine`/`RuleSetJdbcRegistry` wired as plain beans (both are
already framework-independent by design), `TimerSchedulerLifecycle` as the trivial
`@PostConstruct`/`@PreDestroy` wrapper `TimerScheduler`'s own docs anticipate. `GlobalExceptionHandler`
maps `BpmnParseException` (malformed submitted XML) to 400, `BpmnExecutionException` (a
structurally-valid request the process graph's current state can't accept — wrong token, no
matching join wave) to 422, and `NoSuchProcessInstanceException` to 404 — a deliberately specific
mapping so a client can branch on status code alone, matching `ledger-api`'s own
`GlobalExceptionHandler` convention.
