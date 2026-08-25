# ADR 2 — Raft implemented from the paper's Figure 2, not a library

## Context

`castellan-broker` needs replicated, consistent state across nodes: which records a partition
actually contains, in what order, durable across a leader failure. Real, off-the-shelf options
exist (etcd's `raft` package has no direct Java port, but Atomix, JGroups-RAFT, and several smaller
Java Raft libraries do).

## Decision

`broker-raft` implements the Raft paper's Figure 2 directly: `RaftNode` as a pure, deterministic
state machine — every inbound event (`handleRequestVote`, `handleAppendEntries`,
`handleAppendEntriesResponse`, `handleElectionTimeout`, `handleHeartbeatTimeout`, `proposeCommand`)
takes the event as input and returns a `HandleResult` (outbound envelopes + a timer-reset flag),
with all I/O and timers pushed to a separate imperative shell (`broker-server`'s `RaftEventLoop`).

## Why

The brief explicitly called for "replicação com Raft implementado por você" (Raft replication
*implemented by you*) — using a library would not satisfy that requirement, and more importantly
would skip the actual engineering: correctly implementing the paper's safety argument (§5.4.2 — a
leader may only commit an entry from its own term directly, never a prior-term entry by count
alone) is precisely the hard, easy-to-get-subtly-wrong part a library exists to hide. Building it
from scratch, then proving it via `RaftClusterSimulationTest` driving a real 3-node cluster to
leader election, log replication, and a caught real commit-visibility-lag bug, is the actual
deliverable — not a wrapper around a battle-tested implementation.

The pure-state-machine-plus-imperative-shell split (rather than, say, an actor with internal
threads and timers) is itself deliberate: it is what makes `RaftNode` testable without a network, a
clock, or a thread — `RaftClusterSimulationTest` feeds `HandleResult`s between three `RaftNode`
instances directly, no `Thread.sleep` anywhere, and gets deterministic, reproducible test runs as a
direct consequence.

## Rejected alternative

An existing Java Raft library (Atomix, JGroups-RAFT). Rejected per the brief's explicit
requirement, and because it would have made `broker-raft` the one module in this project not
actually demonstrating the skill the whole broker system exists to showcase.
