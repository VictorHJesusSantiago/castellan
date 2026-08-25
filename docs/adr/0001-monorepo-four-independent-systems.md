# ADR 1 — One monorepo, four independent systems

## Context

The brief asked for four genuinely large, unrelated Java systems: an event-sourced core banking
ledger, a distributed log broker with hand-written Raft, a bytecode-instrumenting APM agent, and a
BPMN process engine with a Rete rule engine. Each is a flagship-scale project on its own.

## Decision

One Maven reactor, one root `pom.xml` for shared `dependencyManagement` (Spring Boot BOM, JUnit,
AssertJ, ASM, Byte Buddy, H2, Flyway, Jackson), four aggregator POMs (`castellan-ledger`,
`castellan-broker`, `castellan-apm`, `castellan-flow`) that **share build conventions but never
share code**. No module in one aggregator depends on a module in another.

## Why

Sharing infrastructure (dependency version management, compiler release level, surefire config)
costs nothing and keeps four different pieces of engineering looking like one coherent effort
rather than four unrelated snippets glued together. Sharing *code* between them would have been a
false economy: `ledger-domain`'s `Money`/`Posting` types and `broker-protocol`'s wire records are
both "small immutable value records," but forcing a shared abstraction over them would mean
designing for a hypothetical future need neither actually has. The four systems are genuinely
independent products; the monorepo captures that they were built together under one set of
conventions, not that they are one system.

## Rejected alternative

Four separate repositories. Rejected because building all four to the same depth under the same
house style (real design tradeoffs stated in javadoc, no framework doing the load-bearing work
unless the module genuinely needs one, every layer tested with real dependencies rather than mocks)
is easier to keep consistent from one root than to re-derive four times.
