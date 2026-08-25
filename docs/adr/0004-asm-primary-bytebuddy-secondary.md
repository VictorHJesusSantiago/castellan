# ADR 4 — ASM as the primary weaver, Byte Buddy as a secondary comparison

## Context

`castellan-apm` needs to instrument JDBC, outbound HTTP, and Spring MVC handlers with no source
changes required — the brief explicitly calls for `java.lang.instrument` + `ASM`/`ByteBuddy`,
naming both.

## Decision

`apm-agent`'s default, and only complete, instrumentation path is hand-written ASM
(`CastellanClassFileTransformer`, `ProbeClassVisitor` for a cheap first pass, `SpanWeavingClassVisitor`/
`SpanWeavingMethodVisitor` for the actual bytecode rewrite) covering all three targets: JDBC,
outbound `HttpURLConnection`, and Spring MVC. A second transformer,
`bytebuddy.ByteBuddyJdbcTransformer`, covers **JDBC only**, selectable via the agent's
`transformer=bytebuddy` argument, existing specifically to be a second, independent implementation
of the same instrumentation point for direct comparison — not a partial substitute for the ASM path.

## Why

Building both, at different scope, demonstrates the actual engineering tradeoff between them rather
than picking one and citing the other in a comment. ASM operates at the raw bytecode-instruction
level — every `NEW`/`DUP`/`INVOKESPECIAL` for the try/finally scaffolding is hand-emitted, giving
full control (and full responsibility) over stack map frames, exception table entries, and local
variable slots. Byte Buddy's `Advice` API is declarative — `@Advice.OnMethodEnter`/`@Advice.OnMethodExit`
methods whose bytecode gets inlined for you — trading that control for far less code, at the cost of
exactly the kind of surprise this project's own bug list documents: default Advice-weaving can
structurally add a method, which HotSpot's retransformation forbids outright, a constraint that only
became visible by actually running both paths under real retransformation rather than reading either
library's documentation.

Scoping the Byte Buddy path to JDBC only (not re-implementing all three targets a second time) is
itself the honest choice — the goal was a real, working comparison of the two *techniques*, not
duplicate, half-maintained coverage of the same three instrumentation points forever.

## Rejected alternative

Byte Buddy as the primary (and only) weaver, with ASM cited only in documentation as "the technique
Byte Buddy is built on." Rejected because the brief names both explicitly, and because building only
the higher-level API would have skipped the actual bytecode-generation work — hand-emitting a
correct try/finally span-wrapping sequence with valid stack map frames is exactly the kind of
low-level systems work this project's flagship modules (`broker-raft`, `flow-rules`) are built to
demonstrate elsewhere; `apm-agent`'s ASM path is the same discipline applied to bytecode instead of
a consensus algorithm or a rule engine.
