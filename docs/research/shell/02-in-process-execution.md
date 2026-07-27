> Final. Skeptic verdict (§S6): subprocess UPHELD, amended — the shell runs
> flows as tty-inheriting `scala-cli run` children, forcing its own orca
> version via `--dep` (verified to override the script's pin), with
> pin-honouring + a degradation notice as the compile-failure fallback. The
> ADR overrides the requirement's literal "in-process" wording on this
> recorded case.

# 02 — In-process flow execution

Topic 2 of the [research plan](00-research-plan.md): how the Orca Shell runs a
selected `.sc` flow script "in-process", and whether in-process is even the
right call.

**Recommendation (up front): it is not.** Run the flow as a `scala-cli run`
child process that inherits the shell's terminal, and re-scope "in-process" to
"launched by, and seamlessly integrated with, the shell — same terminal, no
visible seam". A workable in-process design exists (§5) and is documented in
full in case the ADR keeps the requirement, but every one of its costs —
version forcing, `System.exit` containment, terminal handover, global-state
cleanup, classloader dedupe — is bought to avoid a ~1–2 s child-JVM startup on
flows that run for minutes. Details and the honest cost/benefit list in §6.

---

## 1. Codebase facts

### 1.1 The compiler is already a dependency — but Test-only

`build.sbt` gives the `flow` module `scala3Compiler`, defined in
`project/Dependencies.scala` as:

```scala
val scala3Compiler = "org.scala-lang" %% "scala3-compiler" % V.scala % Test
```

It is **Test scope**, pinned to `V.scala = 3.8.4`, and exists solely for the
capture-checking negative-compile suite
(`flow/src/test/scala/orca/CcNegativeCompileTest.scala`). A shell module that
wanted embedded compilation would add it as a Compile dependency (~35 MB of
compiler artifacts on the shell's runtime classpath).

### 1.2 How the CC suite runs dotc in-process

`CcNegativeCompileTest` is a working, in-repo demonstration of embedded
compilation:

- It calls `dotty.tools.dotc.Main.process(args, reporter)` directly, with a
  custom `Reporter` subclass collecting diagnostics (the driver API's supported
  inspection point — no stdout parsing).
- The classpath problem is solved at build time: flow's tests are not forked,
  so `java.class.path` is only sbt's launcher classpath. `build.sbt` therefore
  materialises `Test / dependencyClasspath` into a generated resource
  `cc-test-classpath.txt`, which the suite reads and passes as `-classpath`.
- Each fixture compile writes to a temp `-d` output dir; the classpath string
  is assembled once and reused.

So "compile Scala source in-process against an explicit classpath" is proven
tech in this repo. What the suite does **not** do: parse `//> using`
directives, resolve dependencies, wrap `.sc` scripts (its fixtures are plain
`.scala` sources), or **load and execute** the compiled output. Reuse
potential is real but partial.

### 1.3 `flow()` vs `runFlow()`

`runner/src/main/scala/orca/flow.scala`:

- `flow(...)` (line 108) is the script-facing entry point. On any failure it
  sets `failed = true` and ends with `if failed then System.exit(1)`
  (line 192). An in-process invocation of an unmodified script therefore
  **kills the host JVM** on flow failure. The comment at line 187 already
  documents the nested-`flow()` variant of this hazard.
- `private[orca] runFlow(...)` (line 210) is the exit-free lifecycle:
  failures propagate as `SurfacedFlowFailure` (or unwrapped, pre-dispatcher).
  It exists for testability and is exactly the entry point an in-process shell
  would need — but a third-party script calls `flow()`, not `runFlow()`, and
  `runFlow` is `private[orca]`. The shell can only reach the exit-free path if
  the `orca` classes the script links against are the **shell's own** (§4).
- `runFlow` also enforces `FlowLock.acquireProcess()` — a process-wide
  "one flow per process" guard — plus global logback mutation via
  `OrcaLog.start()` and a default uncaught-exception handler. Consecutive
  in-process runs depend on topic 1's global-state audit (`01-global-state.md`).

### 1.4 How tests "compile example flows" today

They don't compile the `.sc` files at all.
`runner/src/test/scala/flowtests/FlowCompilesTest.scala` is a **canary**: a
plain Scala test file outside the `orca` package that mirrors each example's
shape as a `def` (e.g. `implementFlowShape()` mirrors `implement.sc`) using
only `import orca.{*, given}`. Nothing runs; `sbt test` only requires it to
typecheck. The actual `examples/*.sc` files are never compiled by the build —
they are exercised manually via `examples/runnable/*/create-test-project.sh`,
which seeds a temp project and prints a `scala-cli run` command. So there is
**no existing harness that turns a `.sc` + its directives into loaded classes**;
whatever the shell does here is new machinery.

### 1.5 Example script anatomy

`examples/implement.sc` starts:

```scala
//> using scala 3.8.4
//> using dep "org.virtuslab::orca:0.0.17"
//> using jvm 21
```

and its entry is top-level `flow(OrcaArgs(args)): ...` — it relies on
scala-cli's script wrapping (`args` in scope, top-level statements become the
main body, top-level `case class ... derives JsonData` nested inside the
wrapper object). The root build's `updateDocs` task rewrites both the orca
version and the `//> using scala` pin in `README.md`, `AGENTS.md` and
`examples/` on release, so in-repo scripts always match `V.scala` — but
user-authored flows in the wild can pin anything.

---

## 2. Options for executing a `.sc` in-process

### 2a. Embedded dotc + coursier + hand-rolled directive handling

Build the whole pipeline inside the shell:

1. **Parse directives.** Two library options exist, both from VirtusLab:
   - `org.virtuslab:using_directives:1.1.4` — the original pure-Java parser
     (last release Dec 2024; Maven Central confirms 13 versions).
   - `org.virtuslab.scala-cli:directives-parser_3:1.15.0` — a Scala-rewritten
     standalone parser module, added by
     [scala-cli PR #4192](https://github.com/VirtusLab/scala-cli/pull/4192) in
     response to the tooling-summit request
     [issue #2443](https://github.com/VirtusLab/scala-cli/issues/2443)
     ("standalone using directives library", from the Metals lead). Published
     to Maven Central alongside scala-cli releases (verified: latest 1.15.0).
     This one is *intended* for third-party reuse — the strongest piece of the
     embedded story.
2. **Resolve deps with coursier.** The sanctioned embedding route is
   `io.get-coursier:interface` (verified latest `1.0.29-M4`) — a stable,
   dependency-shaded **Java** API precisely so applications can embed coursier
   without version conflicts; the richer Scala API
   (`io.get-coursier::coursier`, verified latest `2.1.25-M26`) is published
   **for Scala 2.13 only** (no `coursier_3` on Central), usable from Scala 3
   only via `.cross(CrossVersion.for3Use2_13)` with the usual eviction
   headaches. Docs: <https://get-coursier.io/docs/api>.
3. **Wrap the script.** dotc does not compile scala-cli-style `.sc` files with
   scala-cli's semantics; the shell would re-implement the wrapping (strip
   directives, synthesise `object <name>_sc { def main(args: Array[String]) }`
   around the statements, keep top-level type definitions nested). This is the
   drift-prone part: wrapping rules are scala-cli internals with edge cases
   (multiple top-level defs, `@main`, exports), and a silent divergence means
   "runs under the shell but not under `scala-cli run`" or vice versa.
4. **Compile with `dotty.tools.dotc.Main`** (per §1.2) against
   shell-classpath + resolved deps, then load via `URLClassLoader` and invoke
   the wrapper's `main` reflectively.

`//> using scala` mismatches: the embedded compiler is the shell's own 3.8.4,
so the script's pin is silently ignored — acceptable for in-repo flows (kept
in sync by `updateDocs`), a lie for user flows pinning anything else.
`//> using jvm` is likewise ignored (the shell's JVM is what runs).

Verdict: every ingredient exists as a library, but step 3 is re-implementing
scala-cli, and steps 1–2 duplicate what a locally installed scala-cli already
does better (with caching). High maintenance, medium risk.

### 2b. scala-cli as a library

scala-cli **is** published to Maven Central under `org.virtuslab.scala-cli`
(verified via Central: `cli_3`, `build-module_3`, `core_3`, `options_3`,
`directives_3`, … — latest 1.15.0; the Central search index also shows ~57
artifacts). It is a Scala 3 application, so binary compatibility with the
shell's Scala 3.8.4 is not the blocker. The blockers:

- **No public/supported embedding API.** Neither the docs site
  (<https://scala-cli.virtuslab.org/>) nor the repo documents programmatic
  use; the published modules exist because scala-cli's Mill build publishes
  everything, not as a contract. `scala.build.Build.build(...)` and friends
  are internals with no compatibility promise; the artifacts routinely churn
  between minors (the module set itself changed across 1.x).
- **Dependency weight.** `build-module_3` transitively drags coursier,
  bloop-rifle, the config/options stack — effectively the whole tool minus
  the launcher — into the shell's classpath, where it can conflict with the
  shell's own deps (coursier, jsoniter, etc.).
- The one deliberately-extracted, reuse-intended piece is exactly
  `directives-parser` (§2a.1) — its existence as a *separate* answer to
  issue #2443 is itself evidence the rest is not meant to be embedded.

Verdict: not sane as an embedding target. If scala-cli's logic is wanted, use
the scala-cli **binary** (which the shell's own launch story already requires —
topic 4).

### 2c. Child process: `scala-cli run flow.sc` sharing the terminal

Spawn `scala-cli run <flow>.sc -- "<prompt>"` with inherited stdio
(`ProcessBuilder.inheritIO` / os-lib `os.Inherit`). Empirically (local
scala-cli against a demo script), scala-cli compiles with Bloop-backed caching
— an unchanged flow re-run skips compilation — and the scala-cli launcher
itself is a native binary, so per-run overhead is a cached-build check plus one
child-JVM startup.

What is **lost** vs in-process:

- **JVM warmup per flow**: ~1–2 s startup + logback/class-loading before the
  banner. Against flows whose stages are multi-minute agent runs, this is
  noise. (First-ever run of a new flow pays dependency resolution + compile —
  but it pays that under every option; only the cache location differs.)
- **No shared in-memory session registry**: the shell cannot hand an
  `extraListeners` into the script's own `flow(...)` call *in any design* (the
  script constructs its own arguments); in-process this is worked around with
  ambient hooks (§5), out-of-process it needs a **file-based handoff** — e.g.
  a run-manifest under `.orca` that the flow writes session IDs into, which
  topic 8 already contemplates. Note the file is arguably *better* even for
  the shell's own purposes: "continue a session" should survive a shell
  restart, which an in-memory registry cannot.
- No live event stream to the shell (it could tail the manifest/trace file if
  ever needed; today `TerminalInteraction` renders everything to the shared
  terminal anyway, so the shell has nothing extra to render during a run).

What is **gained**:

- **Perfect classpath isolation** — the flow's pinned orca version, scala
  version and `//> using jvm` are all honoured exactly; "runs under the shell"
  is byte-for-byte "runs standalone".
- **`System.exit` containment for free** — `flow()`'s exit-1 contract
  (flow.scala:192) terminates the child, not the shell; the shell reads the
  exit code.
- **Global-state immunity** — `FlowLock`'s one-flow-per-process semantics,
  `OrcaLog`'s global `orca`-logger mutation, the uncaught-exception handler: all scoped
  to the child. Topic 1's "needs cleanup between runs" findings mostly stop
  mattering.
- **No terminal-handover machinery** (§4): the child owns the tty while it
  runs; the shell simply doesn't read until it returns.
- Trivial implementation; failure modes are scala-cli's own, already familiar
  to users.

### 2d. BSP / server modes

`scala-cli bsp` exists for IDE integration (Metals) and `scala-cli compile
--print-class-path` / `scala-cli run --command` expose the same build outputs
over the CLI. BSP would let the shell keep one warm build server and request
compiles + classpaths over the protocol, but adds a protocol client, process
lifecycle management, and still doesn't execute anything — running remains the
shell's problem. Since scala-cli already keeps Bloop warm across plain CLI
invocations, BSP buys latency the CLI path already has. Not worth it.

**The useful CLI-server hybrid** (relevant if in-process is kept): verified
locally,

```
$ scala-cli run --command demo.sc -- x
…/java
-cp
<classes-dir>:<resolved dep jars…>
demo_sc
x
```

`scala-cli run --command` **compiles** (cached) and then prints, one argument
per line, the exact java invocation — classpath and wrapper main class
(`demo_sc` for `demo.sc`) included. This is a supported flag ("Print the
command that would have been run, rather than running it"), and it turns
scala-cli into the shell's build tool without embedding anything: parse the
printed `-cp` and main class, then load/invoke in the shell JVM. It eliminates
§2a's steps 1–3 entirely.

---

## 3. The classpath/version problem

A flow may pin a different orca than the shell runs (shell 0.0.18, script
`//> using dep org.virtuslab::orca:0.0.15`). Three postures:

1. **Isolated classloader, script's own orca.** Child-first `URLClassLoader`
   over the script's full resolved classpath. Version freedom is preserved,
   but: (a) the script's `flow()` — *its* orca's copy — still calls
   `System.exit(1)`, and there is no future-proof in-process interception:
   `SecurityManager` was deprecated for removal by
   [JEP 411](https://openjdk.org/jeps/411) (JDK 17) and permanently disabled
   by [JEP 486](https://openjdk.org/jeps/486) (JDK 24); orca builds with
   `-release 21` but riding a feature that is already dead upstream is not a
   design. (b) The shell's hooks (session-collecting listener, exit-free
   routing, terminal sharing) all live in *shell-orca* classes, which the
   script-orca classes cannot see — crossing the boundary means reflection or
   serialised handoff, i.e. subprocess semantics without subprocess safety.
   (c) Two jsoniter/ox/JLine copies, double static state. Strictly worse than
   a real subprocess on every axis except saving a JVM. **Rejected.**
2. **Force the shell's orca version.** Ignore the script's orca pin: rewrite
   it (in a temp copy of the script) to the shell's version before the
   scala-cli build step, or equivalently compile against the shell's own
   classpath. Version skew then surfaces as a *compile error with a clear
   message* ("this flow targets orca 0.0.15; run it directly with scala-cli,
   or update it") rather than a runtime `LinkageError`. Given the project
   stance that consumers are version-pinned scripts and orca-API
   backward-compat concerns are minor, this is the only coherent in-process
   posture — and it is what makes §5's ambient hooks possible at all, since
   the script now links against the shell's `flow()`. Cost: "the shell runs
   any flow" quietly becomes "the shell runs flows compatible with the shell's
   orca API".
3. **Subprocess.** No problem exists. Pins honoured.

Cross-classloader hook cost, quantified for posture 1 (for the record): every
shared type (`OrcaListener`, `OrcaEvent`, session records) is loaded twice, so
`instanceof`/casts fail across the boundary; the only safe channel is
JDK-types-only (strings/streams) or serialisation — at which point the
`.orca` run-manifest file is the same mechanism with fewer failure modes.

---

## 4. Terminal handover

Shell side: topic 3 points at JLine (already a runner dep, 3.28.0). Flow side:
`TerminalInteraction` (runner/src/main/scala/orca/runner/terminal/) writes
ANSI/cursor-control sequences to stderr from a `TerminalOutput` actor with an
animated status line, and `ConversationRenderer.JLinePrompter` lazily builds
**its own system terminal** — `TerminalBuilder.builder().system(true)
.dumb(true).build()` — with a `LineReader` for `ask_user` prompts, closing it
on interaction close.

In-process, that means two JLine system `Terminal` instances over one tty.
JLine's own guidance is that there is effectively one system terminal per JVM,
and the nested-app problem (sbt hosting a JLine-using REPL) is real enough
that JLine added a global escape hatch specifically for it:
`TerminalBuilder.setTerminalOverride(Terminal)` —
[jline3 PR #554](https://github.com/jline/jline3/pull/554), contributed by
sbt's maintainer because "there is no entry point to the scala REPL that
allows the calling application to pass in a custom terminal" (verified still
present in current `TerminalBuilder`, with a javadoc warning to restrict its
use to exactly this case). sbt's own model is the precedent: one owned
terminal, explicitly *handed over* to the nested console/run and reclaimed
after, with raw-mode attributes restored at each boundary; historical issues
("running console multiple times messes up JLine",
[sbt #4054](https://github.com/sbt/sbt/pull/4054)) show what partial handover
looks like.

Concretely, an in-process shell must, per run: stop reading from its
`LineReader`, restore cooked attributes, either close its terminal or install
it via `setTerminalOverride` so `JLinePrompter` adopts it instead of building
a second one, let `TerminalOutput`'s animator own the status line, and on
completion clear the override, re-assert attributes and redraw its menu.
Signal handling (Ctrl-C during an agent run vs at the shell prompt) has to be
re-partitioned at the same boundary. All feasible — sbt does it — and all
gone in the subprocess model, where the child inherits the real tty, JLine in
the child sees a normal console, and the shell just blocks on `waitFor()`
without touching the terminal (its reader restores attributes between
`readLine` calls already).

---

## 5. The workable in-process design (if the requirement stands)

For completeness — the best-of-breed composition if the ADR insists on
in-process execution:

1. **Build via scala-cli, not embedded dotc.** Copy the script to a scratch
   file with its orca pin rewritten to the shell's version (§3.2); run
   `scala-cli run --command <copy>.sc` (§2d) to get a cached compile plus the
   printed classpath and main class. No directive parsing, no script-wrapping
   re-implementation, no coursier embedding; scala-cli remains the single
   source of build semantics. (The `directives-parser_3` library stays
   relevant for topic 5's lightweight description/directive sniffing only.)
2. **Load parent-first.** `new URLClassLoader(printedCp, shellClassLoader)`
   — parent-first delegation means the shell's orca/ox/JLine/jsoniter win and
   the (now same-version) duplicates on the child path are shadowed; only the
   script's wrapper classes and any *extra* deps it added actually load from
   the child. Invoke `<name>_sc.main(args)` reflectively.
3. **Ambient shell hooks in orca.** Because the script now links against the
   shell's `flow()`, add a `private[orca]` shell bridge (settable hook object)
   that `flow()` consults: (a) route failure to throw instead of
   `System.exit(1)` — i.e. surface `runFlow`'s exit-free behaviour
   (flow.scala:210) without the script asking; (b) append shell listeners
   (session-ID collection for topic 8) to `extraListeners`; (c) supply the
   shared JLine terminal/prompter (§4). The hook is set/cleared by the shell
   around each run; direct `scala-cli run` never sets it, so the standalone
   contract is untouched.
4. **Global-state cleanup between runs** per topic 1's audit (logback
   appender teardown is already per-run via `orcaLog.finish()`; FlowLock
   releases in `finally`; the uncaught handler is install-once by design).

This is genuinely buildable. It is also four coupled mechanisms (script
rewriting, classloader discipline, runtime hooks, terminal handover) plus a
standing dependency on topic 1's cleanliness guarantees — all replacing one
`os.proc(...).call(stdin = Inherit, stdout = Inherit, stderr = Inherit)`.

## 6. Weighing and recommendation

| | in-process (§5 hybrid) | subprocess `scala-cli run` (§2c) |
|---|---|---|
| Honours script's orca/scala/jvm pins | no — forced to shell's (compile-error on skew) | yes, exactly *[amended — forced by default, §S4]* |
| `System.exit` in `flow()` | needs orca hook (§5.3) | contained free |
| Session handoff to shell | in-memory listener (plus file if restart-survivable) | `.orca` run manifest (topic 8 already leans this way) |
| Terminal | handover machinery, sbt-style (§4) | inherit tty, zero work *[amended — §S3 obligations]* |
| Global state between runs | depends on topic 1 cleanup | isolated |
| Per-run overhead | cached scala-cli compile check | same + ~1–2 s JVM start (noise vs multi-minute flows) *[measured <1 s warm — §S2]* |
| "Same as standalone" fidelity | approximate (rewritten pin, hooked runtime) | exact |
| New moving parts | 4 mechanisms + orca runtime changes | ~1 process call + manifest read |

**Recommendation: subprocess (§2c), with the in-process requirement re-scoped**
to "the shell launches, supervises and integrates the flow run on the shared
terminal". The only concrete asset in-process buys — a live in-memory session
registry — is served as well or better by the `.orca` run-manifest file
(restart-survivable, and needed for topic 8's "continue a session" anyway),
while every in-process cost is structural and permanent. The one in-process
ingredient worth keeping regardless: if the ADR overrules this, take the §5
hybrid (scala-cli `--command` + parent-first loader + orca shell bridge), and
under no circumstances options 2a (re-implementing scala-cli's wrapper) or 2b
(embedding scala-cli internals) or 3.1 (isolated-classloader with a foreign
orca).

Questions posed to the skeptic (answered in §S1–§S3): does any planned shell feature
*require* observing flow events live (not post-hoc via manifest)? Is a ~1–2 s
start acceptable in the "run a tiny flow repeatedly" loop? Does the subprocess
model complicate the shell's Ctrl-C story (child process-group signalling) more
than §4's handover would?

## Sources

- Codebase: `/home/adamw/orca/build.sbt`, `project/Dependencies.scala`,
  `runner/src/main/scala/orca/flow.scala`,
  `flow/src/test/scala/orca/CcNegativeCompileTest.scala`,
  `runner/src/test/scala/flowtests/FlowCompilesTest.scala`,
  `runner/src/main/scala/orca/runner/terminal/{TerminalInteraction,ConversationRenderer}.scala`,
  `examples/implement.sc`, `examples/runnable/README.md`.
- Standalone directives library request: <https://github.com/VirtusLab/scala-cli/issues/2443>;
  delivered as `directives-parser` by <https://github.com/VirtusLab/scala-cli/pull/4192>.
- Maven Central (verified via repo1/search APIs, 2026-07-18):
  `org.virtuslab:using_directives:1.1.4`;
  `org.virtuslab.scala-cli:{cli_3,build-module_3,core_3,directives_3,directives-parser_3}:1.15.0`;
  `io.get-coursier:interface:1.0.29-M4`; `io.get-coursier:coursier_2.13:2.1.25-M26`
  (no `coursier_3` published).
- Coursier API docs: <https://get-coursier.io/docs/api>.
- scala-cli docs: <https://scala-cli.virtuslab.org/> (compile `--print-class-path`,
  run `--command` verified against the locally installed scala-cli).
- JLine nested-terminal precedent: <https://github.com/jline/jline3/pull/554>
  (`TerminalBuilder.setTerminalOverride`, added for sbt);
  <https://github.com/sbt/sbt/pull/4054> (console/JLine handover fixes);
  <https://jline.org/docs/troubleshooting/>.
- `System.exit` interception dead ends: <https://openjdk.org/jeps/411>
  (SecurityManager deprecated, JDK 17), <https://openjdk.org/jeps/486>
  (permanently disabled, JDK 24).

---

## Skeptic review

Verdict up front: **UPHOLD the subprocess recommendation, with amendments.**
The steelman for in-process fails against the actual roadmap (§S1), the
measured latency is *better* than the proponent claimed (§S2), and the
subprocess model's real weak points are three small, well-precedented
obligations the proponent under-reported as "zero work" (§S3) — plus one
genuine cross-topic hole (version skew vs the topic-8 manifest, §S4) that
turns out to have a one-flag subprocess fix, empirically verified here.
Per-claim verdicts in §S5; final recommendation and the requirement-override
justification the ADR must make in §S6.

All measurements: scala-cli 1.14.0 (JVM-launcher install), Linux, warm Bloop
server, this machine, 2026-07-18.

### S1. Steelman: what does subprocess actually forfeit?

Each candidate in-process advantage, tested against the shell's menu (run a
flow / create a flow via harness / continue a session / re-configure / exit):

1. **Live event streaming into a shell-rendered UI.** Real loss: a subprocess
   can't hand typed `OrcaEvent`s to an in-shell renderer; the fallbacks
   (tail the incrementally-written manifest, or a JSONL event side-file) are
   string-typed and ad hoc. But no menu item renders a flow dashboard:
   during a run, `TerminalInteraction` already owns the whole terminal, and a
   shell that painted *around* it would fight it for the tty in both models.
   If a "shell-owned flow UI" ever lands on the roadmap, that is a redesign
   of the rendering layer regardless of process boundary. Not needed now.
2. **Shared cost tracking across flows.** `CostTracker` is per-run
   (flow.scala:144) in every model; a cross-flow "session spend" total needs
   per-run totals written somewhere either way. Adding a `cost` field to the
   topic-8 manifest gives the shell per-run totals for free; what's lost is
   only a *live ticking* aggregate mid-run — no menu item shows one. Not
   needed.
3. **Reusing warmed agents.** There is nothing to reuse: backends are rebuilt
   per run (`WiredAgents.build`, flow.scala:261), `opencode serve` is per-run
   and torn down in the body's `finally` (OpencodeServer.scala scaladoc),
   claude/codex/gemini/pi CLIs are spawned per conversation, and session
   durability is on disk by design. In-process would *permit* a future warm
   registry; today's architecture has no warm state an in-process shell could
   keep. Not a real loss.
4. **Cancelling a flow cleanly.** This steelman point **inverts**. In-process
   cancel means interrupting an Ox scope whose forks sit in interrupt-immune
   native reads (documented in `OpencodeServer` — destroy-to-unblock is the
   existing idiom) plus killing the drivers' CLI children — and per
   `01-global-state.md` #4, an abandoned in-process run leaves `flow.lock`
   holding the **live shell's PID**, so every later run in that workdir is
   refused until the shell restarts. Subprocess cancel is a tree-kill
   (§S3.3), after which the lock file holds a *dead* PID and the next run
   steals it cleanly (FlowLock.scala:84–100). Subprocess is strictly better
   here.
5. **Latency of repeated small flows.** Measured, warm Bloop cache
   (§S2): ~0.6 s total for a trivial script, ~0.85 s with
   `org.virtuslab::orca:0.0.17` on the classpath — *including* the child JVM
   start the proponent budgeted at 1–2 s. Even the "tiny flow in a loop" case
   spends multiples of that per LLM call. The latency argument for in-process
   is dead on the numbers.
6. **Programmatic overrides (extra listeners, prompter injection).** True:
   an `OrcaListener` object cannot cross a process boundary. But the
   proponent's §2c note is correct and bears repeating — it can't cross the
   *call* boundary either: the script constructs its own `flow(...)`
   arguments, so in-process delivery already requires the §5.3 ambient-hook
   backdoor, which itself requires forcing the shell's orca version (§3.2).
   Both models need a side channel; the subprocess channels are argv (the
   shell controls `-- <args>`, parsed by `OrcaArgs`) and **environment
   variables** (e.g. `ORCA_RUN_MANIFEST=…` read by the child's orca — env
   crosses the process boundary even though objects don't). Same forcing
   prerequisite, comparable mechanism weight. Not a differentiator.

Honest summary: on the current roadmap, **no menu item needs anything only
in-process can provide**. The features that would (live typed event UI, live
cross-flow cost ticker, warm agent pool) are all post-roadmap hypotheticals,
and two of the six steelman points actively favor subprocess.

### S2. Measurements (verifying §2c/§2d empirical claims)

- Warm `scala-cli run demo.sc` (hello-world): 0.56–0.69 s wall. With
  `//> using dep org.virtuslab::orca:0.0.17` + `//> using jvm 21`: 0.80–0.89 s.
  Cold first compile of a new trivial script: ~1.5 s. The proponent's
  "~1–2 s JVM start" is conservative; the real warm figure is **under 1 s**.
- `--server=false`: 2.6 s per run *even fully cached* (no compile output) —
  bypassing Bloop roughly quadruples warm latency. The shell should never
  pass it; noted because it also means "Bloop unavailable" degrades to ~2.6 s,
  still acceptable.
- `scala-cli run --command demo.sc -- x`: **verified, with a correction to
  §2d's sketch.** Output is one argument per line, but between the `java`
  path and `-cp` come `-D` lines (`-Dscala.sources=…`, `-Dscala.source.names=…`).
  A consumer must scan for the literal `-cp` line and take the next line as
  the classpath, then treat the first non-option token after it as the main
  class (`demo_sc`) — not slice fixed positions.
- `--command` **does** run the (incremental, cached) compile before printing:
  a modified script prints `Compiling project …` to stderr first; a script
  with an error exits 1 with diagnostics on stderr and an **empty stdout**.
  So `--command` doubles as the shell's pre-flight compile check — compile
  errors are cleanly separable from flow failures (§S3.5) at zero extra cost.

### S3. Attacking the subprocess story's weak points

§4's "inherit tty, zero work" is overstated. The real bill:

1. **Ctrl-C / SIGINT.** Verified empirically: `scala-cli run` children share
   the shell's **process group** (`pgid` identical; no `setsid` anywhere in
   the chain), and the chain has an intermediate process (launcher →
   scala-cli JVM → flow JVM). So Ctrl-C delivers SIGINT to *every* process on
   the terminal's foreground group at once: shell, scala-cli, flow JVM, and
   the drivers' CLI grandchildren (including `opencode serve`). The child
   tree dies of its own signal — correct, and identical to standalone usage —
   but the **shell dies too** unless it installs a SIGINT handler and ignores
   the signal while a child is in flight (re-enabling normal behaviour at its
   own prompt). This is standard practice, not novel machinery: git blocks
   SIGINT/SIGQUIT in the parent while waiting for the editor/pager
   (run-command's `launch_editor`), and sbt traps INT around `run`/`console`
   (`sbt.internal.util.Signals`); JLine exposes
   `Terminal.handle(Signal.INT, …)` for exactly this. Cost: ~a dozen lines,
   but it MUST be in the ADR — it is the one place the shell can be killed by
   accident.
2. **Terminal state after a child crash in raw mode.** If the flow JVM dies
   mid-`readLine` (ask-user prompt, JLine raw mode), the tty stays raw —
   `01-global-state.md` #2 notes JLine only restores on its own exception
   paths, not on JVM death. The shell must snapshot sane attributes at
   startup and re-assert them (plus cursor-show/reset SGR) after **every**
   `waitFor` return, unconditionally. Small, mandatory, and cheap insurance
   the proponent's "the shell just blocks on waitFor" elides.
3. **Hard-kill zombies.** Menu-driven cancel must not `destroy()` the direct
   child only: killing the scala-cli process alone orphans the flow JVM, and
   killing the flow JVM alone orphans `opencode serve` (its teardown lives in
   the flow body's `finally`, which never runs on SIGKILL — nor on
   SIGTERM/SIGINT, since the JVM runs only shutdown hooks then; today's
   standalone Ctrl-C is saved by process-group delivery, not by `finally`).
   The fix is the pattern orca already ships:
   `OsProcCliRunner.destroyForciblyTree` (OsProcCliRunner.scala:80) —
   `ProcessHandle.descendants()` then the root — with its documented
   double-fork caveat. The shell reuses it; alternatively, spawning the child
   in its own process group and signalling the group would also cover
   double-forkers, at the cost of taking over Ctrl-C forwarding manually.
   Preferred order for cancel: SIGINT to the tree (graceful, mirrors Ctrl-C),
   then tree-destroy after a timeout.
4. **Exit-code and stderr surfacing.** `inheritIO` streams stderr to the user
   directly and `waitFor` yields the code — nothing hidden. Residual
   ambiguity: compile failure and flow failure both exit 1. Resolved for free
   by §S2's finding: run `--command` first (pre-flight compile + classpath),
   then the real child; an exit-1 from the second phase is a flow failure by
   construction. Alternatively the manifest's `outcome` field disambiguates.
5. **Verdict on the attack:** all four points are real and all four have
   short, precedented answers. None approaches the standing complexity of
   §5's four coupled mechanisms. The amendment required is to *name* these
   obligations in the ADR instead of claiming "zero work".

### S4. The version-skew hole (critical, cross-topic)

The proponent's fidelity row ("honours script's orca pin — yes, exactly")
hides a cost: **the topic-8 run manifest is a new orca-runner feature.** A
flow pinning an older orca (e.g. `0.0.17`) has no manifest writer, so under
pin-honouring subprocess execution, "continue a session" after such a flow
**silently shows nothing** — a quiet feature hole, not an error. And older
pins are not an edge case: scripts are written once (often by a harness) and
pin the version current at authoring time, so as orca moves, *older-pin user
flows become the norm*. Built-in flows always match the shell (shipped
together; `updateDocs` keeps in-repo examples current), so the hole is
user-flow-only — but user flows are the shell's headline use case.

First, the honest framing: **in-process does not inherently fix this.** The
§5 design only has the manifest/listener hook because §3.2 *forces* the
shell's orca version; posture §3.1 (honour the pin in-process) was rejected.
So "the hook always exists" is a property of *version forcing*, not of
in-processness — and forcing is equally available to the subprocess model:

**Empirical finding (the one-flag fix).** scala-cli's documented precedence
is command line > using directives
(<https://scala-cli.virtuslab.org/docs/guides/introduction/using-directives/>).
Verified here that this applies to *same-module dependency versions*, in both
directions, on scala-cli 1.14.0: a script pinning
`//> using dep "com.lihaoyi::os-lib:0.9.1"` run with
`--dep com.lihaoyi::os-lib:0.11.3` resolves **0.11.3** on the `--command`
classpath (single jar), and with `--dep com.lihaoyi::os-lib:0.8.1` resolves
**0.8.1** — the CLI dep *replaces* the directive's version rather than
merging into highest-wins resolution. So
`scala-cli run --dep org.virtuslab::orca:<shellVersion> flow.sc`
deterministically forces the shell's orca **without the temp-copy pin
rewriting §5.1 needed** — no script mutation, no scratch files. Caveat: the
docs state the general precedence rule but not the same-module-replacement
semantics for cumulative options; re-verify on scala-cli upgrades (one canary
test in the shell module).

Evaluating the three postures against the project stance (consumers are
version-pinned scripts; built-ins always match; API back-compat concerns
minor):

- **(a) Accept degradation with a visible notice.** Shell parses the script's
  orca pin (directives-parser, already planned for topic 5's description
  sniffing); if pin < minimum-manifest version, run it anyway and show
  "sessions from this flow can't be continued (flow pins orca 0.0.17;
  shell integration needs ≥ 0.0.NN)". Cheap, honest, zero risk. The
  unacceptable variant is degradation *without* the notice.
- **(b) Force the shell's orca via `--dep`.** One flag, verified above.
  Cost: same as in-process §3.2 — a genuinely API-incompatible old flow now
  fails to compile instead of running manifest-less. Given the stance that
  such flows are rare and the compile error is clear and actionable
  ("run it directly with scala-cli, or update it"), this is the right
  *default*.
- **(c) Document a minimum orca version for shell integration features.**
  Necessary regardless; not sufficient alone (nobody reads it at the moment
  the menu item comes up empty).

**Recommended: (b) by default, falling back to (a) on compile failure, plus
(c) in the shell docs.** Concretely: run with `--dep` forcing; if the forced
compile fails, offer to re-run honouring the pin with the degradation notice.
This closes the hole for every script the forced compile accepts (the vast
majority, per the stance), never blocks running an old flow, and never lies
about missing sessions. It does amend the proponent's comparison table: the
subprocess fidelity cell becomes "exact by default; forced (compile-checked)
when shell integration is on" — an *option* the shell exercises, which is
strictly stronger than in-process, where forcing is structurally mandatory.
Bonus: with the shell's orca forced into the child, env-var channels
(`ORCA_RUN_MANIFEST`, future hooks) are guaranteed to have a reader — the
subprocess equivalent of §5.3's ambient hooks, without the `private[orca]`
mutable hook object.

### S5. Per-claim verdicts

| Proponent claim | Verdict |
|---|---|
| Warm-cache subprocess overhead "~1–2 s" | **Verified, conservative** — measured 0.6–0.9 s warm incl. orca classpath (§S2) |
| `--command` prints java/-cp/main-class, one arg per line | **Verified with correction** — `-D` lines precede `-cp`; parse by scanning for `-cp` (§S2) |
| `--command` gives a cached compile | **Verified** — incremental compile runs; exit 1 + stderr diagnostics + empty stdout on error (§S2) |
| Subprocess terminal story is "zero work" | **Overstated** — SIGINT handler, attribute restore after every child, tree-kill for cancel are mandatory (§S3) |
| Global-state immunity | **Holds, and stronger than stated** — the `flow.lock` live-PID asymmetry (01 #4) actively favors subprocess on abandoned runs (§S1.4) |
| Manifest serves session handoff as well as a live registry | **Holds only with skew handling** — silent no-manifest hole for older-pin flows unless the shell forces `--dep` or shows a notice (§S4) |
| "Honours pins exactly" as a pure advantage | **Amended** — pin-honouring is also the source of the skew hole; make forcing the default, pin-honouring the fallback (§S4) |
| In-process's only concrete asset is the in-memory registry | **Confirmed** — and §S1 finds no roadmap consumer for the other candidates either |

### S6. Final recommendation

**UPHOLD subprocess (§2c), amended.** The requirement's plain text says
in-process, so the ADR must overrule it *explicitly*, on this case: (1) a
workable in-process design exists (§5) but every one of its four standing
mechanisms — forced version, exit containment, terminal handover,
global-state hygiene (plus 01's `JLinePrompter` redesign) — exists to buy
back properties the subprocess model has natively; (2) the sole measured cost
is <1 s of warm start against multi-minute flows; (3) nothing on the shell's
menu consumes any capability only in-process provides (§S1); (4) the intent
behind "in-process" — one seamless terminal experience, shell integration
with the run — is fully met by the re-scoped wording. Conditional for the
ADR: if a future roadmap item requires live *typed* event streaming into a
shell-owned renderer, revisit via the §5 hybrid; nothing else on the horizon
justifies reopening.

Required amendments to the proponent's recommendation:

1. Name the subprocess obligations (§S3): shell SIGINT handler active while a
   child runs; terminal-attribute restore after every child exit; cancel =
   SIGINT-to-tree then `descendants()`-tree-destroy (reuse the
   `OsProcCliRunner.destroyForciblyTree` pattern); pre-flight `--command` (or
   manifest `outcome`) to separate compile failures from flow failures.
2. Close the version-skew hole (§S4): default to
   `--dep org.virtuslab::orca:<shellVersion>` forcing (verified to override
   the script's pin in both directions on scala-cli 1.14.0; add a canary
   test), fall back to pin-honouring with a visible degradation notice on
   compile failure, and document the minimum orca version for shell
   integration features.
3. Correct the table row "per-run overhead" to the measured <1 s and the §2d
   `--command` output sketch per §S2; never pass `--server=false`.

### Skeptic sources

- Measurements: scala-cli 1.14.0, Linux, warm Bloop, 2026-07-18 — timing
  (`hello` 0.56–0.69 s; orca-dep script 0.80–0.89 s; `--server=false` 2.6 s
  cached), `--command` output shape/compile/error behaviour, `--dep`-vs-pin
  precedence both directions (os-lib 0.8.1/0.9.1/0.11.3), `pgid`/`pstree` of
  a running `scala-cli run` child.
- scala-cli precedence docs (command line > using directives):
  <https://scala-cli.virtuslab.org/docs/guides/introduction/using-directives/>,
  <https://scala-cli.virtuslab.org/docs/guides/introduction/configuration/>.
- Codebase: `runner/src/main/scala/orca/flow.scala`,
  `runner/src/main/scala/orca/runner/FlowLock.scala`,
  `opencode/src/main/scala/orca/tools/opencode/OpencodeServer.scala`,
  `tools/src/main/scala/orca/subprocess/OsProcCliRunner.scala:80`
  (`destroyForciblyTree`); sibling drafts `01-global-state.md` (#2, #4),
  `08-session-continuation.md` (manifest design, §A.3 listener).
- Interactive shell-out precedent: git blocks SIGINT/SIGQUIT while waiting on
  the spawned editor/pager (run-command `launch_editor`); sbt's
  `sbt.internal.util.Signals` INT trapping around `run`/`console`; JLine
  `Terminal.handle(Signal.INT, …)` and `TerminalBuilder.setTerminalOverride`
  (<https://github.com/jline/jline3/pull/554>).
