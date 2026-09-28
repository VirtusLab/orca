# Capabilities and compile-time checking

```{note}
This feature is experimental. The runtime checks described here are always on;
the compile-time part is opt-in. Scripts without the opt-in imports compile
and run identically.
```

Orca gates side effects behind three capability tokens. The bodies of
`stage(...)` and `flow(...)` provide them, and you never construct one
yourself. Calling a gated function without its token is a compile error, and
the error message says where the call belongs.

The capabilities come in two kinds. A *shared* capability may be captured by a
[fork](stages.md#parallel-work); an *exclusive* one must stay on the flow
thread.

| Capability | Kind | Gates | Provided by | Misuse caught by |
|---|---|---|---|---|
| `InStage` | shared (`caps.SharedCapability`) | LLM runs: `agent.*.run`, `session.run` | `stage(...)` bodies | missing-given compile error |
| `WorkspaceWrite` | exclusive (`caps.ExclusiveCapability`) | git and `gh` writes, `fs.write`, progress-log writes | `stage(...)` bodies | missing-given compile error; using it from a fork fails at runtime |
| `FlowControl` | exclusive (`caps.ExclusiveCapability`) | starting stages, creating [sessions](talking-to-agents.md) | the `flow(...)` body, not forks | missing-given compile error; using it from a fork fails at runtime |

`FlowContext` gives you reads and `display`. It is not a capability: it is
thread-safe, and forks may use it. A helper that starts stages therefore takes
`(using FlowContext, FlowControl)`.

The runtime checks are always on: a fork that calls `stage(...)` or
`session(...)`, or that writes to the workspace, fails at once; a second
`flow(...)` in the same working tree is refused; and an agent used after its
flow has ended throws.

## Compile-time checking

Shared and exclusive are terms from
[capture checking](https://docs.scala-lang.org/scala3/reference/experimental/cc.html).
Two things are checked at compile time:

- **Inside the library.** Orca's own parallel code, the reviewer fan-out, is
  compiled under capture and separation checking. A change that captured a
  `WorkspaceWrite` into that fan-out would not compile, and a compile-time
  test suite pins this.
- **Opt-in, in your script.** Add two language imports to have the compiler
  check your code too. Today this checks, for example, that a custom
  [`ReviewerSelector`](review.md#selecting-reviewers-per-round)'s per-round
  function stays pure:

  ```scala
  import language.experimental.captureChecking
  import language.experimental.separationChecking
  ```

```{note}
Scripts will get compile-time fork checks once
[Ox](https://ox.softwaremill.com/) adopts capture checking. Until then, the
runtime check covers forks. See
[ADR 0018](https://github.com/VirtusLab/orca/blob/master/adr/0018-stage-bound-flow-runtime.md).
```
