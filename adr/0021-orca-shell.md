# 0021. Orca Shell

Status: Proposed · Date: 2026-07-18
Related: [ADR 0019](0019-project-stack-settings.md) (settings-file format,
committed-by-default `.orca/`, `OrcaDir`), [ADR 0020](0020-configurable-role-agents.md)
(role agents, the global settings file the wizard writes), [ADR 0018](0018-stage-bound-flow-runtime.md)
(stage-bound runtime; its progress log's atomic-write pattern is what the
manifest writer reuses),
[ADR 0004](0004-module-layout.md) (module layout, extended with `shell`).
Research record: `docs/research/shell/00-research-plan.md` and result files
01–08 (topics 1, 2, 3, 7, 8 adversarially reviewed by proponent/skeptic
pairs; where a body and its `## Skeptic review` disagree, the skeptic section
is authoritative).

## Context

Orca is a library: users write flow scripts and run them with
`scala-cli run flow.sc -- "task"`. That stays. But there is no interactive
entry point — a new user must find an example flow, learn the settings file
by hand, and gets no help discovering flows that exist in their project, no
way to author a new flow with an agent's help, and no way to pick up an agent
conversation a flow started. The Orca Shell is an add-on interactive terminal
program providing: a first-run welcome wizard (harness auto-detection +
preferred harness per role, written to the global settings file), then a main
menu — run a discovered flow, create a new flow using a harness, continue a
session started by the last flow run, re-configure, exit.

Constraints from the brief: the shell is a separate module, its own
executable "launching scala-cli, where the shell is really implemented";
direct `scala-cli run` of flow scripts must keep working; a preliminary
concern was process-global state leaking between consecutive flow runs
started from one shell.

## Decision

### 1. Module and distribution

A new sbt module `shell`, published as `org.virtuslab::orca-shell`, main
class `orca.shell.Main`, depending on `runner` (published `orca`) — for the
settings machinery (ADR 0019/0020), `BackendTag`, `OrcaDir`, and the run-
manifest types of §8; it does NOT call into the flow runtime (§2). `runner`
keeps zero knowledge of the shell, so the add-on constraint holds
structurally. Shell code lives under the `orca.shell` package, so
`private[orca]` members are already accessible; the two `private[settings]`
members the wizard needs (`SettingsError`, `AgentKey`) widen to
`private[orca]`.

The executable is a ~5-line shim script installed to `~/.local/bin/orca` by a
curl-able `install.sh`:

```bash
#!/usr/bin/env bash
exec scala-cli run --jvm 21 --quiet \
  --dep "org.virtuslab::orca-shell:latest.release" \
  --main-class orca.shell.Main -- "$@"
```

Verified: scala-cli runs a main class straight from a Maven dependency with
no sources, and `latest.release` resolves (research 04). The shim therefore
never needs version bumps; the shell prints its resolved version at startup
(manifest `Implementation-Version`, the existing `OrcaBanner.version`
pattern — no BuildInfo plugin). The README additionally documents the pinned
one-liner (bumped by `updateDocs`) for CI and the install-averse. Rejected:
coursier app descriptors and `scala-cli --power package` as the launch story
(both bypass scala-cli at launch and add prerequisites/build surface;
research 04 §2b–2c) — a URL-based `cs install` channel JSON may be added
later as a convenience.

All modules release under one dynver version, so shell version = orca
version with no extra plumbing.

### 2. Flow execution: supervised subprocess, shell-forced orca version

**The shell runs a selected flow as a `scala-cli run` child process that
inherits the terminal — not in the shell's own JVM.** This deliberately
overrides the brief's literal "run in-process" wording; the brief's intent —
one seamless terminal experience, the flow run integrated into the shell
invocation — is preserved. The recorded case (research 02):

- Measured warm start of `scala-cli run` with orca on the classpath is
  0.6–0.9 s — noise against multi-minute flows (Bloop's cache skips
  recompiles).
