# ADR 5 — JSON-declared rule sets support one pattern; joins require the Java builder

## Context

`flow-rules` is a genuine Rete engine supporting up to three joined patterns per rule, via a typed
builder (`RuleBuilder1`/`RuleBuilder2`/`RuleBuilder3`) whose join test is a real
`BiPredicate<Tuple, Object>`. `flow-engine` needs a `serviceTask` in a BPMN process to be able to
invoke a rule set declared over REST (`POST /rule-sets`), as data, without requiring a JVM
redeploy — `JsonRuleDefinition` is that data shape: `condition` and `outcomeExpressions` are plain
strings, evaluated by `ExpressionEvaluator` against a single fact (the process's variables as one
`Map<String, Object>`).

## Decision

A `JsonRuleDefinition` supports exactly one pattern — one `condition` expression evaluated against
one fact. There is no JSON representation for a second, joined pattern. A rule set that genuinely
needs a join (correlating two separate facts, e.g. "an order and a customer whose id matches") must
be built directly in Java against `flow-rules`' typed builder and deployed as compiled code, not
through the JSON/REST path.

## Why

A join predicate in `flow-rules` is a `BiPredicate<Tuple, Object>` — arbitrary Java code with access
to two full fact objects. There is no meaningful textual or JSON encoding of "arbitrary Java
predicate" short of embedding a second expression language capable of expressing multi-fact
correlation (something like a real Drools DRL join syntax), which is a substantially larger
undertaking than the JSON rule format this project actually needed: BPMN service tasks in practice
overwhelmingly evaluate conditions against the *current process instance's own variables* — a
single fact — not correlations across independently-inserted facts. Building the single-pattern
case as real, working, tested REST-deployable data, and stating plainly that the multi-pattern case
routes through the existing Java API instead, is more honest than either skipping JSON rule sets
entirely or half-implementing a join-capable expression language that wouldn't actually be safe or
complete.

## Rejected alternative

Extending `ExpressionEvaluator` with its own join syntax (e.g. a second `Map<String,Object>` fact
parameter and a correlation expression referencing both). Rejected: `ExpressionEvaluator` is a small,
deliberately narrow boolean/comparison expression subset (see its own docs), and growing it into a
second rule language capable of real multi-fact correlation would mean re-deriving a meaningful slice
of what `flow-rules`' typed Java API already does correctly, just in string form — worse in every
respect (no compile-time checking, a hand-rolled parser to maintain, a second place join semantics
could disagree with the Java path) for a capability the JSON path doesn't actually need to cover.
