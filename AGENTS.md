# Working on Orca

Internals, architecture, and coding conventions for hacking on the library
itself. Build/test commands and the recipes for running a locally modified
orca live in [CONTRIBUTING.md](CONTRIBUTING.md); end-user documentation in
the [README](README.md).

Orca is implemented in Scala 3 on top of [Ox](https://ox.softwaremill.com/)
for structured concurrency, [tapir](https://tapir.softwaremill.com/) for
JSON Schema derivation, and
[jsoniter-scala](https://github.com/plokhotnyuk/jsoniter-scala) for codec
generation.

## Project layout

```
orca/
├── build.sbt / project/
├── tools/      # tool traits + os-backed impls (git/gh/fs), LLM SPI + session durability, InStage, events, subprocess, sweep
├── flow/       # stage/display/fail + FlowContext/FlowControl; orca.{plan,review,pr,progress}
├── claude/ codex/ gemini/ opencode/ pi/   # one module per coding-agent backend
├── runner/     # flow() entry, DefaultFlowContext, FlowLifecycle, terminal UI
└── shell/      # `orca-shell`, the `orca` CLI's interactive front-end (ADR 0021)
```

Dependency graph:

```
tools   (standalone)
  ├── flow                          → tools
  ├── claude / codex / gemini /
  │     opencode / pi               → tools
  ├── runner                        → tools + flow + all five backends
  └── shell                         → runner (published `orca`)
```

The runner module owns the `flow` entry point (`package orca`) and wires
defaults via `DefaultFlowContext` (`package orca.runner`); the stage
setup/teardown/recovery state machine is `orca.runner.FlowLifecycle`. The
terminal UI lives in `orca.runner.terminal`, behind an `Interaction`, so
swapping it for a Slack or HTTP equivalent is one substitution at the call
site rather than rewiring modules.

The user-facing surface lives in `package orca` (the `flow` entry, the tool
accessors — including `planningAgent`/`codingAgent`/`reviewAgent`, the
backend-agnostic role accessors (ADR 0020) — `stage`/`display`/`fail`,
`JsonData`, `OrcaArgs` and the `RunTarget` its flags parse into).
Implementations live in
focused subpackages: `orca.tools` (os-backed git/gh/fs impls + their traits),
`orca.agents` + `orca.backend` (LLM SPI, `SessionSupport`,
conversation driver), `orca.subprocess` (subprocess shim), `orca.sweep`
(finds agent work that outlived a turn), `orca.events`
(event bus), one `orca.tools.<backend>` per coding agent, and `orca.runner` /
`orca.runner.terminal` (wiring + terminal UI). The flow module adds
`orca.{plan,review,pr,progress}`.

`codingAgent: Agent[ctx.CodeB]` (the same holds for `planningAgent`/
`ctx.PlanB` and `reviewAgent`/`ctx.ReviewB`) is path-dependent, so it only
works inside a straight-line `flow(...)` body sharing one `using
FlowContext` — it doesn't survive being factored into a helper function,
since two `FlowContext` parameters' `CodeB` members don't unify even when
they're the same backend at runtime. A helper should instead take an
explicit `[B <: BackendTag]` type parameter, or bundle the agent and its
durable session as a `FlowSession[B]` handle (`codingAgent.session(name,
seed)`) — see the `CodeB` scaladoc (`flow/src/main/scala/orca/FlowContext.scala`)
for the full rationale.

## The stage-bound runtime

The flow runtime is specified in [ADR 0018](adr/0018-stage-bound-flow-runtime.md) —
read it before touching `stage`, the progress log, or sessions. The invariants
most easily broken:

- **Capability gating.** Four compile-time capabilities gate side effects:
  `FlowContext` (reads + emit; thread-safe), `FlowControl <: FlowContext`
  (authority to start a stage; thread-affine), and a SPLIT pair of stage-bound
  capability tokens (both in `tools`, `package orca`) — `InStage`, the SHARED
  half (`caps.SharedCapability`, fork-capturable): every `agent.*.run` /
  `FlowSession.run` (spend tokens, drive an agent) takes `(using InStage)`,
  and it is safe to capture into a `fork` (the reviewer fan-out's shared
  `InStage` capture is load-bearing); and `WorkspaceWrite`, the EXCLUSIVE half
  (`caps.ExclusiveCapability`, fork-opaque): every git write, `fs.write`, `gh`
  write, and progress-log write takes `(using WorkspaceWrite)`, and it must NOT
  cross a `fork` boundary (two concurrent forks racing on the same git index or
  progress log is exactly what this is meant to catch — ADR 0018 §6). A helper that does
  both (e.g. the lifecycle's `freshRun`, which names the branch via the cheap
  model AND performs the setup git writes) takes BOTH; `Flow`'s
  `recordAndCommit` instead mints its own tokens through the `RuntimeInStage`
  door. Only a `stage` body mints — and is handed both tokens together. Don't relax
  this: production mints go through `orca.RuntimeInStage.token()` /
  `orca.RuntimeInStage.workspaceToken()`, the single named door (a grep for
  `RuntimeInStage` is the whole whitelist of privileged callers);
  `InStage.unsafe` / `WorkspaceWrite.unsafe` themselves are called only by
  `RuntimeInStage` and tests. Don't call either `unsafe` directly outside that
  door, and don't drop a `(using InStage)` / `(using WorkspaceWrite)` to "make
  it compile" — thread it up to the nearest stage. `orcacaps.InStageNegativeTest`
  pins that a workspace mutation outside a stage fails to compile with the
  `WorkspaceWrite` message and an LLM run outside a stage fails to compile with
  the (distinct) `InStage` message.

  **Capture-checking status:** enforcement of the fork-boundary rule is per
  compilation unit and currently opt-in. The separation rejection fires only
  in files carrying the `captureChecking`/`separationChecking` language
  imports *and* only where the fork thunks are widened to the impure
  `() => T` element type — which `CheckedPar`'s `C^` signature forces at the
  one production call site, `ReviewLoop`'s reviewer fan-out (pinned by
  `CcNegativeCompileTest`; see `CheckedPar`'s scaladoc for the verified
  mechanics). Everywhere else — user flow scripts, examples, the rest of
  orca — the shared/exclusive split is convention, and there is no runtime
  guard on `WorkspaceWrite` (the R12 owner-thread assert covers only the
  `FlowControl`/`stage` surface). Ox itself is not yet capture-checked; once
  it is, rejection moves to `ox.fork` directly and `CheckedPar` is deleted.

- **Progress log + recovery.** A run commits `.orca/progress-<hash>.json` (hash =
  prompt, so the path is branch-independent) with one entry per completed stage;
  a re-run replays recorded entries and skips them. The header is untrusted on
  load — `orca.progress.RecoveryCheck` validates it (safe ref, prompt-hash match,
  protected-branch refusal) before any destructive git op.

- **Sessions.** `AgentBackend.sessions: SessionSupport[B]` is one final class
  built from two per-backend choices: durability —
  `SessionSupport.ephemeral(scheme)` (nothing survives a process restart; no
  backend uses it today) or `SessionSupport.durable(scheme, probe)` (all
  backends — sessions outlive the process, including across a restart; pi's
  live in `.orca/cache/pi-sessions/<id>/` and are probed for a `*.jsonl` whose
  transcript header pi's `--continue` accepts for the workDir, within the
  30-day retention (`PiSessionStore.resumable`, shared with the shell's resume);
  opencode persists sessions in its own global on-disk store independent of
  orca's per-run `opencode serve` process, so a fresh server spawned after a
  kill/restart resumes a committed `resumeWireId` the same way the file-probed
  backends do, live-verified 2026-07-08) — and the `IdScheme`: `ClientClaimed`
  (claude/pi — the client id IS the wire id, put on the wire at spawn) or
  `ServerMinted` (codex/gemini/opencode — the server mints the wire id, learned
  from the protocol and registered after the turn). `Agent` derives `willContinue` /
  `resumeWireId` / `registerResumeWireId` as `final` methods over the single
  `sessionSupport` hook, so a concrete tool can't wire one session operation
  while silently defaulting the others — that half-wiring is unrepresentable.
  `SessionId[B]` (the client-side handle) is split from `WireSessionId[B]`
  (what actually goes on the wire) — `SessionId#onWire` is the only
  client→wire crossing. `willContinue` stays a best-effort, non-destructive
  probe; when it can't confirm a live session the flow re-seeds, the uniform
  fallback that holds on every backend. A live conversation a PREVIOUS run
  opened (its record carries a `resumeWireId`) is told once, on its first turn
  here, that the tree holds only what earlier stages committed — the re-seeded
  case needs no telling, its preamble already says so.

  The user surface is three rungs (README "Sessions"): `agent.run` (one-shot)
  / `agent.chat()` (ephemeral `Chat`, fork-safe, `InStage`-only) /
  `agent.session(name, seed)` (durable `FlowSession`, flow-thread-only
  — the owner-thread assert in `FlowSession.run` enforces it at runtime, and
  the raw session-threading doors are `private[orca] runWithSession`, so
  ephemeral continuation is only reachable through a `Chat` handle).

  Sessions have explicit identity: `agent.session(name, seed)` keys an
  `orca.sessions.SessionRecord` by `orca.agents.SessionKey(name, stage)`. `name`
  is the role; `stage` is an `orca.StagePath` — `FlowBody`, or `Stage(path id)`
  for the stage the mint sits in — taken from `StageFrames` rather than supplied
  by the author. The persisted spelling (`""` for the flow body) is decoded in
  one place, `StagePath.fromValue`. Two stages therefore cannot name one
  session, and a per-task loop needs no per-task label. The whole key reaches
  `OrcaEvent.SessionCommitted`, the run manifest and the shell's session picker.
  Identity and label are separate here: `SessionKey.describe` renders a key for
  the run's own diagnostics (a stage path id carries `#0` suffixes), and a
  session reads to a person as its bare `name` — `SessionPicker.displayName` is
  the single home of that. A row's `(stage: ...)` segment is a DIFFERENT field,
  the stage the session was last active in, so `SessionPicker.mintedInTag`
  appends the minting stage to exactly those rows two lineages would otherwise
  share, and `orca continue --list` gives it a column. Neither half of the key is
  hashed or turned into a filename, and only `name` is validated (non-empty).
  Reordering or skipping *other* `session(...)` calls between runs doesn't
  re-key this one; renaming the stage a mint sits in does. Minting one name
  twice in one stage throws (`FlowControl.claimSessionKey`, the only door that
  MINTS a key — `SessionRecord.key` and `ManifestSession.mintedKey` rebuild one
  from persisted halves); re-minting on resume is the reuse path, since only
  this execution's keys are tracked. For a mint inside a stage that check is
  sound because a stage body is all-or-nothing: two mints of one name in one
  stage both execute or neither does. The flow body offers no such guarantee —
  two mutually exclusive mints there are never both claimed — which costs the
  warning, not correctness.

  Records live in `.orca/cache/sessions-<prompt hash>.json`
  (`orca.sessions.SessionStore`), under the same hash as the progress log, NOT
  in the log itself: a backend session id is a machine-local handle, and the
  committed log is erased back to the last stage commit by the failure
  teardown's `git reset --hard` — which is exactly the stage a resume re-runs,
  so a record in the log could never be read back. The cache survives that
  reset, its `git clean -fd`, and the resume-time stash, and follows the
  directory a `--worktree` run happens in. A missing or unreadable file costs
  nothing but a re-seed. `teardownSuccess` drops it with the log, so a later run
  of the same prompt opens fresh conversations.

  A mint sits wherever the session is used — inside the driving stage, or above
  the stages that share it. What R22 blocks is one specific route out of a
  stage: neither `FlowSession[B]` nor `SessionId[B]` has a `JsonData`, so
  neither can be a stage's result. A handle stashed in an in-memory `var` in
  stage A and read in stage B still compiles, and fails loudly with
  `NoSuchElementException` on the resume that skips A.
  Each record also carries the minting agent's `backend` tag, so
  `FlowLifecycle.rehydrateSessions` replays a resumed run's resume wire ids
  into the record's own backend's agent rather than always the lead
  (untagged/older records fall back to the lead; a tag matching none of the
  context's accessors is skipped, not guessed).

- **Tool enforcement.** `AgentConfig.tools: ToolSet` (ReadOnly/NetworkOnly/Full)
  and `autoApprove: AutoApprove` (All/Only) request a restriction, but each
  backend enforces it differently. `AgentBackend.enforcementCell(tools,
  autoApprove, dispatch)` answers with the guarantee actually achieved plus the
  reason — see `Enforcement`'s scaladoc for what the levels mean.

  The block below is RENDERED — a fresh-turn table, then the resumed turns that
  differ — by `runner/src/test/scala/orca/runner/EnforcementTableTest.scala`,
  which walks the full (backend × ToolSet × AutoApprove × dispatch) product.
  Don't hand-edit it: when the test fails, paste the block it prints.

  | tools, approve         | ClaudeCode | Codex         | Opencode | Pi         | Gemini     |
  |------------------------|------------|---------------|----------|------------|------------|
  | ReadOnly, *            | Hard       | Hard          | Hard     | Hard       | PromptOnly |
  | NetworkOnly, *         | Hard       | PromptOnly    | Hard     | PromptOnly | PromptOnly |
  | Full, All              | Hard       | Hard          | Ignored  | Ignored    | Hard       |
  | Full, Only(_) / Only() | Hard       | SandboxApprox | Ignored  | Ignored    | Ignored    |

  A resumed turn is classified the same, except:
  - Codex, Full, Only(_) / Only(): Ignored, not SandboxApprox

  Per-cell rationale lives in each backend's `*Args.enforcementCell`, with the
  CLI version and date of any probe behind it. A turn that asks for a
  restriction its backend can't apply mechanically gets an `EnforcementNotice`
  — see that class for what it says and how often.

  The table answers how strongly a tier's gate is held, not what the tier
  GRANTS. For `NetworkOnly` the grant is per-backend, and this list is
  hand-maintained — nothing renders or checks it:

  - claude: `WebFetch`/`WebSearch` (`ClaudeBackend.DefaultNetworkTools`; a flow
    can substitute its own via `claude.withNetworkTools(...)`) on `--tools` AND
    on `--allowedTools` — `--tools` only advertises, so a name missing from the
    approval flag comes back as a failed call. Plus the host-served GitHub
    issue/PR read (`GitHubMcpServer`).
  - codex: `network_access=true` inside the `workspace-write` sandbox — codex
    has no read-only-with-network sandbox.
  - gemini: `web_fetch` pre-approved through `--allowed-tools`.
  - opencode: `webfetch` left unset, so the server's own default decides
    (`ReadOnly` disables it by name).
  - pi: `bash` — pi has no web tool, and `bash` also writes.

- **Conversation events.** The event grammar (turn boundaries, `Option` tool
  names) is specified on `ConversationEvent`'s scaladoc and pinned per backend
  by `ConversationEventConformance` assertions in each module's tests.

- **Listener contract.** A listener that throws is logged at ERROR (with its
  stack) and announced once on stderr, then quarantined — permanently
  excluded from dispatch for the rest of the run. The remaining listeners
  still see every event and the flow itself always survives; see
  `EventDispatcher`.

## Testing approach

Build/test/format commands and the gated integration suites are in
[CONTRIBUTING.md](CONTRIBUTING.md). Unit tests use in-memory fakes
(`StubCliRunner` / `SpawnStubCliRunner`, `FakeAgent`,
`FakePipedCliProcess`, `TestFlowContext` / `TestFlowControl`) and the shared
`orca.testkit` fixtures — the `GitRepo` temp repo, `StubGitHubTool` (every `gh`
endpoint refusing, override the ones a suite reaches) and `PushlessGit` (the
real git with the remote-facing calls stubbed) — published via `tools %
test->test`; no network, no real filesystem outside `os.temp.dir()`.

### Debugging backend breakage

The coding-agent CLIs auto-update and have broken wire behavior before
(claude 2.1.207 rejected schemas declaring `$schema: …/2020-12` and began
surfacing `--json-schema` output as a visible `StructuredOutput` tool call).
When a flow breaks with no orca change, suspect the CLI first: reproduce
with a direct probe (e.g.
`claude -p "…" --model haiku --json-schema '…'`) before touching orca code,
then fix at the seam (`JsonSchemaGen`, the backend's conversation driver)
with a test pinning the observed wire shape.

### Iterating quickly

- Prefer plain `sbt <cmd>` in agent/non-interactive shells. Avoid
  `sbt --client` unless you know the persistent sbt server was started with
  the repo's direnv/JDK 21 environment; otherwise the client can attach to a
  stale server running a different Java and fail despite the current shell's
  `JAVA_HOME`.
- `sbt ~test` re-runs tests on save.
- Don't run `clean`. The incremental build is reliable and nothing here is
  known to go stale; a profiled run lost 49 minutes to 13
  `clean compile Test/compile test` cycles at 228 s each, against ~65 s for
  the same work incrementally.
- `sbt test` is what `.orca/settings.properties` configures — use it while
  iterating, and keep the `ORCA_INTEGRATION=1` suites (CONTRIBUTING.md), which
  shell out to real CLIs, for final verification.
- For capture-checking experiments, take flow's test classpath once with
  `CP=$(sbt -batch -error "export flow/Test/dependencyClasspath")`, then
  compile fixtures directly with
  `java -cp "$CP" dotty.tools.dotc.Main -classpath "$CP" -d out Fixture.scala`.
  Verify CC enforcement claims this way — empirically, against the pinned
  compiler — before editing docs or wrappers.
- Metals MCP is configured (`.metals/mcp.json`), so AI-assisted tooling can
  query real type info across modules.

## Conventions

### Scala style

- Braceless syntax; explicit return types on every public member.
- No class-level `var`s; mutable state stays in method bodies or
  `AtomicReference`-guarded test helpers.
- Opaque-type aliases for domain string labels (e.g. `Title`, `SessionId`).
- Recoverable failures return `Either[E, T]` where `E <: OrcaFlowException`;
  system failures throw. Use Ox's `.orThrow` at the call site when the
  failure case is genuinely unexpected.

### Code style

- Use proper packaging — related functionality lives in one package.
- A comment must earn its place: keep it succinct and include only what a
  competent reader can't get from the code, names, or signature — a non-obvious
  *why*, an invariant, or a side effect the types can't express. Don't restate
  the README, neighbouring comments, or the obvious, and don't narrate the next
  line. Default to fewer words; delete a sentence that adds nothing rather than
  rewording it.
- Scaladoc describes contract and intent; implementation notes go in inline
  `//` comments alongside the code.
- Comments state present-tense facts about how the code works now — never
  change history ("X no longer does Y").
- Don't explain Scala 3 mechanics (`@implicitNotFound`, sealed traits, given
  resolution, import semantics) in comments — assume the reader knows the
  language. Naming a mechanism is fine; teaching it is noise.
  Project-specific constraints and verified compiler-version-specific
  behavior do belong.
- Never cite development-plan labels (epic/finding numbers) in comments —
  plans are deleted after execution. Cite ADRs, code, or state the fact
  inline.
- Tests target exactly one scenario each.

### Review vocabulary

Three words for the review loop, used the same way in identifiers, prompt text,
screen output and the PR body:

- **finding** (`ReviewFinding`, `ReviewResult.findings`) — one problem a
  reviewer or the lint gate reported. `issue` is not a synonym: in this
  codebase it means a GitHub issue (`orca.tools.Issue`, `IssueHandle`).
- **declined** (`DeclinedFinding`, in `FixOutcome.declined`) — the fixer
  considered a finding and refused it, giving a reason. It names one way a
  finding stays open, not the set of everything that does. The wire shape the
  fixing agent fills, so it carries title and reason and nothing else.
- **open finding** (`OpenFinding`, `OpenFindings`) — a finding the run ends
  without resolving, paired with an `OpenReason`: declined, never reported on
  by the fixer, past the round cap, still failing lint, or from a review that
  could not run at all. This is what `reviewThenFix` and `reviewAndFixLoop`
  return, what later rounds' reviewers are shown, and what the PR body lists
  under "Open review findings".

Don't name the open set after one of its reasons: any such name misreports the
others. `OpenReason` is where the distinction lives — its `describe` is the only
place each reason's prose is written, so a test asserts the case, not the
sentence.

Dated records — an ADR's `Amendment (date)`, `docs/plans/*`, `docs/research/*` —
keep the words that were current when they were written. Rename in the code and
in undated prose; leave a dated record alone.

### Review-derived rules

The rules distilled from recurring review findings live in
[`.orca/reviewers/orca.md`](.orca/reviewers/orca.md) — the reviewer orca runs
against every Scala change here, discovered per
[ADR 0023](adr/0023-reviewer-discovery.md). Read it before writing code. Those
rules have no second copy: they change there or not at all.

That file also condenses rules the sections below own in full — comments,
capability tokens, `.orca` writes, subprocesses, review vocabulary, and 0.x
versioning. Change one of those and change the condensed line with it.

### Versioning (0.x)

Orca is 0.x: no backwards compatibility is owed anywhere.

- No default values on domain or persisted fields (case classes that travel
  through `JsonData`, progress-log/manifest types, config records). Every
  call site passes them explicitly — a default silently papers over a call
  site that forgot the field, which is exactly the bug class this rule
  catches.
- Never carry back-compat machinery — no defaulting-old-shape codec configs,
  no dual parse paths, no fixture tests pinned to a prior wire format. Change
  the shape and update every call site instead.
- Two deliberate exceptions, both live local data that has to survive an orca
  upgrade, where invalidating it costs a user-visible feature rather than a
  re-run. Don't "fix" either under this rule:
  - `ProgressLog`'s and `SessionRecord`'s tolerant decoding (documented at
    each definition), so a mid-run resume survives the upgrade — an in-flight
    run's log written by an older orca must still load. The records an older
    log carried are the one thing that does not: they lived in the log and now
    live in `.orca/cache/`, so such a run re-mints and re-seeds.
  - `RunManifest` (documented at its definition; ADR 0021 §8 amendment,
    2026-08-05), so the shell still offers "continue a session" from a manifest
    an older orca wrote. Changes to it are additive only, still with no
    defaults; the fixtures that hold that — `RunManifestGoldenTest`'s frozen
    files, and `ManifestReaderTest`'s verbatim older manifest body — are
    sanctioned, not violations of the no-fixtures rule above. `CostRecord` is
    not covered: nothing reads a cost log, and no fixture pins it.

    Breaking additive-only is possible but owed an argument, because the golden
    fixtures cannot catch a rename of a field they never carried. A PR that
    renames or retypes a manifest field says what an older manifest loses by
    it, and the answer has to be a degraded listing rather than a resume that
    misfires: the five required fields and `wireId` — what the shell
    dereferences and execs — are not renamed. ADR 0021 §8 records each break.
    The one so far is `sessionDetail` → `sessionStage` (ADR 0018 §2.6 stage
    keying), which costs an older manifest's per-task sessions their separate
    picker lineages.
- Comments (see Code style above) never narrate this either: no "was
  previously", "renamed from", "kept for compat" — state what the field/type
  means now, not its history.

### Library

- Tool event sinks take `OrcaListener` (default `OrcaListener.noop`).
- Domain helpers that bundle an LLM brief follow
  [ADR 0010](adr/0010-prompts-and-helpers-convention.md): sibling
  `XxxPrompts` object + `instructions: String = …` parameter.
- Subprocesses launched from a tool **must** capture stderr — go through
  [`subprocess.QuietProc.call`](tools/src/main/scala/orca/subprocess/QuietProc.scala)
  or a `CliRunner`. os-lib defaults `os.proc(...).call(...)`'s `stderr` to
  `Inherit`, which lets subprocess output bypass the renderer's StatusBar
  and tear the spinner row.
- Every `spawnPiped` child carries a unique `ORCA_TURN_COOKIE`
  (`orca.sweep.EnvCookie`). At turn teardown `EnvCookieSweep` scans
  `/proc/*/environ` for it and REPORTS what is still running — the backstop for
  work an agent detached from orca's process tree, which no parent-link
  teardown can reach. Report-only unless `ORCA_SWEEP_KILL=1`; Linux only, and
  silently inert elsewhere (nothing to act on, so nothing is said).
- Any filesystem write under `.orca/` **must** go through
  `OrcaDir.ensureRoot`/`ensureCache`, which refuse a symlinked `.orca` or
  `.orca/cache` component (`OrcaDir.abortIfOrcaComponentSymlink`) before
  creating or writing through it — a committed symlink (git mode 120000)
  would otherwise redirect orca's writes outside the working tree, since orca
  runs flows against arbitrary cloned repos. Prefer `os.write` (`CREATE_NEW`,
  refuses an existing symlink at the leaf) over `os.write.over` (follows a
  leaf symlink); if `.over` is unavoidable, guard the path with `os.isLink`
  first. The check is lstat/no-follow and runs at the earliest `.orca` touch
  (`FlowLock.acquireWorkdir` → `ensureCache`), ahead of any mutation.

The `direct-style-scala` plugin codifies the Scala-style bullets; re-reading
its chapters before a non-trivial change is recommended.

## Publishing and local testing

See [CONTRIBUTING.md](CONTRIBUTING.md): `sbt publishLocal` (with a
`~publishLocal` watch loop) installs the modules into `~/.ivy2/local`, and
its "Testing the `orca` CLI with local changes" section has the isolated
scratch-project recipe for running a locally built shell, interactively and
headless.