- No shell menu item consumes any capability only in-process execution
  provides (no live typed-event UI, no shared warm agents — backends rebuild
  per run even in-process).
- Every mechanism the workable in-process design needs — `System.exit(1)`
  containment, JLine terminal handover, forced dependency versions, global-
  state hygiene — exists to buy back properties a child process has natively.
  The global-state audit (research 01) found genuine one-run-per-process
  assumptions — from `flow()`'s exit call to the workdir `flow.lock` whose
  staleness check would see the long-lived shell's own live PID and never
  self-heal. Subprocess execution makes all of them moot; research 01 is the
  complete inventory and the checklist if in-process is ever revisited.
  Revisit condition: a roadmap item that needs live typed event
  streaming into a shell-owned renderer (then via the hybrid design in
  research 02 §5).

Launch mechanics: the shell's working directory is the cwd it was started
from, and that cwd is the `workDir` for everything project-scoped — project
flows, manifests, session resume — exactly as `os.pwd` is for directly-run
flows; there is no directory picker. After the user selects a flow, the shell
shows its description and prompts for the task text, appended after `--`
(FlowLauncher's argv supports a verbose flag; v1's menu does not yet expose
it) — the same argv a direct
`scala-cli run flow.sc -- "task"` gets. Started outside a project, the shell
still works: the project tier lists nothing, continue-session is disabled
until a manifest exists, and built-in/global flows remain listed — a flow
launched in a non-repo directory fails with the flow's own existing clear
error; the shell adds no pre-check.

Version handling: by default the shell passes
`--dep org.virtuslab::orca:<shellVersion>`, which is documented and verified
(both directions, scala-cli 1.14.0) to REPLACE the script's
`//> using dep` orca pin. This guarantees the run-manifest writer of §8
exists in the child regardless of the script's pin. If the forced compile
fails (a genuinely API-incompatible flow), the shell offers to re-run
honouring the script's own pin, with a visible notice that session
continuation will be unavailable. A canary test in `shell` pins the
`--dep`-overrides-directive semantics against scala-cli upgrades.

Subprocess obligations (research 02 §S3, all mandatory):

- The child shares the foreground process group, so Ctrl-C reaches both:
  while a child runs, the shell parks under a SIGINT-tolerant read and
  resumes its menu after the child exits.
- Terminal attributes are restored after every child exit (a child crashed
  in raw mode must not wedge the menu).
- Cancel = SIGINT reaches the entire tree via the shared foreground process
  group (no separate signal-forwarding code): the shell itself ignores the
  SIGINT and survives to resume its menu once the child tree exits. A
  descendant that ignores SIGINT and outlives its parent (e.g. an orphaned
  `opencode serve`) is a recorded residual — explicit escalation (a
  `descendants()` tree-kill) is deferred until observed in practice.
