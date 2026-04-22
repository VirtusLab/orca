# 0001. Implement Orca in direct-style Scala 3 on Ox

Status: Accepted · Date: 2026-04-22

## Decision

Orca is a Scala 3 library targeting JDK 21, using
[Ox](https://ox.softwaremill.com/) for structured concurrency and virtual
threads. All code is direct-style — no `Future`, no tagless-final effect
monads — with `supervised { ... }` / `fork` for parallelism.

## Why

- Flow scripts read top-to-bottom like shell scripts; no monadic ceremony to
  sequence steps or thread an effect type through the DSL.
- Virtual threads make blocking subprocess / CLI calls cheap, so shelling out
  to `claude`, `git`, and `gh` doesn't need a separate async abstraction.
- Ox's `supervised` scope gives us structured cancellation and resource
  cleanup without a framework.

## Consequences

- Errors propagate as exceptions; recoverable failures use `Either` where
  idiomatic (see ADR 0003 on the corrective-retry loop).
- Every backend is blocking code. A streaming LLM response is pulled by a
  foreground virtual thread.
- Libraries that *require* `Future`/`IO` (some Tapir interpreters, most
  reactive HTTP clients) are off-limits. We use the direct-style
  (`.handle*`) Tapir family and synchronous sttp variants instead.
