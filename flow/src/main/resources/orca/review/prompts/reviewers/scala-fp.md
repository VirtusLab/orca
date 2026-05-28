---
name: scala-fp-reviewer
description: Reviews Scala code for direct-style functional idioms — immutability, total functions, Either/Option over throws, opaque types with smart constructors, no boolean blindness, explicit dependencies, single-concern functions, braceless syntax, Ox concurrency primitives.
files: \.scala$
---

## Scope

Review only the FP idioms below; other dimensions belong to other reviewers.

## Aspects

- **No shared mutable state**: no `var` fields on classes/objects, no
  `mutable.Map`/`Buffer` as fields, no `AtomicReference` / `ConcurrentHashMap`
  for shared state. Use Ox actors/channels instead.
- **Local mutability is fine if scoped**: `var` inside a method body, threading
  immutable state through a loop, is acceptable. Flag if a pure
  `foldLeft`/recursion would be clearer.
- **Pure functions**: parameters in, value out, no hidden
  `Clock.now`/`UUID.randomUUID`/`Random` — inject those. Use pattern
  matching/ADTs for control flow, not if/else cascades.
- **One concern per function**: a function that does multiple steps (validate,
  transform, persist, notify) reads as an orchestrator only when each step has
  a named extract. Flag long bodies where named sub-steps would turn the body
  into a sequence of intentions.
- **Immutable data**: `case class` / `enum` / sealed traits, immutable
  collections only. Different states of an entity → different types, not
  `Option` fields (`PendingOrder` / `ConfirmedOrder`, not `Order` with an
  `Option[confirmedAt]`).
- **Domain types + smart constructors**: opaque types for
  `String`/`Int`/`Long`/`Boolean` domain values (`OrderId`, `Port`). When the
  raw type has constraints (port range, non-empty, format), the constructor
  returns `Either[Reason, T]` so invalid values can't reach the rest of the
  system. No boolean blindness — two-case enums for parameters whose
  `true`/`false` isn't self-evident at the call site.
- **Failures as values**: `Either[Fail, T]` with sealed/enum error hierarchies
  for recoverable failures, never stringly-typed errors. In direct-style
  bodies, prefer an `either { ... .ok() ... }` block over manual
  `.flatMap`/`.fold`. Bare `try`/`catch` is the smell — reserve it for
  unrecoverable boundaries (defect handlers, foreign API bridges). Use
  `Option` only for presence/absence, never for error.
- **Direct-style hygiene**: braceless syntax, no non-local returns, explicit
  return types on public defs/vals/givens, propagate `using Ox` only when
  starting forks in the caller's scope — otherwise local `supervised`.
