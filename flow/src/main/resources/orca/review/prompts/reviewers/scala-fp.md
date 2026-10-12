---
name: scala-fp-reviewer
description: Reviews Scala code for direct-style functional idioms — immutability, total functions, Either/Option over throws and nulls, opaque types with smart constructors, no boolean blindness, explicit dependencies, single-concern functions, braceless syntax, Ox concurrency primitives and scope ownership.
files: \.(scala|sc)$
---

## Scope

Review only the FP idioms below; other dimensions belong to other reviewers. If
the `direct-style-scala` skill is available, load it first and use it as the
fuller statement of these aspects only — its tooling, testing, visibility, and
performance rules belong to other reviewers.

## Aspects

- **No shared mutable state**: no `var` fields on classes/objects, no
  `mutable.Map`/`Buffer` as fields. Mutable state lives in an Ox actor, or in
  an `AtomicReference` over one immutable value updated with
  `updateAndGet`/`getAndUpdate` — never `get` then `set`, never as a lifecycle
  or shutdown flag. Mutable collections in test helpers that simulate external
  systems are fine.
- **Local mutability is fine if scoped**: `var` inside a method body, threading
  immutable state through a loop, is acceptable.
- **Pure functions**: parameters in, value out, no hidden
  `Clock.now`/`UUID.randomUUID`/`Random` — inject those. Use pattern
  matching/ADTs for control flow, not if/else cascades.
- **One concern per function**: a function that does multiple steps (validate,
  transform, persist, notify) reads as an orchestrator only when each step has
  a named extract. Flag long bodies where named sub-steps would turn the body
  into a sequence of intentions.
- **Immutable data**: `case class` / `enum` / sealed traits, immutable
  collections only. Different states of an entity → different types, not
  `Option` or `Boolean` fields whose combinations include impossible states
  (`PendingOrder` / `ConfirmedOrder`, not `Order` with an
  `Option[confirmedAt]`).
- **Domain types + smart constructors**: opaque types for
  `String`/`Int`/`Long`/`Boolean` domain values (`OrderId`, `Port`). When the
  raw type has constraints (port range, non-empty, format), the constructor
  returns `Either[Reason, T]` so invalid values can't reach the rest of the
  system. No boolean blindness — two-case enums for parameters whose
  `true`/`false` isn't self-evident at the call site.
- **Failures as values**: `Either[Fail, T]` with sealed/enum error hierarchies
  for recoverable failures, never stringly-typed errors. Exceptions from a
  foreign API are converted at the boundary with `.catching[SpecificException]`;
  bare `try`/`catch` only at defect boundaries. Absence is `Option` — never
  `null` or a sentinel value — and `Option` is never an error.
- **Direct-style hygiene**: braceless syntax, no non-local returns, explicit
  return types on public defs/vals/givens. Take `using Ox` only when starting
  forks in the caller's scope, `using ResourceScope` when only registering
  resources; otherwise a local `supervised` (or `resourceScope` when there is
  no concurrency).
- **Ox concurrency**: no raw `Thread`, `LinkedBlockingQueue`, `synchronized` or
  `Lock` where Ox has a primitive; prefer a `Flow` (`mapPar`, `merge`,
  `mapStateful`) over hand-wired forks and channels. Never return an object
  that owns running forks to be driven later; a fork's result is its return
  value, not a value published through a shared reference.
