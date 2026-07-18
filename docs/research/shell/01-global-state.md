> Final. Skeptic-reviewed — where the body and `## Skeptic review` disagree
> (notably #2: `JLinePrompter` prompts do NOT break on run 2 on Linux; the
> real loss is JLine's shutdown-hook/signal-handler cleanup), the skeptic
> section is authoritative. With ADR 0021 adopting subprocess flow execution,
> this inventory is moot for the shell itself and stands as (a) the recorded
> justification for that choice and (b) the checklist if in-process is ever
> revisited.

# 01 — Global-state audit: what leaks between consecutive in-process `flow()` runs

Scope: every module (`tools`, `claude`, `codex`, `opencode`, `pi`, `gemini`,
`flow`, `runner`), main sources only. The question: if an Orca Shell runs
`flow()` (or `runFlow`) twice in the same JVM, one run after another, what
process-global mutable state does run 1 leave behind for run 2 — and what
breaks if run 1 crashed?

Method: grep sweeps for `var`/mutable collections in `object`s, atomics,
`System.exit`, logback mutation, `Thread.setDefaultUncaughtExceptionHandler`,
shutdown hooks, system-property/env mutation, `os.pwd`-at-init, `Console`
redirection, signal handlers, threads/executors/processes outliving a run, and
JLine state — followed by full reads of every hit and of the run lifecycle
(`flow.scala`, `FlowLock`, `OrcaLog`, `TerminalInteraction`,
`ConversationRenderer`, `TerminalOutput`, `AskUserMcpServer`/`AskUserSession`,
`OpencodeServer`, backends).

Note on framing: agents/flows are trusted-but-fallible. "Crashed run" below
means an exception or hang, never adversarial behaviour.

## Summary table

| # | State | Location | Self-heals between sequential runs? | Classification |
|---|-------|----------|-------------------------------------|----------------|
| 1 | `System.exit(1)` on flow failure | `runner/src/main/scala/orca/flow.scala:192` | n/a — kills the JVM | **NEEDS-REDESIGN** |
| 2 | `JLinePrompter` singleton: `object` with `lazy val` JLine `Terminal`, closed at run end, cannot re-init | `runner/src/main/scala/orca/runner/terminal/ConversationRenderer.scala:296–318` | **No** — run 2's prompts silently cancel *[overturned — prompts work on run 2; see Skeptic review, corrected rows]* | **NEEDS-REDESIGN** |
| 3 | `FlowLock.processFlowLock` — process-wide `AtomicBoolean` | `runner/src/main/scala/orca/runner/FlowLock.scala:28` | Yes (released in `finally`) | SAFE (semantics match the shell's needs; see caveats) |
| 4 | `flow.lock` workdir file: PID-liveness staleness check assumes holder-process death ⇒ flow death | `runner/src/main/scala/orca/runner/FlowLock.scala:56–103` | Yes normally; **no** after a leak — the "stale" PID is the live shell | **NEEDS-CLEANUP** |
| 5 | `OrcaLog.start()/finish()` — mutates the global logback `orca` logger (appender + additivity) | `runner/src/main/scala/orca/runner/OrcaLog.scala` | Yes via `flow()`'s `finally`; not if the shell bypasses `flow()`; corrupts if runs overlap | **NEEDS-CLEANUP** |
| 6 | `Thread.setDefaultUncaughtExceptionHandler` (install-once-if-null) | `runner/src/main/scala/orca/flow.scala:377–388` | Persists by design; holds no run state | SAFE |
| 7 | `deleteOnExit` temp files/dirs — JVM `DeleteOnExitHook` static set grows per run; disk not reclaimed until JVM exit | `pi/src/main/scala/orca/tools/pi/PiBackend.scala:52,166`; `pi/src/main/scala/orca/tools/pi/PiAskUserExtension.scala:29`; `claude/src/main/scala/orca/tools/claude/ClaudeBackend.scala:267` | No (accumulates for JVM lifetime) | **NEEDS-CLEANUP** |
| 8 | `OrcaDebug.enabled/streamTrace` — env pinned at classload | `tools/src/main/scala/orca/util/OrcaDebug.scala:16,22` | Env is process-immutable anyway | SAFE (note: not per-run togglable) |
| 9 | `os.pwd` — default parameters only, evaluated at call time | `flow.scala:110`, all backends' `workDir` defaults | n/a | SAFE (shell must pass `workDir` explicitly) |
| 10 | Ask-user MCP Netty server (per conversation, ephemeral port) | `tools/src/main/scala/orca/backend/mcp/AskUserMcpServer.scala:74–91`, `AskUserSession.scala` | Yes (close wired into finalize; ephemeral port ⇒ no rebind conflict) | SAFE |
| 11 | `opencode serve` subprocess | `opencode/src/main/scala/orca/tools/opencode/OpencodeServer.scala` | Yes (`ctx.close()` tree-destroys it in the body's `finally`) | SAFE |
| 12 | Per-run instances: `EventDispatcher`, `CostTracker`, `SessionSupport` maps, `StageFrames`, `TerminalOutput`, conversations, `AgentBackend.closedFlag` | various (see §B) | Yes (all die with the run/Ox scope) | SAFE |
| 13 | Immutable statics: `Pricing.default`, prompt resources, jsoniter codecs, `OrcaBanner`, `OrcaArgs` parser, backend constants | various (see §C) | n/a | SAFE |
| 14 | Cross-run **on-disk** state: progress log, gemini `settings.json` merge, claude `.orca-mcp-*.json`, opencode global session store | various (see §D) | Mostly; crash leaves documented residue | SAFE / noted |
| 15 | Non-findings: no shutdown hooks (beyond #7), no signal handlers registered by orca, no `System.setProperty`, no `Console`/`System.setOut` redirection, no global executors | — | — | SAFE |

## A. Process-global mutable state

### 1. `System.exit(1)` on flow failure — NEEDS-REDESIGN

`runner/src/main/scala/orca/flow.scala:192`:

```scala
if failed then System.exit(1)
```

`flow()` is the public entry point for flow scripts; any failure (a
`SurfacedFlowFailure` or any `NonFatal`) sets `failed` and exits the JVM after
the cost summary and `orcaLog.finish()`. In a shell that runs flows
in-process, a single failed flow kills the whole shell.

- Self-heals: n/a — the process dies.
- The exit-free lifecycle already exists: `runFlow`
  (`flow.scala:210`), which *propagates* failures instead of exiting — but it
  is `private[orca]`, and it does not print the banner, start `OrcaLog`, wire
  the `CostTracker`, or print the cost summary (all of that lives only in the
  `flow()` wrapper, lines 133–192). The shell needs either a public exit-free
  wrapper that replicates the `flow()` extras, or `runFlow` widened plus a
  reusable "run bracket".
- The comment at `flow.scala:187–191` documents a second bite: in a *nested*
  `flow()` call the exit tears down the JVM before the outer run's `finally`
  (branch restore, lock release) — exactly the situation a shell hosting
  `flow()`-calling scripts would reproduce.

### 2. `JLinePrompter` — process-scoped singleton terminal — NEEDS-REDESIGN

`runner/src/main/scala/orca/runner/terminal/ConversationRenderer.scala:296–318`:

```scala
object JLinePrompter extends Prompter:
  @volatile private var opened = false
  private lazy val terminal: Terminal =
    val t = TerminalBuilder.builder().system(true).dumb(true).build()
    ...
  private lazy val reader: LineReader = ...
  override def close(): Unit = if opened then terminal.close()
```

This is the default prompter for approval and ask-user prompts. It is an
`object` holding a `lazy val` JLine system terminal. The scaladoc itself states
the limitation: *"process-scoped and its lazy terminal cannot re-initialize
after `close()`, so a second `flow(...)` in the same JVM that fires a prompt is
unsupported."*

- Lifecycle mismatch: `TerminalInteraction.close()`
  (`TerminalInteraction.scala:63–66`) calls `prompter.close()` at the end of
  **every** run, but the prompter is shared across runs. A Scala `lazy val`
  stays initialized, so run 2 reuses the closed `Terminal`.
- What breaks on run 2: `reader.readLine` on the closed terminal throws; `ask`
  maps `EndOfFileException` to `PromptOutcome.Interrupted`
  (lines 313–316), which the renderer turns into `conversation.cancel()` —so
  every approval/question in run 2 **silently cancels the conversation**
  instead of failing loudly. Interactive flows become quietly unusable.
- Raw-mode/terminal state: JLine's system terminal enters raw mode only inside
  `readLine` and restores on return/close; orca registers no signal handlers
  itself, but JLine's system terminal installs its own WINCH handling. On a
  crash mid-`readLine` within a surviving JVM, JLine's exception paths restore
  attributes; only JVM death mid-prompt leaves the tty raw (out of scope
  here).
- The fix seam already exists: `TerminalInteraction.start(prompter = ...)`
  takes an injected `Prompter` (`TerminalInteraction.scala:79–80`), and
  `Prompter` is a two-method trait. A per-run (or shell-owned, never-closed)
  prompter resolves this. The shell will additionally want to *share* one
  JLine terminal between its own menu UI and the flow's prompts — same seam.
- Note the non-prompting path is fine: runs that never fire a prompt never
  force the lazy terminal (`opened` stays false, `close()` is a no-op).

### 3. `FlowLock.acquireProcess` — process-wide `AtomicBoolean` — SAFE (with caveats)

`runner/src/main/scala/orca/runner/FlowLock.scala:26–34`:

```scala
private val processFlowLock = new AtomicBoolean(false)
def acquireProcess(): Unit =
  if !processFlowLock.compareAndSet(false, true) then
    throw new OrcaFlowException("a flow is already running in this process")
def releaseProcess(): Unit = processFlowLock.set(false)
```

Semantics: a *reentrancy/concurrency guard*, not a "one flow per process
lifetime" latch. `runFlow` acquires it first thing (`flow.scala:229`) and
releases it in the outermost `finally` (`flow.scala:375`), so two consecutive
sequential runs are explicitly supported by this mechanism — the guard only
rejects *overlapping* runs (nested `flow()` inside a flow body, or a
concurrent one), which would corrupt the single working tree (ADR 0018 §6).

- Self-heals: yes. The release is in `finally`, which runs for `NonFatal`
  failures, `SurfacedFlowFailure`, fatal throwables unwinding, and thread
  interruption. Only JVM death or a *hung* (never-unwinding) run keeps it set.
- Shell interaction: this is actually the semantics the shell wants —
  serialize flows. Caveats: (a) if the shell ever abandons a hung flow thread
  and offers the menu again, the next run is refused until the hung run
  unwinds (arguably correct); (b) `releaseProcess` is a blind `set(false)` —
  a stray/double release by a future embedder would let two flows overlap. No
  change needed for sequential runs.

### 4. Workdir `flow.lock` PID-staleness vs an in-process shell — NEEDS-CLEANUP

`runner/src/main/scala/orca/runner/FlowLock.scala:56–103`. The lock file
`{workDir}/.orca/cache/flow.lock` contains the holder's **PID**
(`ProcessHandle.current().pid()`, line 58). On contention, a live PID
hard-refuses; a dead PID is stolen with a warning (lines 84–100). Released in
`finally` via `releaseWorkdir` (`flow.scala:374`).

- Self-heals between sequential runs: yes, same `finally` argument as #3.
- What breaks in the shell: the staleness heuristic encodes "one flow per
  process" — *holder process alive ⇒ flow still running*. In a long-lived
  shell, the PID in the lock file is the **shell's own PID**. If the file is
  ever leaked (shell killed -9 mid-run and restarted → old file holds a dead
  PID: fine, stolen; but shell *survives* a run that skipped its `finally`,
  e.g. a hung run the user abandoned, or a future bug), every subsequent run
  in that workdir is refused with "a flow is already running (pid N)" where N
  is the shell itself — and unlike the two-process case, restarting the flow
  doesn't help; only restarting the shell does. Also, two *different*
  workdir locks held by the same shell PID are indistinguishable from a
  genuinely-running flow.
- The `flow.scala:187–191` comment already names one concrete leak path:
  nested `flow()` + `System.exit` skips the outer run's release (that one
  resolves via dead-PID steal only because the JVM died; in-shell containment
  of `System.exit` (#1) removes the JVM death but must then also not leak the
  outer lock).
- Small change that fixes it: include a run token (e.g. PID + a per-run nonce,
  or PID + thread id) and/or have the shell clear/verify its own stale lock
  between runs. Classification: NEEDS-CLEANUP.

### 5. `OrcaLog` — global logback mutation — NEEDS-CLEANUP

`runner/src/main/scala/orca/runner/OrcaLog.scala`. Correction to the research
plan's premise: `start()` does **not** touch the root logger. It mutates the
global logback **`orca` logger**: attaches a per-run DEBUG `FileAppender`
(fresh temp file, `deleteOnExit = false`) and sets the logger **non-additive**
(lines 72–74), so `orca.*` output goes to the file and stops reaching the root
console appender. `finish()` (lines 41–46) is symmetric: stops + detaches the
appender and restores `setAdditive(true)`; it is idempotent via an
`AtomicBoolean` (line 35).

Per-run coverage:

- **Run 2 after a clean or failed run 1 via `flow()`**: fine. `flow()` calls
  `orcaLog.finish()` in its `finally` (`flow.scala:186`), which runs even when
  the body failed (it runs *before* the `System.exit`). Run 2's `start()`
  attaches a fresh appender to a clean logger. Sequential in-process runs
  self-heal.
- **Crashed run 1 that skipped `finish()`** (JVM survives but `finally` was
  skipped — hung run abandoned by the shell, or a future exit-free wrapper
  forgetting the bracket): run 2's `start()` **adds a second appender** to the
  same `orca` logger. Run 2's log lines are then written to *both* trace
  files (run 1's file keeps growing), and run 1's `finish()`, if it ever runs
  late, flips `setAdditive(true)` while run 2 is active — spilling run 2's
  full DEBUG `orca.*` stream onto the console *[amended: WARN+ only — see
  Skeptic review #5]*. Appenders accumulate one per leaked run.
- **Concurrent runs** (not the shell's plan, but worth stating): inherently
  broken — one shared `orca` logger cannot fan out per-run; both files get
  both runs' lines and the additivity toggles race.
- **Shell bypassing `flow()`**: `OrcaLog` is only started by the `flow()`
  wrapper, not by `runFlow`. A shell that calls `runFlow` directly gets no
  trace file unless it owns the `OrcaLog.start()/finish()` bracket itself.

Small changes that would make this robust: make `start()` detach any existing
`orca-run-trace`-named appender first (it already sets a fixed name, line 66),
or hold the active `OrcaLog` in a process-level slot that `start()` finishes
before starting anew. Classification: NEEDS-CLEANUP (correct for the intended
sequential use, but not self-healing after a skipped `finish`, and the
lifecycle lives in the wrong place for a `runFlow`-based shell).

### 6. Default uncaught-exception handler — SAFE

`runner/src/main/scala/orca/flow.scala:377–388`. Installed once per JVM,
guarded by `if Thread.getDefaultUncaughtExceptionHandler == null`. The handler
captures only a logger (`LoggerFactory.getLogger("orca")`) and prints to
stderr + logs at DEBUG; it holds **no per-run state**, so persisting across
runs is harmless. Two notes for the shell: (a) if the shell (or a UI library)
installs its own default handler first, orca will never install its —
acceptable, but the shell's handler then owns the diagnostic; (b) after run N
ends, a late uncaught exception from a leaked daemon thread still routes
through this handler and logs to the (restored-additive) `orca` logger — the
DEBUG level means it won't hit the console appender, only a live trace file if
one is attached.

No JVM shutdown hooks are registered anywhere in orca's own code (`grep` for
`addShutdownHook`/`Runtime.getRuntime`: no hits outside `deleteOnExit`, see
#7). No signal handlers (`sun.misc.Signal` etc.): none.

### 7. `deleteOnExit` accumulation — NEEDS-CLEANUP

`java.io.File#deleteOnExit` registers a single JVM shutdown hook whose static
set of paths **only grows** and whose files are reclaimed only at JVM exit.
In the one-run-per-process world that's fine; in a long-lived shell every run
permanently adds entries (memory) and leaves the files on disk for the shell's
lifetime:

- `pi/src/main/scala/orca/tools/pi/PiBackend.scala:52` — one
  `orca-pi-sessions-*` temp **dir per `PiBackend` instance** (one per run that
  wires pi), `deleteOnExit = true`. Side note: `deleteOnExit` on a *directory*
  only works if it is empty at exit; a dir still containing session files
  won't actually be deleted — so these leak past JVM exit too.
- `pi/src/main/scala/orca/tools/pi/PiBackend.scala:166` — per-call
  `orca-pi-system-prompt-*` dirs, `deleteOnExit = true`.
- `pi/src/main/scala/orca/tools/pi/PiAskUserExtension.scala:29` — per-call
  `orca-pi-ask-user-*` dirs, `deleteOnExit = true`.
- `claude/src/main/scala/orca/tools/claude/ClaudeBackend.scala:267` —
  per-conversation `orca-system-prompt-*.md` via `os.temp(...)` with os-lib's
  **default `deleteOnExit = true`**.
- Deliberately *not* in this bucket: `OrcaLog`'s trace file
  (`deleteOnExit = false`, kept for inspection), codex's config temp
  (`CodexBackend.scala:224–228`, explicit `deleteOnExit = false` with explicit
  cleanup), `Lint`'s temp (`Lint.scala:92–101`, `finally`-owned).

Impact per run: a handful of small files — slow growth, not a correctness
bug. Fix: delete eagerly at conversation/backend close (the close hooks
already exist) instead of relying on JVM exit. Classification: NEEDS-CLEANUP.

### 8. `OrcaDebug` — env pinned at classload — SAFE

`tools/src/main/scala/orca/util/OrcaDebug.scala:16,22`. `ORCA_DEBUG` and
`ORCA_DEBUG_STREAM` are read once at object initialization. Since a JVM's
environment is immutable, this is not a leak — but the shell cannot toggle
these per run; they're fixed for the shell's lifetime at first touch. (The
`--verbose` CLI flag path, `args.verbose` at `flow.scala:225`, *is* per-run.)
Display-side env (`NO_COLOR`, `ORCA_NO_ANIMATION`, `CI`) is read via `def`s in
`TerminalInteraction` (`TerminalInteraction.scala:90–112`) — re-evaluated per
run, SAFE.

### 9. `os.pwd` — call-time defaults only — SAFE (shell obligation)

No object initializes state from `os.pwd`. All hits are **default parameter
values** evaluated at call time: `flow(workDir = os.pwd)`
(`flow.scala:110`) and per-backend test-convenience defaults
(`ClaudeBackend.scala:54`, `CodexBackend.scala:56`, `PiBackend.scala:45`,
`GeminiBackend.scala:55`, `OpencodeBackend.scala:73`, plus tool constructors).
Obligation for the shell: the JVM's working directory is fixed at launch and
cannot change, so a shell offering "run a flow in project X" must pass
`workDir` explicitly and never rely on the default.

## B. Per-run resources — verified run-scoped (SAFE)

These were each read for lifecycle; all are instance-level, created inside a
run and torn down by the run's `finally` chain or its Ox scope. Listed so the
skeptic pass knows they were checked, with the leak-on-crash story for each.

- **Ask-user MCP server (Netty)** —
  `tools/src/main/scala/orca/backend/mcp/AskUserMcpServer.scala:74–91`. One
  `NettySyncServer` binding **per interactive conversation**, on `127.0.0.1`
  port 0 (ephemeral) — so run 2 can never hit an address-in-use conflict with
  run 1's binding. Lifecycle is caller-owned via `AskUserSession`
  (`AskUserSession.scala`): close order bridge → server → extras, each wrapped
  so one failure doesn't skip the next; `allocate` closes bridge+server if the
  extras callback throws (lines 37–49); drivers close it from `onFinalize`
  after the read loop drains (e.g. `ClaudeBackend.scala:166–168`). Each
  binding creates and stops its own Netty event-loop threads (tapir
  netty-sync per-server groups — library behaviour, not re-verified in
  source). Residual risk: a conversation that never finalizes leaks one
  binding + its threads for the JVM's life — but the finalize wiring is the
  invariant the drivers already maintain. SAFE.
- **`opencode serve` process** — `OpencodeServer.scala`. Per-run instance;
  spawned lazily on first `http` force; `shutdown()` tree-destroys the process
  (so the Ox drain forks' reads EOF) and is called from `ctx.close()` in the
  body's `finally` (`flow.scala:367`), *before* the scope joins. Idempotent
  (`stopped` CAS), covers the start/shutdown race (line 139). SAFE. (Its
  on-disk session store is global and durable *by design* — §D.)
- **`EventDispatcher`** — `flow/src/main/scala/orca/events/EventDispatcher.scala`.
  Per-run instance (`flow.scala:241`); the `quarantined` concurrent set is
  instance state, so a listener quarantined in run 1 is clean in run 2. SAFE.
- **`CostTracker`** — instance per `flow()` call (`flow.scala:144`);
  `AtomicReference` state is instance-level. SAFE.
- **`SessionSupport`** — `tools/src/main/scala/orca/backend/SessionSupport.scala:63`.
  The client→wire `ConcurrentHashMap` lives in the per-backend instance;
  backends are rebuilt per run (`WiredAgents.build`, `flow.scala:261`), so
  mappings cannot leak across runs. SAFE.
- **`StageFrames`** — `flow/src/main/scala/orca/StageFrames.scala:52–111`.
  Trait mixed into the per-run context; `ownerThread` captured at
  construction. Fresh per run, so a shell running each flow on a new thread is
  fine — with the existing constraint (already true in `runFlow`) that the
  context must be constructed on the same thread that runs the body. SAFE.
- **`AgentBackend.closedFlag` / claude `sharedClosedFlag`** —
  `tools/src/main/scala/orca/backend/AgentBackend.scala:42`,
  `ClaudeBackend.scala:59`. Per-backend-instance use-after-close latch; a
  handle leaked from run 1 into run 2 fails loudly with `ClosedMessage`
  (`AgentBackend.scala:156–162`) — exactly the right behaviour for the shell.
  SAFE.
- **Terminal rendering** — `TerminalOutput.scala`, `TerminalEventListener`
  (`@volatile var stack`, instance), `ConversationRenderer` (per-conversation
  instance), `TerminalInteraction` (per-run, closed in `finally` at
  `flow.scala:373`). All actors/animator forks are bound to the run's Ox
  scope. `utf8Stderr` wraps — never closes — `System.err`
  (`TerminalInteraction.scala:106–107`); no `System.setOut/setErr/setIn` or
  `Console.withOut` anywhere. SAFE. (The one singleton in this subsystem is
  `JLinePrompter`, #2.)
- **Conversation/driver `var`s** — `ClaudeConversation`, `CodexConversation`,
  `GeminiConversation`, `OpencodeConversation`, `PiConversation`,
  `AskUserEchoes`, `StderrPipeline`, `ForkedConversation`,
  `AskUserBridge`: all instance state on per-conversation objects. SAFE.
- **`DefaultFlowContext`** failure-collection `AtomicReference`
  (`DefaultFlowContext.scala:72`): per-run instance. SAFE.

## C. Immutable statics — SAFE

- `Pricing.default` (`flow/src/main/scala/orca/events/Pricing.scala:104`):
  immutable `PriceList` value, and per-run overridable via `flow(pricing = …)`.
- `PromptResource` (`tools/src/main/scala/orca/util/PromptResource.scala`):
  stateless loader; prompt objects (`PlanPrompts`, `ReviewerPrompts`,
  `DefaultPrompts`, …) hold immutable strings resolved at classload;
  a missing resource fails at object init once per JVM (same first-run
  behaviour in a shell).
- jsoniter codecs (`JsonCodecMaker.make` / `derives ConfiguredJsonValueCodec`
  across `claude/streamjson`, `gemini`, etc.): immutable, thread-safe — safe,
  as anticipated.
- `OrcaBanner.version` (`OrcaBanner.scala:15–16`): `def`, reads the manifest
  each call. `OrcaArgs`' mainargs parser: immutable given. `OrcaDir`:
  stateless path logic; `ensureCache` writes idempotent markers.
  `AgentBackend.ClosedMessage`, `ClaudeBackend` constants, `OpencodeServer`
  companion regex: immutable.
- `OsProcCliRunner` (`tools/src/main/scala/orca/subprocess/OsProcCliRunner.scala`):
  stateless object (logger only); the memoised stream iterators are on the
  per-spawn `OsPipedSubProcess`.

## D. Cross-run on-disk state (adjacent to the JVM question)

Not JVM memory, but state one run leaves for the next on the same machine —
the shell makes these *consecutive-run* rather than *separate-invocation*
concerns:

- **Progress store** — `.orca/progress-<promptHash>.json`
  (`OrcaDir.scala:37–40`, `ProgressStore`). Persistence is the *feature*
  (crash→resume), but note the shell UX consequence: running the same flow
  with the same prompt in the same workdir twice in a row triggers
  resume-from-progress semantics, not a fresh run. The shell should surface
  this (it likely wants to for "continue a session" anyway — topic 8).
- **gemini `settings.json` merge** —
  `gemini/src/main/scala/orca/tools/gemini/GeminiSettings.scala:46–58`.
  Mutates the user-visible `<workDir>/.gemini/settings.json` per interactive
  conversation, restoring original bytes on close. Sequential runs are clean;
  a crashed run leaves a stale `orca` entry with a dead URL (documented,
  ADR 0015); the next run's merge overwrites the entry with its live URL, so
  it self-corrects — but that next run's "restore" then re-instates the
  polluted version. Known, accepted; no worse in a shell.
- **claude `.orca-mcp-<port>.json`** — `ClaudeBackend.scala:229–232`:
  workdir-local, port-named (no cross-run collision), deleted via a finalize
  resource (`ClaudeBackend.scala:184–186`); a crash leaves a harmless
  untracked file.
- **`flow.lock`** — see #4.
- **opencode global session store** — durable machine-global by design
  (`OpencodeServer.scala` scaladoc, `SessionSupport.durable`); the shell's
  session-continuation feature (topic 8) benefits from exactly this.
- **Git tree / branch state** — `FlowLifecycle` restores via its own
  success/failure teardown inside the run; the known residual is #1's nested
  `System.exit` skipping the outer restore.

## E. Explicit non-findings

Verified absent from all main sources: JVM shutdown hooks other than
`deleteOnExit` (#7); signal handlers registered by orca (JLine's system
terminal manages its own — see #2); `System.setProperty`/`sys.props`
mutation; `System.setOut/setErr/setIn` and `Console.with*` redirection;
`ThreadLocal`s; global/static executors or daemon-thread pools owned by orca
(all concurrency is Ox forks bound to the run's `supervised` scope; Netty's
threads are per-binding, #B).

## Implications for the shell (one paragraph)

Sequential in-process runs are *closer to working* than the one-run-per-process
assumption suggests: the locks, log appender, terminal output, MCP servers,
and the opencode process all release in `finally` chains that survive
failures. The two hard blockers are `System.exit(1)` in `flow()` (#1 — the
shell must get a public exit-free entry with the banner/OrcaLog/CostTracker
extras) and the `JLinePrompter` singleton (#2 — per-run prompter injection,
which the shell wants anyway to share its terminal) *[#2 downgraded — see Skeptic review]*. The cleanups (#4 lock
PID semantics, #5 OrcaLog leak-tolerance, #7 eager temp-file deletion) only
bite on a crashed/abandoned run but are all small, seam-adjacent changes.

## Skeptic review

Method: independent grep sweeps over all `*/src/main/scala` for object-level
`var`s/mutable collections, atomics, `lazy val`s in objects, `System.exit`,
`System.setProperty`/`sys.props`, shutdown hooks, `deleteOnExit`,
`setDefaultUncaughtExceptionHandler`, signal handlers, `TerminalBuilder`,
executors/`ExecutionContext`/`new Thread`, `Console`/`System.set*`
redirection, and `synchronized`; full reads of `flow.scala`, `FlowLock.scala`,
`OrcaLog.scala`, `ConversationRenderer.scala`, `TerminalInteraction.scala`,
`TerminalOutput.scala`, `OsProcCliRunner.scala`, `EventDispatcher.scala`,
`SessionSupport.scala`, `GlobalSettings.scala`, plus the shipped
`runner/src/main/resources/logback.xml`; a source read of JLine 3.28.0
(`ExecPty`, `JniNativePty`/`FfmNativePty`, `PosixSysTerminal`,
`AbstractPosixTerminal`, `NonBlockingReaderImpl`, `TerminalBuilder`); and an
**empirical reuse test** of a closed JLine system terminal under a real pty.

### Verdicts on the five highest-impact claims

**#1 `System.exit(1)` / `runFlow` bracket — UPHELD** (minor addition).
Verified against `runner/src/main/scala/orca/flow.scala:147–192, 210–224`.
`flow()` is the only `System.exit`; `runFlow` propagates and is
`private[orca]`; the `finally` at 184–186 does run the cost summary and
`orcaLog.finish()` before the exit. One omission in the draft's list of
`flow()`-only extras: `runFlow` also does **not** install the uncaught
exception handler (`installUncaughtExceptionHandler()`, `flow.scala:142`) — a
`runFlow`-based shell must own that too. NEEDS-REDESIGN stands, taxonomically:
no between-run cleanup can undo an exit; the fix is an API change (albeit a
small one, since the exit-free lifecycle already exists).

**#2 `JLinePrompter` "cannot re-init; run 2's prompts silently cancel" —
OVERTURNED (failure mode), AMENDED (classification rationale).** The
singleton/lifecycle description is accurate
(`ConversationRenderer.scala:296–318`, `TerminalInteraction.scala:63–66`),
but the claimed run-2 behaviour is wrong on Linux, and the scaladoc it leans
on is wrong with it. Empirical test (JLine 3.28.0, `PosixSysTerminal` under a
real pty): build terminal + reader, `readLine` once, `terminal.close()`,
`readLine` again — **the second `readLine` succeeds and returns the typed
line**. No `EndOfFileException`, no cancel. Root cause in JLine source: for a
*system-stream* terminal, `close()` never closes the underlying fd —
`ExecPty.close()` is empty (`impl/exec/ExecPty.java:59`), and
`JniNativePty.close()`/`FfmNativePty.close()` guard with `if (slave > 0)`
while the system slave fd is 0/stdin (`impl/jni/linux/LinuxNativePty.java`,
`current()`), so nothing happens; `reader.shutdown()`
(`NonBlockingReaderImpl.java:70`) merely parks the helper thread, and
`read()` restarts it on demand (`startReadingThreadIfNeeded`). `close()` only
restores tty attributes, unregisters signal handlers, and removes JLine's
shutdown hook.

What reuse-after-close *actually* costs (new findings, below): the tty-restore
shutdown hook and the `sun.misc.Signal` handlers that `PosixSysTerminal`
registered at build (`PosixSysTerminal.java:48–58` — all five signals, not
just WINCH) are removed at run 1's close and never re-registered, so a JVM
death mid-prompt in run ≥2 leaves the tty raw, and WINCH/resize handling is
gone. And the whole thing rests on unspecified, provider- and
platform-dependent behaviour (Windows `AbstractWindowsTerminal` and macOS
were not tested; the dumb-terminal fallback differs again).

Corrected classification: not a hard **blocker** — on Linux a second
prompting run works today — but the per-run/shell-owned `Prompter` injection
via `TerminalInteraction.start(prompter = ...)` remains the right design (the
shell needs the seam anyway to share its terminal), and the reuse behaviour
must not be relied on. Suggested label: NEEDS-REDESIGN for the shell seam,
with the severity note corrected from "run 2 unusable" to "run 2 works by
accident of JLine internals; unsupported and degraded (no tty-restore hook,
no signal handlers)". The `JLinePrompter` scaladoc's "cannot re-initialize"
sentence should be fixed when the seam is reworked.

**#3 `FlowLock.processFlowLock` — UPHELD.** Verified `FlowLock.scala:26–34`,
acquire at `flow.scala:229`, release in the outermost `finally` at
`flow.scala:375`. Reentrancy-guard semantics, blind `set(false)` release, and
the hung-run caveat are all as described. SAFE stands: the only way run 2
breaks is a run-1 thread that never unwinds, and refusing to overlap it is the
correct behaviour, not a leak.

**#4 `flow.lock` PID-liveness — UPHELD.** Verified `FlowLock.scala:56–107`:
PID content (line 58), `isAlive` staleness check (84–86), live-PID refusal
(87–90), dead-PID steal (91–100), release at `flow.scala:374`. The in-shell
consequence (a leaked lock names the shell's own live PID, so it is never
stolen and only a shell restart clears it) is correctly reasoned.
NEEDS-CLEANUP stands.

**#5 `OrcaLog` appender accumulation — UPHELD on mechanics, AMENDED on one
severity claim.** Verified `OrcaLog.scala`: `start()` attaches a fresh named
appender and `setAdditive(false)` (72–74) without detaching an existing one;
`finish()` is instance-idempotent (`AtomicBoolean`, line 35) but two leaked
`OrcaLog` instances still accumulate appenders, and a late run-1 `finish()`
does flip the shared logger additive while run 2 runs. Amendment: the spill is
**not** "run 2's full DEBUG `orca.*` stream onto the console". The shipped
console appender carries a WARN `ThresholdFilter`
(`runner/src/main/resources/logback.xml:11–13`), so restored additivity leaks
only WARN+ `orca.*` lines to the console; the full-DEBUG duplication goes to
the *leaked run's trace file*, as the draft's other bullet correctly states.
NEEDS-CLEANUP stands; the proposed detach-by-name fix is right (the fixed
appender name `orca-run-trace`, line 66, makes it trivial).

**"Everything else is per-run" (#12/§B) — UPHELD.** Independently re-swept;
every `var`, atomic, and mutable collection outside the items above is
instance- or local-scoped. Two suspicious sweep hits were confirmed false
alarms by full reads: the `TerminalOutput` vars live in the per-run
`TerminalOutputState` class, not the companion (`TerminalOutput.scala:100–125`
— the companion holds only `start()`); `OsProcCliRunner`'s memoised lazy
iterators live in the per-spawn `OsPipedSubProcess` class, not the object
(`OsProcCliRunner.scala:52–70` — the object holds only a logger).
`EventDispatcher.quarantined` (`EventDispatcher.scala:25–26`),
`SessionSupport.wireIds` (`SessionSupport.scala:54–64`), and
`ClaudeBackend.sharedClosedFlag` are instance fields as claimed. The
`ConversationRenderer` prompt path was verified: `PromptOutcome.Interrupted →
conversation.cancel()` (`ConversationRenderer.scala:192–193, 209–210`), so
the draft's *mechanism* for the silent-cancel story was right — only the
premise (EOF on a closed terminal) was not.

### Missed findings

1. **JLine registers a JVM shutdown hook and `sun.misc.Signal` handlers when
   the lazy terminal is built** — third-party, but in-process and
   run-lifecycle-relevant. `PosixSysTerminal` (jline 3.28.0) does
   `ShutdownHooks.add(closer)` and `Signals.register`/`registerDefault` for
   **all five** signals (INT, QUIT, TSTP, CONT, WINCH) at construction
   (`PosixSysTerminal.java:48–58`; `TerminalBuilder` defaults `nativeSignals =
   true`, `SIG_DFL`), and removes both in `doClose()`. Orca-side trigger:
   `ConversationRenderer.scala:301–302`. Consequences: (a) §E's "no shutdown
   hooks / no signal handlers" is true only of orca's *own* code and should
   say so for the JLine case explicitly (it does for WINCH, but understates —
   it is all five signals, not "its own WINCH handling"); (b) as noted under
   #2, after run 1's close these protections are permanently gone for later
   runs that reuse the singleton.
2. **`JLineNativeLoader` extracts a native library to the temp dir with
   `deleteOnExit`** on first terminal build (`JLineNativeLoader.java:199–200`)
   — one more (third-party, once-per-JVM) member of #7's hook set. Trivia.
3. **Netty/tapir statics** behind the ask-user server: first use initialises
   process-lifetime Netty statics (allocator caches, `GlobalEventExecutor`
   daemon thread). Memory-only, no per-run growth after first touch; worth a
   line in §E so "no global executors" is explicitly scoped to orca-owned
   code. Per-binding event-loop groups are per-run as the draft says.
4. **`flow()`-only extras list incomplete** (#1): add
   `installUncaughtExceptionHandler()` (`flow.scala:142`).
5. Completeness sweep otherwise **confirms the inventory**: no additional
   orca-owned object-level `var`s (`JLinePrompter.opened` is the only one), no
   other object-level `lazy val`s holding resources
   (`JLinePrompter.terminal/reader` only), no other process-wide atomics
   (`FlowLock.processFlowLock` only), no `System.setProperty`, no
   `Console`/`System.set*` redirection, no orca-owned threads/executors, no
   further default-`deleteOnExit` `os.temp` sites beyond the four listed
   (`ClaudeBackend.scala:267` confirmed as the only implicit-default one; the
   codex/Lint/ProgressStore/OrcaLog sites all pass `deleteOnExit = false`).

### Corrected summary rows

| # | Correction |
|---|-----------|
| 2 | Failure mode corrected: on Linux, run 2's prompts **work** on the closed singleton terminal (JLine system-terminal `close()` never closes the fd; verified empirically). Residual: unspecified behaviour, and run ≥2 prompts lose JLine's tty-restore shutdown hook + signal handlers. Keep the prompter-injection redesign for the shell seam; drop "run 2 silently cancels" as its justification. |
| 5 | Additivity-restore spill is WARN+ only (console appender has a WARN `ThresholdFilter`), not the full DEBUG stream. Rest stands. |
| 6/15 | JLine's lazy terminal adds a JVM shutdown hook + handlers for all five signals (removed at first close, never re-added); Netty statics and the JLine native-lib `deleteOnExit` exist as third-party process-lifetime state. §E's non-findings hold for orca-owned code only. |