- Compile failures are distinguished from flow failures (exit code + the
  manifest's `outcome` field; optionally a `scala-cli compile` pre-flight).

`flow()` itself is untouched: its exit-based CLI contract, locks and logging
remain correct for the one-flow-per-process model it was designed for.

### 3. Shell UI

JLine 3.30.x plus `org.jline:jline-console-ui` (the ConsoleUI merge, 73 KB,
same release train as the jline the runner already pins — bump the shared pin
from 3.28.0). It provides arrow-key select lists, confirm/input prompts and a
`Function`-based multi-step wizard with back-navigation; raw mode is scoped
per prompt; Ctrl-C surfaces as a catchable `UserInterruptException`. Verified
by spike: compiles with the `jline-builtins` exclusion and navigates
end-to-end under a pty (research 03).

Conditions: every ConsoleUI prompt is tty-gated — on non-tty stdin ConsoleUI
NPEs (reproduced), so a plain numbered-menu `readLine` fallback ships as a
requirement, not an option; navigation is documented as arrow keys plus
ConsoleUI's `e`/`y` scroll bindings — there are no vi `j`/`k` bindings.
Runner-up cue4s was rejected on source-verified grounds (session-scoped raw
mode, POSIX Ctrl-C kills the JVM, JNA dependency); recheck at its 1.0.

The main menu, in fixed order: Run a flow · View a flow · Edit a flow ·
Create a new flow · Continue a session · Re-configure · Exit. Unavailable
items (continue with no manifests, §8) render disabled with the reason, not
hidden.

### 4. Welcome wizard and settings

First run = the global settings file (`$XDG_CONFIG_HOME/orca/settings.properties`,
ADR 0020) is absent, or parses cleanly with all three role keys unset. A
malformed file is NOT first-run: the wizard surfaces the parse error and
offers a rewrite (that file otherwise aborts every flow run, so the wizard is
the natural repair point).

Step 1 — detection: for each harness in `BackendTag` declaration order
(ClaudeCode, Codex, Opencode, Pi, Gemini — the order every wizard list
uses), probe `bash -c 'command -v -- "$1"' bash <name>` (the exact
`StackDiscovery` shape, extracted into a shared helper; binary names all
equal the settings names). Undetected harnesses stay selectable — detection
only drives pre-selection and a `✓ found` decoration. Probing is always-on
rather than the brief's optional step: it is instant and side-effect-free,
so a skip option would only produce worse defaults. No `--version` probing
in v1 (consistent with discovery's "resolves ≠ is-right" stance).

Step 2 — preferred harness for planning / coding / review: harness only, no
model picker. Every bare harness resolves to a sensible default model, and
orca deliberately passes model ids through unvalidated — a curated list
would drift (research 06 §3). The wizard's closing note tells users the file
is hand-editable (`harness[:model]`); the harness-change/mint-fresh-sessions
detail (ADR 0020 §8) was dropped from that note per user feedback — unnecessary
detail at write time.

Write path: file absent → fresh write with a wizard-appropriate header (not
`SettingsFile.Header`, whose stack-discovery wording doesn't apply) and
explicit `role = harness` lines (explicit lines keep the wizard from
re-triggering and make the role announcement say `(global)` honestly). File
present (the re-configure menu item) → surgical line-level update: replace or
append only the three agent-key lines, preserving comments and blanks;
pre-select current values from `SettingsFile.parse`; keep an existing
`:model` pin when the harness is unchanged, drop it when the harness changes.
Both shapes live beside `render` in `SettingsFile`, keeping format knowledge
in one file, with a render/parse round-trip test. The wizard writes ONLY the
global file — the project file is discovery's territory (ADR 0019/0020).

**Re-discovering project stack settings** (a distinct top-level menu item,
"Re-discover project stack settings", not folded into Re-configure — stack
commands and role agents are unrelated settings, and reusing Re-configure
would force an extra sub-menu layer onto its existing, simple "re-run the
wizard" behavior): the shell has no `Agent`/`InStage` plumbing to invoke
`StackDiscovery` itself, so it doesn't try to. Instead it surgically strips
every stack line (`format`/`lint`/`test`, live or `#`-commented) from
`{workDir}/.orca/settings.properties` via `SettingsFile.stripStackLines` — a
new helper that reuses `hasStackLines`'s own line predicate, so it can never
disagree with `FlowLifecycle.readSettings`'s re-discovery trigger — leaving
role-agent keys, blank lines, and unrelated comments untouched. That trigger
already re-runs discovery whenever the file names no stack key at all, so the
strip alone is enough: no new discovery path is added. The action reads the
project file passively (no `.orca` creation on a bare view), guards the write
with the same `OrcaDir.assertNoOrcaSymlinks` check `selectFlow` uses, shows
the current stack commands, and requires a `[y/N]`-defaulted confirm before
writing — a no-op with a one-line explanation when the file is absent or
already stack-line-free, and an abort on a malformed file (the same message
`FlowLifecycle.readSettings` would show) rather than a blind rewrite.

### 5. Flow discovery

Three tiers, one listing:

- **Project**: `{workDir}/.orca/flows/*.sc` — committed, consistent with ADR
  0019's committed-by-default `.orca/` (ephemera live in the self-ignoring
  `cache/`). Collision-free today; created via a new `OrcaDir.flowsPath` /
  `ensureFlows` so the symlink guard applies. Prior art: `.github/workflows/`,
  `.claude/commands/`.
- **Global**: `$XDG_CONFIG_HOME/orca/flows/` — config home, not data home
  (user-authored, dotfile-portable; fish-functions precedent), sharing
  `GlobalSettings`' config-home resolution.
- **Built-in**: shipped with the shell (§7).

Precedence project > global > built-in, keyed by filename — one menu row per
name showing the winner's description and origin label, with a
`shadows <tier>` annotation so shadowing is visible; no UI to run a shadowed
tier in v1.

Description rule: the first line, within the file's leading block of blank
lines / `//` comments / `//>` directives, that is a `//` comment (not a
`//>` directive) whose text after the marker strips to something non-empty —
a bare or whitespace-only `//` line is skipped, never returned as an empty
description. Convention (taught to authors and used by §9's
prompt): the description is line 1. Verified against scala-cli 1.14.0 that
comments before or between directives leave the directives honoured. Each
built-in flow gains such a line (drafted in research 05 §4). A flow in any
tier remains directly runnable with `scala-cli run` — the shell adds
discovery without becoming mandatory.

### 6. Viewing and editing flows

Two further menu items operate on the same discovery listing (§5).

**View** prints the flow with Scala syntax highlighting and redraws the menu.
Highlighting is free: `org.jline.builtins.SyntaxHighlighter` (nanorc-based)
ships inside the `org.jline:jline` bundle jar already on the classpath, so
the `jline-builtins` exclusion stands and no dependency is added. Orca
bundles its own ~20-line `scala.nanorc` resource — GNU nano and jline ship
none, and the common scopatz/nanorc definitions are GPL-3.0,
license-incompatible with Apache-2.0. No pager in v1 (flows are 100–300
lines); jline's `Less` is the upgrade path if needed. Non-tty output falls
back to plain print. View works on all three tiers (built-ins read from the
extracted cache).

**Edit** opens the flow in the user's editor: `$VISUAL` > `$EDITOR` > `vi`
(the git/gh convention; no `ORCA_EDITOR` — nothing orca-specific about the
choice), spawned git-style as `sh -c '<editor> "$@"' <editor> <path>` with
inherited tty, so editor values carrying arguments (`code --wait`) work; the
§2 subprocess obligations (attribute restore, SIGINT handling) already cover
the child. Project and global flows are edited in place. A built-in is never
edited in its cache copy (the next extraction would overwrite it): edit on a
built-in offers "customize" — copy to the project or global tier, then open
the copy, which thereafter shadows the built-in under the standard
`[shadows built-in]` label (§5).

### 7. Built-in flows

`examples/*.sc` (implement, implement-interactive, implement-enhanced, epic,
issue-pr, issue-pr-bugfix) move to a top-level `flows/` directory;
`examples/runnable/` stays as seed harnesses with `FLOWS_DIR` updated. The
`shell` module embeds `flows/*.sc` as jar resources under a namespaced prefix
via a resource generator, plus a generated index resource (jars aren't
listable). At runtime the shell extracts them (keyed by version) to
`$XDG_CACHE_HOME/orca/shell/<version>/flows/` — a real path for scala-cli to
run, doubling as the browsable "crib from the built-ins" location.

Version sync is by construction: `flows/` is added to the root `updateDocs`
file set, so the release commit that CI builds the jar from already pins
`//> using dep "org.virtuslab::orca:<thatVersion>"`. A dev build (dynver
`+`-suffixed or `dev`) rewrites the extracted flows' pins to its own version
and injects `//> using repository ivy2Local` (the `_seed_lib.sh --local`
treatment), warning at startup that built-ins run against the locally
published build. GitHub-raw
fetching at the release tag was rejected: network-dependent, needs runtime
tag mapping, and its one advantage — updating flows without a release — is
an anti-feature for built-ins (research 04 §4).

### 8. Session tracking and continuation

**Library side** (this is the one change to the published `orca` library):
a new event, emitted at the Agent layer — `BaseAgent`'s autonomous
`runWithSession`, `DefaultAgentCall`'s structured path, and beside the
interactive `sessions.register` — carrying
`(backend wireName, clientId, persistableWireId, agent name, agent role)`.
The backend commit door can't produce it (no agent/role context there), and
`persistableWireId` (not the raw drained wire id) makes pi's non-resumable
temp-dir sessions report as such for free. The event is named
`SessionCommitted`: it fires when a session's first turn commits — accurate
also for entries that commit but are not resumable (pi). A
runner-side listener (always attached, like `LoggingListener`) maintains the
stage stack from stage events, joins durable-session names from the progress
log by `clientId`, and writes the manifest.

**Manifest**: one JSON file per run at
`.orca/cache/runs/<startedAt>-<pid>.json` (self-gitignored cache; at most
one writer per workdir thanks to `FlowLock`), schema v1 per research 08 §4:
`manifestVersion` (hard gate — newer than the shell understands ⇒ skip with
a message), `orcaVersion`, `flow` (populated from the `ORCA_FLOW_NAME` env
var the shell sets before exec'ing the child — the flow's filename is
unknowable in-library; `None` for direct `scala-cli run` invocations),
`workDir`, `pid`, timestamps, `outcome`, and per session: harness, wireId,
`resumable` + reason, agent, role, stage, sessionName, kind, first/last-seen.
`kind` is derived at the listener: `durable` when the `clientId` joins a
progress-log `SessionRecord`, else `oneShot` — the event layer cannot
distinguish interactive calls today, so those land as `oneShot` (recorded
limitation; the `interactive` value is reserved). Written
atomically (the `ProgressStore` temp+move pattern) on every session event
and stage transition, `outcome: "running"` until `flow()`'s `finally`
finalizes it — so a crashed or killed flow still offers its sessions
(stale-`running` with a dead pid is treated as crashed). The writer prunes
old manifests (keep the last 20). One-shot runs are included: every one-shot
IS a durable harness session on claude/codex/gemini/opencode (research 08
skeptic §2); internal `quietTextTurn` calls bypass the emission point and
are excluded automatically.

**Shell side**: after a flow run (and on entry, from existing manifests,
newest first) the "continue a session" item lists one row per durable
`(agent, sessionName)` lineage — `★ <sessionName> — latest (stage: <stage>)
[<harness>]`, deduped across every run to just the lineage's most-recently-
active occurrence — plus two collapsed-by-default expander rows ("show N
earlier occurrences", "show N one-shot sessions") revealing everything else
in place when picked (research 08 items 7+8: the naive one-row-per-manifest-
session listing floods the picker with same-named one-shot calls — Plan-stage
runs, reviewer-selection picks, reviewer `chat()` turns — since only durable
`agent.session(...)` calls carry a distinguishing `sessionName`). It is
disabled with a hint when no manifests exist — including the version-skew
case where the flow ran under a pre-manifest orca pin (§2's fallback path).
Resume execs
the harness's own interactive UI from the manifest's `workDir`
(claude/gemini/opencode scope session lookup by cwd/project; codex's by-id
resume is global, but the resumed context still references that directory):

| harness | command | confidence (research 08, verified against installed CLIs) |
|---|---|---|
| claude | `claude --resume <uuid>` | high — headless-created sessions resume interactively |
| codex | `codex resume <thread-id>` | medium-high — exec→TUI crossover empirically checked during implementation |
| opencode | `opencode --session <ses_…>` | high — TUI shares `opencode serve`'s store |
| gemini | `gemini --list-sessions` → match uuid → `gemini --resume <index>` | medium — `--resume` takes latest/index, not uuid |
| pi | greyed out: "pi sessions are deleted with the run's temp dir" | future: move `--session-dir` under `.orca/cache/` |

### 9. Creating a new flow with a harness

Menu flow: pick global vs project target upfront (fixes the save path:
`$XDG_CONFIG_HOME/orca/flows/<name>.sc` vs `{workDir}/.orca/flows/<name>.sc`),
describe the flow's goal, pick a harness (default: the configured coding
agent); the shell then execs that harness's interactive UI with an initial
prompt.

How the agent learns the API (research 07): the shell ships the README (the
project's single, self-contained API reference — only ~4% of it is non-API
content, so no condensed subset doc) and two example flows (`implement.sc`,
`implement-interactive.sc`) as jar resources, and extracts them INTO the harness's workspace — for project
flows under `{workDir}/.orca/cache/orca-api-<version>/`; for global flows
the harness is launched with cwd `~/.config/orca/` and the material extracted
beneath it. Out-of-workspace absolute paths were refuted as the mechanism:
claude/opencode prompt for approval and gemini hard-fails on them. Rejected:
link-only (codex can't fetch pages; pi has no web tool), a curated API
subset (second doc to keep in sync, ~no token savings), and cellar as the
primary (real and active, but milestone-stage and teaches signatures, not
the authoring model — at most one optional prompt line when detected on
PATH).

The initial prompt states: the goal and target path; the verbatim
`//> using` header with the shell's orca version; the line-1 `//`
description convention (§5); pointers to the extracted README and examples;
"verify with `scala-cli compile <path>`"; and the caveat that fork/ordering
rules are enforced at runtime, so the README's authoring rules must be
followed beyond what the compiler catches. A tag-pinned raw README URL is
included only as a last-resort fallback line.

## Non-goals

- Windows support beyond the existing `bash -c` contract (consistent with
  ADR 0019/0020).
- A model picker in the wizard; model pins remain hand-edits.
- In-process flow execution (revisit condition in §2).
- `cs install` / native-image packaging at launch (possible later; §1).
- Running a shadowed flow tier from the menu (use `scala-cli run <path>`).
- A directory picker — the shell operates on the cwd it was launched from.
- An in-shell pager for view-a-flow — v1 prints the highlighted source;
  jline's `Less` (already on the classpath) is the upgrade path (§6).
- pi session continuation (feasible later via a `--session-dir` move).
- Live streaming of a running flow's typed events into shell-owned UI — the
  child owns the terminal while it runs.

## Consequences

- New published artifact `orca-shell`; `runner` gains the session event, the
  manifest-writing listener, and the shared PATH-probe helper — this sets
  the **minimum orca version for shell session-continuation**, which the
  shell documents and (by forcing `--dep`, §2) normally guarantees anyway.
- `examples/*.sc` move to `flows/` with description lines added; seed
  harnesses' `FLOWS_DIR`, README links, and the `updateDocs` file set are
  updated in the same change.
- The shared jline pin moves to 3.30.x (runner + shell); the shell adds a
  bundled `scala.nanorc` resource (own-written — no license-compatible one
  exists to vendor) for view-a-flow highlighting.
- `SettingsError` and `AgentKey` widen from `private[settings]` to
  `private[orca]`; `SettingsFile` gains the global-file write/update methods.
- `OrcaDir` gains `flowsPath`/`ensureFlows`; `.orca/cache/` gains `runs/`
  and `orca-api-<version>/` entries (both auto-ignored by the existing
  cache setup).
- Verification obligations carried into the implementation plan: the
  `--dep`-overrides-pin canary test, the codex exec→TUI resume check, the
  gemini index-resume check, and the interactive terminal-matrix pass for
  ConsoleUI (tmux/emulators, Ctrl-C, harness-TUI handoff).
