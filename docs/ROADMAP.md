# Roadmap

This file is kept current, not aspirational — every item below is either a permanent, deliberate
scope boundary (stated as such) or genuine follow-up work not yet done. Nothing here is hidden
behind a confident-sounding class name; the same statement lives in the relevant class's own
javadoc, which is the authoritative source. This file exists so the boundaries are visible in one
place without reading twenty modules' worth of comments.

## Deliberate, permanent scope boundaries

These are design decisions, not gaps waiting on effort — each has an ADR or an in-code doc
explaining the reasoning, and "finishing" them would mean building a different, larger system.

- **`broker-raft`**: no cluster membership changes (§6's joint consensus) and no log snapshotting
  (§7) — a cluster's membership is static for its lifetime, and the Raft log itself is retained in
  full rather than compacted. Both are real, substantial features the Raft paper spends whole
  sections on; neither was in scope here.
- **`broker-server`'s consumer-group protocol**: one round trip (`JoinGroup`) rather than Kafka's
  two-phase `JoinGroup`+`SyncGroup` handshake — the coordinator computes assignment itself instead
  of electing a client-side "group leader" to compute and distribute it. Loses pluggable
  assignment strategies; gains "a rebalance completes in one request instead of needing every
  member to complete two phases in lockstep."
- **`CommandApplier`'s idempotent-producer dedup**: remembers only the single latest
  `(producerId, sequence)` per partition, not a window of recent ones (Kafka keeps 5). A very-stale
  retry still isn't double-appended, but it gets back the latest offset rather than its own
  original one.
- **`apm-agent`'s `HttpUrlConnectionInstrumentationRule`**: only `java.net.HttpURLConnection` (and
  `HttpsURLConnection`, which extends it) is instrumented. `java.net.http.HttpClient` (JDK 11+) is
  explicitly not — its real implementation is a JDK-internal class this class-body-weaving strategy
  cannot reach without call-site rewriting at every caller, a different and larger project.
- **`apm-agent`'s `JdbcInstrumentationRule`**: captures the SQL statement text (`db.statement`) but
  never bind parameter values — a stated privacy boundary (bind values routinely carry PII), not an
  oversight.
- **`apm-agent`'s `SpringMvcInstrumentationRule`**: inbound trace continuation only works for a
  handler that takes an explicit `HttpServletRequest` parameter, and the mapping's actual route
  (`/orders/{id}`) is never read — the span name is `SimpleClassName.methodName`. Real inbound
  propagation for the common case would mean intercepting `DispatcherServlet`/`HandlerAdapter`
  instead, a different instrumentation target.
- **`apm-collector`'s service map**: derived from span *operation names*, not a per-process
  `service.name` identity — there is no such concept anywhere in this project's tracing data model.
  An honest simplification given what `apm-agent` actually stamps on a span, not a hidden gap.
- **`flow-engine`'s JSON-declared rule sets**: exactly one pattern, no joins — a join predicate is
  arbitrary Java (`BiPredicate<Tuple, Object>`), which has no textual/JSON form. Joined rule sets
  are built directly against `flow-rules`' typed Java builder and deployed as compiled code instead.
- **`ledger-infrastructure`'s outbox publisher**: `LoggingOutboxPublisher` is a stated stand-in for
  a real message bus (Kafka, SQS, ...) — the outbox *pattern* (transactional write + independently
  retryable relay) is real and fully implemented; the actual downstream transport is not this
  project's concern.

## Genuine follow-up work (not done, not permanently cut)

- **`broker-client`**: no batching/pipelining — every `Producer.send` blocks on its own Raft
  commit round trip; a real high-throughput client would batch multiple records per request.
- **`apm-collector`**: no retention/aggregation of raw spans — every span ingested is kept forever
  in a single `spans` table; a long-running collector would need the same kind of retention
  `broker-storage`'s `PartitionLog` already has for produced records.
- **`flow-engine`**: parsed BPMN XML is re-parsed on every instance resume rather than cached
  (deliberately, for now — see `ProcessEngine`'s own docs) — a real high-throughput deployment
  would want to cache parsed, immutable definitions keyed by `(processId, version)`.
- **`ledger-api`**: no pagination on list endpoints (`AuditController`, transaction history) — fine
  at this project's test scale, a real gap at production data volumes.
- **Cross-system integration**: the four systems are independently complete and independently
  tested, but nothing wires them together (e.g. the ledger publishing domain events to the broker
  instead of `LoggingOutboxPublisher`, or `flow-engine` service tasks calling the ledger's API). Each
  is a real, standalone system; connecting them was never the brief.

## Real bugs this project's own tests caught, and how they were fixed

Kept here as evidence the test suites are load-bearing, not decorative — every one of these was
found by running tests, not by inspection, and every fix is a real behavioral change, not a test
adjustment to hide the symptom (except the two explicitly marked as test-only bugs below).

1. **Raft commit-visibility lag** (`broker-raft`): a follower only learned of an advanced
   `commitIndex` on the leader's *next* heartbeat. `advanceCommitIndexIfPossible` now returns
   whether it advanced, and the leader broadcasts a fresh round to every peer immediately when it
   does. Caught by `RaftClusterSimulationTest`.
2. **Shutdown-ordering bug** (`broker-server`): `RaftEventLoop.close()`'s `executor.shutdownNow()`
   could interrupt a thread mid-write to the partition log; `FileChannel` (an `InterruptibleChannel`)
   silently closes itself on interrupt, so a later flush during shutdown failed with
   `ClosedChannelException`. Fixed with graceful-then-forceful executor shutdown. Caught by
   `broker-client`'s `ProducerTest`, not `broker-server`'s own suite.
3. **ByteBuddy retransformation, two-part bug** (`apm-agent`): a test's redundant manual
   `retransformClasses()` call after `AgentBuilder.installOn()` (which already retransforms
   automatically) double-retransformed an already-woven class; once removed, the *automatic*
   retransform was found to be silently failing on its own because default Byte Buddy Advice-weaving
   structurally adds a method, which HotSpot forbids on retransform. Fixed by removing the redundant
   call and adding `.disableClassFormatChanges()` to the `AgentBuilder` chain.
4. **`AdaptiveSamplerTest` measurement bug** (`apm-core`, test-only): a steady-state-rate assertion
   didn't drain the sampler's initial token-bucket burst before measuring, so the burst dominated the
   measured rate. Fixed by draining first, matching a sibling test's existing pattern.
5. **Test fixture/assertion bugs** (`ledger-application`'s `PostTransactionHandlerTest`, test-only):
   a test helper posted a debit through the real command handler instead of seeding the event store
   directly, tripping the very insufficient-funds check it was meant to bypass as setup; a separate
   assertion expected zero `TransactionPosted` events where a legitimate seed transaction existed.
   Both fixed in the test, not the handler.
