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
curl-able `install.sh`, which also bootstraps `scala-cli` itself via its
official installer if it's missing:

```bash
#!/usr/bin/env bash
exec scala-cli run --jvm 21 --quiet --verbose \
  --dep "org.virtuslab::orca-shell:latest.release" \
  --main-class orca.shell.Main -- "$@"
```

Verified: scala-cli runs a main class straight from a Maven dependency with
no sources, and `latest.release` resolves (research 04). `--quiet`
suppresses coursier's fetch-progress chrome (which otherwise races the
wizard's first prompt paint) — on a cold cache that is a ~900-line wall of
`Downloading`/`Downloaded`/`Failed to download`, the failed ones being
expected repository fallback probes. `--quiet` alone would go too far:
scala-cli derives both the coursier logger AND its verbosity from that one
flag, and at verbosity -1 it prints no build exception, so a dependency that
fails to resolve exits nonzero in complete silence. `--verbose` restores
verbosity 0 while leaving the fetch log off — resolution errors print again
(verified against scala-cli 1.15.0). Both flags together are one setting;
`FlowLauncher` passes the same pair when it spawns a flow. The shim
therefore never needs version bumps; the shell prints its resolved version
at startup (manifest `Implementation-Version`, the existing
`OrcaBanner.version` pattern — no BuildInfo plugin). The README additionally
documents the pinned one-liner (bumped by `updateDocs`) for CI and the
install-averse. Rejected: coursier app descriptors and
`scala-cli --power package` as the launch story (both bypass scala-cli at
launch and add prerequisites/build surface; research 04 §2b–2c) — a
URL-based `cs install` channel JSON may be added later as a convenience.

All modules release under one dynver version, so shell version = orca
version with no extra plumbing.

> **Amendment (2026-07-27).** The shim additionally passes `--workspace
> "${XDG_CACHE_HOME:-$HOME/.cache}/orca/shell/workspace"` (created with
> `mkdir -p` first): without it, this no-input `--dep`+`--main-class`
> invocation wrote `.bsp/scala-cli.json` and `.scala-build/` into whatever
> directory `orca` was launched from — the user's project — where a later
> run's `ensureClean` auto-stash (§2) would sweep it up alongside real
> changes. The README/AGENTS.md recipes that quote the invocation carry the
> same flag.

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
still works: the project tier lists nothing, continue-session is absent
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

The task/goal/fork prompt reads multi-line: Enter submits, Alt+Enter or
Shift+Enter inserts a literal newline, and a paste with embedded newlines
lands intact — `runner`'s `MultilineLineReader` owns the shared JLine widgets
and kitty-keyboard-protocol handling, reused as-is by the runner's own
ask-user prompts during a flow.

The main menu, in fixed order: Run a flow · View a flow · Edit a flow ·
Create a new flow · Continue a session · Re-configure · Exit.

> **Amendment (2026-07-27).** Inapplicable items are absent, not shown
> disabled: Continue a session appears only once a manifest exists (§8),
> matching Resume interrupted run's presence rule.

Shell-voice output — the lines the shell prints on its own behalf, as opposed
to prompts or a flow child's own output — carries a single `◆` glyph
(`ShellOutput.info`/`error`/`section`), distinguishing it from the flow
runtime's own glyph family (`⏺`/`●`/`▶`/`▸`).

> **Amendment (2026-07-27).** "Resume interrupted run" is inserted right after
> "Run a flow", shown only when `.orca/progress-<hash>.json` is present on the
> current branch — failure teardown (ADR 0018) keeps the log and stays on the
> branch, so its mere presence IS the detection signal, re-checked on every
> menu redraw. Selecting it relaunches the recorded flow with the recorded
> task text verbatim, through the same path "Run a flow" uses, no
> re-prompting — the byte-identical text a resume needs, since the log's
> resume check keys on a hash of it. Unlike Continue, the item is ABSENT
> (not disabled) when there's nothing to offer: no log, an unparseable one, or
> one whose header doesn't record a flow name (a run started outside the
> shell) — the simpler choice over a partial pick-the-flow-and-prefill
> fallback. Multiple unfinished logs (different prompts, one branch) offer
> only the newest by mtime.

> **Amendment (2026-08-27).** The scan spans the checkout the shell was started
> in plus the worktrees orca made FOR THAT CHECKOUT — the ones under its own
> `.orca/worktrees/`, matching where the data lives, since `.orca/` is
> per-checkout. So the checkout that made the worktrees sees itself and all of
> them, while a worktree, whose own `.orca/worktrees/` is empty, sees only
> itself; surveying every run means running the shell from the checkout the
> worktrees hang off. A `--worktree` run leaves
> its progress log inside its own tree, so a shell scanning only its own
> directory would either offer nothing or offer an older run. The
> newest-by-mtime rule now orders across all of them, and a directory that
> cannot be read costs only its own logs. Discovery is git's own worktree list
> (`Worktrees.list`, resolved at the call site so the scan itself takes plain
> directories and stays testable without a repository), filtered to children of
> `.orca/worktrees/` and capped at the 20 most recently used (orca never removes
> a worktree, and these scans run per redraw) — a worktree checked out to review
> someone else's branch
> carries that branch's committed progress log, and its recorded task text is
> what the offer would hand an agent. The progress-log scan deliberately does
> NOT descend into `.orca/worktrees/`; it stays one level deep.
>
> The offer carries the directory its log was found in, names it in the menu
> label when it is not the shell's own, and the resume RUNS there. No
> shell-voice notice on top: the child recognises an orca worktree from its own
> working directory rather than from the flag, so its closing summary already
> names the tree and offers a `git -C` diff for it. Not `--worktree`: that flag re-derives a path from the task text, which
> is the directory holding the log only when the log was already in an orca
> worktree of that exact prompt — otherwise it would silently start a fresh run
> in a tree with no log, leaving the interrupted one behind.

> **Amendment (2026-08-01).** A `branch: <name>` line prints directly above
> the menu prompt, re-read on every redraw (like Continue's manifest listing
> and the resume check) so it stays true after a flow run switches branches —
> which is why it lives in the menu loop rather than the startup config
> summary (§4). Best-effort: outside a git repo, or if git fails, the line is
> omitted; a detached HEAD renders `(detached HEAD)` rather than git's literal
> `HEAD`.

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

Step 2 — preferred harness for planning / coding / review, then a model step
for that harness. claude and codex get a curated select (values are
CLI-resolved ALIASES — `fable`/`opus`/`sonnet` for claude,
`gpt-5.6-sol`/`-terra`/`-luna` for codex — not raw model ids, so the list
can't drift the way research 06 §3 worried a curated list would: the CLI
resolves the alias itself), plus "enter manually…" (free text) and "harness
default" (no pin) rows. opencode / pi / gemini get free text only, as
research 06 §3 anticipated for any future model step; blank input means no
pin. Preselection: planning defaults to `fable`/`gpt-5.6-sol`, coding/review
to `opus`/`gpt-5.6-sol`; re-configure preselects an existing pin's curated
row (or "enter manually" prefilled with it), and picking "harness default"
clears the pin. The wizard's closing note tells users the file is
hand-editable (`harness[:model]`); the harness-change/mint-fresh-sessions
detail (ADR 0020 §8) was dropped from that note per user feedback — unnecessary
detail at write time.

Write path: file absent → fresh write with a wizard-appropriate header (not
`SettingsFile.Header`, whose stack-discovery wording doesn't apply) and
explicit `role = harness` lines (explicit lines keep the wizard from
re-triggering and make the role announcement say `(global)` honestly). File
present (the re-configure menu item) → surgical line-level update: replace or
append only the three agent-key lines, preserving comments and blanks;
pre-select current values from `SettingsFile.parse` (Step 2 re-asks the model
explicitly rather than silently carrying a pin forward). Both shapes live
beside `render` in `SettingsFile`, keeping format knowledge
in one file, with a render/parse round-trip test. The wizard writes ONLY the
global file — the project file is discovery's territory (ADR 0019/0020).

**Re-discovering project stack settings** (a distinct top-level menu item,
"Re-discover project stack settings", not folded into Re-configure — stack
commands and role agents are unrelated settings, and reusing Re-configure
would force an extra sub-menu layer onto its existing, simple "re-run the
wizard" behavior): the shell has no `Agent`/`InStage` plumbing to invoke
`StackDiscovery` itself, so it doesn't try to. Instead it surgically strips
every LIVE stack line (`format`/`lint`/`test`, including an explicit `off`)
from `{workDir}/.orca/settings.properties` via `SettingsFile.stripStackLines`
— a new helper that reuses `hasStackLines`'s own line predicate, so it can
never disagree with `FlowLifecycle.readSettings`'s re-discovery trigger —
leaving role-agent keys, blank lines, and comments (inert, ADR 0019 amendment
2026-07-26) untouched. That trigger
already re-runs discovery whenever the file names no stack key at all, so the
strip alone is enough: no new discovery path is added. The action reads the
project file passively (no `.orca` creation on a bare view), guards the write
with the same `OrcaDir.assertNoOrcaSymlinks` check `selectFlow` uses, shows
the current stack commands, and requires a `[y/N]`-defaulted confirm before
writing — a no-op with a one-line explanation when the file is absent or
already stack-line-free, and an abort on a malformed file (the same message
`FlowLifecycle.readSettings` would show) rather than a blind rewrite.

> **Amendment (2026-07-25).** "Edit settings" (menu and `orca config --edit
> project|global`) hand-opens the chosen tier's settings file in
> `$VISUAL`/`$EDITOR`/vi via the same editor-spawn machinery as §6's "Edit a
> flow", creating it from its template first if absent, and re-parses it once
> the editor exits — a malformed result is a non-fatal warning, a valid one
> reprints the startup config summary.

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

**Fork** (feedback item 10) is a third entry point over the same discovery
listing, reusing §9's tier/filename/harness/yolo machinery instead of editing
in place: pick a source flow from any tier, describe the desired changes, pick
where the fork is saved and its filename (defaulted from the source's name),
then a harness authors it. The initial prompt points the harness at the
source flow and asks it to copy that file to the target path verbatim before
applying the described changes — the shell itself never writes the fork's
content. A source that isn't already inside the authoring harness's workspace
(a cross-tier fork, or any built-in, whose cache directory sits outside both
tiers) is copied alongside the extracted API material first, so gemini/
claude/opencode can all read it without a workspace-escape prompt or hard
failure (§9).

> **Amendment (2026-07-25).** "Harness"/"yolo" above is superseded by §9's
> amendment: fork reuses the tier/filename machinery only, and "a harness
> authors it" now means running the built-in authoring flow with the fork
> prompt as its task.

> **Amendment (2026-07-26).** Edit gains the same hand-vs-agent mode prompt as
> §9's Create/Fork, asked right after picking the flow. Hand is this section's
> path, unchanged. Agent describes the changes and runs them through the same
> sandboxed authoring flow as Create/Fork, targeting the flow's own path with
> an overwrite flag (`AuthorParams.overwrite`) so a successful run copies the
> result back over the original instead of being refused as a collision — an
> edit overwrites. A built-in source is never overwritten directly: it's
> customized into a tier first (this section's own picker), and the agent's
> changes land on that copy. The CLI's `edit` stays hand-only (unchanged); the
> hand/agent split is menu-only, the same asymmetry §9's amendment notes for
> Create/Fork.

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
`persistableWireId` (not the raw drained wire id) means any backend whose sessions
aren't durably resumable reports as such for free — no backend is in that shape
today, but the SPI still allows one. The event is named
`SessionCommitted`: it fires when a session's first turn commits — accurate
also for entries that commit but are not resumable. A
runner-side listener (always attached, like `LoggingListener`) maintains the
stage stack from stage events, joins durable-session names from the progress
log by `clientId`, and writes the manifest.

**Manifest**: one JSON file per run at
`.orca/cache/runs/<startedAt>-<pid>.json` (self-gitignored cache; at most
one writer per workdir thanks to `FlowLock`), schema v1 per research 08 §4:
`manifestVersion` (hard gate, checked before the body is decoded — any
version other than the one the build writes ⇒ skip with
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

> **Amendment (2026-08-03).** The schema has moved past the v1 described
> above; `RunManifest.SupportedVersion` in code is the authority, and the
> shape is documented on `RunManifest` itself. Since v1 it gained per-run
> `cost` (token axes plus resolved spend, subtotalled by role, agent and
> stage) and a per-turn `turns` log, each turn carrying its agent, role,
> stage, prompt size and `attempt` — the turn's 1-based position among the
> turns its call produced, so retried spend is separable. Readers gate on an
> exact version match in both directions, so a manifest from any other build
> is skipped with a warning rather than misread.

> **Amendment (2026-08-07).** `SessionCommitted` also carries the session's
> name, set by `FlowSession` (`agent.session(name, seed)`) and `None`
> everywhere else, so the listener reads `sessionName` and `kind` off the
> event instead of joining the progress log by `clientId`. The reserved
> `interactive` kind is gone; re-adding it is additive.

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
absent when no manifests exist — including the version-skew
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
| pi | `pi --session-dir <workDir>/.orca/cache/pi-sessions/<id> --continue` | medium-high — orca writes the dir itself; inferred from pi's `--continue` semantics, not yet live-verified interactively |

> **Amendment (2026-08-27).** The listing spans the checkout the shell was
> started in plus the worktrees orca made for it (`WorktreeScan.dirs`, the same
> set the resume offer scans, §3): a `--worktree` run writes its manifests into
> its own tree, so a shell reading only its own `.orca/cache/runs/` would omit
> the sessions of a run it had just started. The newest-first order now runs
> across all of them. Containment is per directory as well as per file: the
> caller's own directory is read strictly (a symlinked `.orca` there aborts),
> while a failure in any other — unreadable, or removed mid-redraw — becomes one
> warning naming it rather than taking the listing down with it. Lineages are
> keyed on the manifest's `workDir` too, since flow session names are static and
> harness sessions are cwd-scoped: the same name in two worktrees is two
> conversations, and the rows say which tree each is in. Reattaching needs
> nothing further — resume already execs from the manifest's recorded
> `workDir`, which is the worktree.

> **Amendment (2026-07-28).** Pi is now durable (ADR 0018 §2.6); before the argv is
> built, resume applies the same resumability predicate as the backend's own probe
> (`PiSessionStore.resumable`: a `*.jsonl` whose transcript header matches the
> checkout, within the 30-day retention) and reports "no resumable pi transcript at
> \<dir\>" when it fails.

> **Amendment (2026-08-05).** The manifest splits in two and loses its version
> gate. The shell's menu behaviour, the session shape, and the resume table are
> unchanged; only the file layout and the compatibility rule move. This
> supersedes the 2026-08-03 amendment's "readers gate on an exact version match"
> sentence.
>
> **Why.** Every schema bump since §8 shipped — #61, #64, #71 (v4), #77 (v5) —
> was cost or turn work; the session half has not changed since #27. Yet the
> shell decodes the whole file strictly behind an exact-version gate, so a purely
> additive cost field invalidates the session data it happens to share a file
> with, and the user loses "continue a session" for every run recorded by the
> previous build. Second, because every write rewrites the whole file,
> `write()` re-serialises the entire turn log and recomputes all four aggregates
> on each of ~40 stage/session writes — O(turns × writes) for data nothing reads
> back. The two halves have different readers, different rates of change, and
> different write shapes, so they get different files.
>
> **Layout.** `.orca/cache/runs/` holds two files per run, sharing the
> `<startedAt-epoch-ms>-<pid>` run id:
>
> - `<id>.json` — run fields plus sessions, rewritten whole as today. The
>   shell's file, and the only one it reads.
> - `<id>-cost.jsonl` — one JSON object per line, appended as the run goes. The
>   debug/measurement record.
>
> The `.jsonl` extension is load-bearing, not cosmetic. The shell's reader and
> the writer's pruner both select files with `_.ext == "json"`
> (`ManifestReader.scala:50`, `RunManifestWriter.scala:329`), so a `-cost.json`
> sibling would land in the shell's listing as a manifest that fails to decode —
> one spurious warning per run, on every menu redraw and every `orca continue`.
> `.jsonl` keeps both filters correct with no suffix special-case, is honest
> about a file that is not a single JSON value, and matches how pi's transcripts
> are already named.
>
> **What replaces the version gate.** `manifestVersion`,
> `RunManifest.SupportedVersion` and the reader's pre-decode version check are
> removed. No codec config change is needed and none is made: jsoniter skips
> unknown fields under any config, which is the whole of what reading an older
> build's file requires. What does the work is the declaration — every field
> added from here on is `Option` or carries a Scala default, since jsoniter
> defaults an absent scalar only when the case class declares one.
>
> The manifest's codec therefore stays STRICT. Switching it to the progress
> log's `withRequireCollectionFields(false)` — the first draft of this
> amendment said to — would make `sessions` optional, which is the one thing
> this format cannot afford (see Required fields). Strictness only constrains
> collection fields, so it costs nothing else; the consequence is that a
> collection added later must be wrapped in `Option`, or it lands on every
> reader as newly required.
>
> That covers additions. It does not cover renames, retypes, or respelled wire
> vocabulary, and one of those gets *worse*: today `ManifestSession` has no
> defaults at all, so renaming a field is a loud decode error. Once fields carry
> defaults, a rename reads as an unknown key skipped plus a defaulted absent key
> — silent, and wrong in a way the user sees. Rename `sessionName` and every
> durable lineage collapses onto the agent name in the picker, with no warning
> anywhere. So the rule is **additive only**: no field is renamed, retyped, or
> has its wire strings respelled; a changed meaning gets a new field beside the
> old one. The same applies to the `outcome` and `kind` vocabularies, and to
> `wireId`'s format — that last one is the only case where losing the gate turns
> a missing offer into a broken action, since the shell execs `claude --resume
> <id>` straight from it.
>
> **The rule needs a mechanism, and it does not have one yet.** No test in the
> repo decodes a previous build's bytes: every fixture is either hand-built in
> the test and edited in lockstep with the schema, or round-tripped through the
> current case class. An implementer adding a required field would update the
> fixtures in the same commit and see green. So this change checks in **frozen
> golden fixtures** — committed as test resources, never edited — that must keep
> decoding. Without them the additive-only rule is a comment.
>
> Two manifest fixtures, not one: a finished run and an in-flight one
> (`outcome: "running"`, `finishedAt` absent). The second is what a crashed run
> leaves behind, and continuing its sessions is what this format exists for, so
> retyping `finishedAt` out of `Option` has to fail. Each is compared by WHOLE
> VALUE, not field by field — a structural comparison cannot forget a field, so
> renaming an `Option` (which would otherwise decode as a silent `None` and
> pass) fails too. `ManifestReaderTest`'s verbatim v2 JSON stays as a third
> case: it currently proves the gate skips it, and inverts into proving
> tolerance reads it.
>
> **Required fields.** `workDir`, `pid`, `startedAt`, `outcome` and `sessions`
> stay required with no default. The first four are what the shell dereferences
> unconditionally. `sessions` is on the list for a different reason: dropping
> `withRequireCollectionFields(true)` would let an absent array read as empty,
> and the menu renders the count verbatim — "Continue a session from the last
> flow run (0 session(s))", leading to an empty picker, with no warning. A file
> missing any of the five is skipped with a warning naming it. That is not the
> only way a listing ends early: a symlinked `.orca/cache/runs` still aborts the
> whole listing rather than skipping a file.
>
> **Existing files.** Nothing is migrated and nothing is discarded. Every session
> field has sat at the same path since #27, so v1–v5 manifests all decode under
> the tolerant session-only schema, skipping `manifestVersion`, `cost` and
> `turns` as unknown keys. Their cost data is lost, which is acceptable —
> `.orca/cache/` is gitignored local cache. Note the behaviour change this
> implies: manifests the gate currently hides become visible.
>
> **The cost file.** JSONL, appended a line at a time with no read-modify-write.
> A JSON array cannot be appended to — its closing bracket makes every append a
> rewrite, and an unterminated one is unreadable in full.
>
> Each line carries a `type` discriminator, so an unknown record kind is skipped
> rather than breaking the reader — the additive rule applied to the line
> vocabulary. Three kinds: a `"run"` header (orca version, flow, work dir),
> `"turn"` lines, and a `"finish"` trailer carrying the outcome. The trailer is
> what lets a cost file answer "did this run finish?" on its own; its absence is
> the crash signal. The header duplicates three fields that also live in
> `<id>.json` and the two could in principle disagree — accepted, because the
> alternative is a cost file that cannot be read without its sibling, and the
> sibling is exactly what a session-less run does not have.
>
> **No persisted summary.** `ManifestCostSummary` and its subtotals are dropped
> from disk; `CostAccumulator` and `Tally` go with them. Each turn line instead
> carries its own `ManifestUsage` (all five axes), `apiCalls`, and resolved
> `Option[Cost]`, so total, by-role, by-agent and by-stage all become read-time
> folds — the algebra survives, since `Usage.+` is componentwise and `Cost.+`
> ORs `estimated`. `promptTokens` is dropped: it is `usage.inputTokens` verbatim,
> and keeping both on one line is the same self-disagreeing duplicate this
> paragraph exists to remove.
>
> A turn keeps only the `session` key, not a denormalised copy of the session's
> harness and name. The first draft of this amendment called for the copy,
> reasoning that the key dangles when the sibling manifest was never written or
> was pruned. Implementation removed both cases: retention deletes a run's two
> files together, so a cost log cannot outlive its manifest; and a run that
> never committed a session has no session record to copy from, so the fields
> would be empty in exactly the case they were meant to cover. They would have
> been populated only when the manifest existed and already answered the
> question — and populated from the second turn of a session onward, since the
> first precedes its own commit.
>
> The fold needs somewhere to live: a small `CostLog.read` ships in `runner` as
> production code. Without it "aggregates become read-time folds" describes code
> in nobody's repo, and two claims here — that a reader drops a bad line and
> keeps the rest, and that the golden fixture is decoded by a test — have nothing
> to call.
>
> **Creation gates.** The session manifest keeps its existing gate: it comes into
> existence on the first `SessionCommitted`, because that gate is the only thing
> preventing the "(0 session(s))" menu item above. The cost file gets its own,
> on the first `TokensUsed`. They cannot share one: on the autonomous text path
> `emitTokens` fires *before* `emitSessionCommitted`, so gating cost on the
> session event would mean buffering turns until it opens — reintroducing the
> in-memory turn log this change exists to remove, and discarding it entirely for
> a `quietTextTurn`-only run.
>
> **Writing.** The append opens the file per line rather than holding a handle
> for the run: a held handle survives neither the directory being replaced
> underneath it nor `finish`, and would need a close hook on the actor. The
> append must go through the same `safeWrite` guard as the session write —
> `TokensUsed` handling is pure in-memory accumulation today and cannot throw,
> and after this change it does IO on every turn; a throw out of a `tell`
> handler closes the actor's channel and quarantines the writer for the rest of
> the run, session writes included.
>
> One property is genuinely lost and is not recoverable. Because every session
> write rewrites the whole file, a swallowed failure today is self-healing — the
> next successful write restores complete content, which is what
> `RunManifestWriterTest`'s rug-pull case pins. An append has no such property: a
> swallowed append is that turn, gone. Accepted; this file is measurement, and
> the session half keeps its atomic rewrite. The realistic torn-line source is
> also not a kill (the kernel already has those bytes) but a short write that
> throws mid-line, after which the next append concatenates onto an unterminated
> line and costs two records rather than one. Bounded at two, in a file already
> declared lossy, so the reader simply skips the bad line rather than the writer
> repairing the tear.
>
> The reader must decode with REPLACEMENT, not the JVM default for line reading.
> A tear can cut a multi-byte UTF-8 sequence — stage and agent names are
> free-form prose and jsoniter emits them unescaped — and a reporting decoder
> throws before yielding any line at all, losing the whole file including the
> lines before the tear. That would defeat the entire reason for choosing a
> line-oriented format.
>
> **Retention.** The keep-20 prune counts run ids, not files, and deletes a run's
> two files together — 20 runs, as before, not 10. Grouping by run id is what the
> existing filename sort cannot do once a second file per run exists: `<id>.json`
> and `<id>-cost.jsonl` sort adjacently but as two entries, and since `.jsonl`
> fails the `ext == "json"` filter a file-counting prune would delete session
> manifests while leaving their cost files behind forever. The prune trigger also
> moves: it fires once, on the first write of *either* file, not from inside the
> session write — otherwise a workdir where flows keep failing before their first
> session commit grows without bound, which is the very case this change starts
> recording.
>
> A run that spends tokens without committing a session is the common case, not
> a rare one: every fresh run names its branch through a cheap agent call
> (`BranchNamingStrategy.shortenPrompt`) before its first stage, so one cancelled
> at the plan prompt leaves a cost log and no manifest. Ranked by run id alone,
> twenty such runs would empty the shell's listing. Retention therefore keeps two
> sets of 20: the newest runs that own a manifest, and the newest runs of any
> kind. The first holds the listing at 20 continuable runs however many
> manifest-less runs pile up; the second bounds a workdir that stops producing
> manifests altogether, where the first has nothing to rank against. A run's
> files are still kept or deleted together, so a cost log never outlives its
> manifest. The directory holds between 20 and 40 runs.
>
> **This needs a carve-out in the 0.x versioning rule.** AGENTS.md forbids
> default values on persisted fields and forbids back-compat machinery outright,
> with one documented exception for `ProgressLog`/`SessionRecord`. The manifest
> becomes the second, for the same reason the first exists: this is live local
> data that has to survive an orca upgrade, and invalidating it costs a
> user-visible feature rather than a re-run. The carve-out is narrow — defaults
> are permitted on the two `.orca/cache/runs/` shapes and nowhere else, and only
> on fields added after this change; the five required fields above keep the
> no-default rule, so a call site that forgets `pid` still fails to compile.
> AGENTS.md is edited to say so in the same change, or the next reviewer
> correctly flags every optional field added here.
>
> **Done when.** The `shell` module names no `orca.runner.manifest` cost type —
> `ManifestCostSummary`, `ManifestSubtotal`, `ManifestUsage`, `ManifestTurn` — in
> production or test code. Four shell test files reference them today; three pass
> `ManifestCostSummary.empty` to a constructor, and `ManifestRoundTripTest`
> asserts on `cost.total`, `cost.byRole` and `turns`, so its cost half moves to a
> `runner` round trip against the cost file rather than being deleted. The
> criterion is deliberately about `orca.runner.manifest` types and not "cost" in
> general. (`PriceList` has since left `RunManifestWriter.start`'s signature:
> each turn's cost is resolved once at the dispatch boundary and arrives on the
> event, so the writer prices nothing.)
>
> **Enabled, not scheduled.** Recording each turn's `model` becomes cheap once
> the cost file is additive, and there is a concrete question waiting for it: a
> dated haiku model id appears on turns billing at Sonnet rates, while a
> session-id join places that same id in fully-qualified-haiku sessions. Both
> observations can only hold together if a turn's model id and its tokens come
> from different sessions — a check that needs `model` on the turn *and* on the
> session. Not a task here: the parallel claude plan-mode removal may remove the
> cause.

### 9. Creating a new flow with a harness

Menu flow (feedback item 9, goal-first): pick global vs project target upfront
(fixes the save path: `$XDG_CONFIG_HOME/orca/flows/<name>.sc` vs
`{workDir}/.orca/flows/<name>.sc`), describe the flow's goal, then a filename —
suggested by a cheap, best-effort non-interactive call to the configured
coding agent (`claude -p`/`codex exec`/`gemini -p`/`opencode run`/`pi -p`,
whichever is print-mode for that CLI) turning the goal into a kebab-case slug,
sanitized hard and bounded by a short timeout; a slow, absent, or unreachable
agent degrades to a local word-based slug instead, and either way the
suggestion is shown as an editable default, never written unconfirmed. Fork's
target filename is suggested the same way, grounded in the source's name and
description plus the described changes instead of a fresh goal, and degrading
to `<source>-fork.sc` on the same conditions. Then
pick a harness (default: the configured coding agent) and confirm running it
without approval prompts ("yolo mode", feedback item 11, default yes) — mapped
per backend to claude's `--dangerously-skip-permissions`, codex's
`--dangerously-bypass-approvals-and-sandbox`, gemini's `--yolo`; pi has no
approval gate to bypass and opencode's interactive TUI has no such flag at all
(config-only, via `opencode.jsonc`), so both print a one-line note instead of
silently doing nothing. Between the harness and yolo prompts sits a model
step — the wizard's own curated/free-text UX (`ModelCatalog`, shared with §4),
preselecting the configured coding agent's pin when its harness matches — and
the chosen model is passed as every harness's own `--model`/`-m` flag,
opencode's default TUI launch included. The shell then execs the harness's
interactive UI with an initial prompt.

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

> **Amendment (2026-07-25).** Authoring no longer execs a harness's own
> interactive UI directly. Create/fork instead run the built-in
> `implement-interactive.sc` flow, with the prompt above as its task, over the
> exact same launch path "Run a flow" uses
> ([[FlowLauncher.runAnnounced]]/[[RunAction]]: forced-version + fallback
> semantics, tty-inherited terminal). The configured planning/coding/review
> agents — and their model pins, from settings — do the writing, planning, and
> review automatically, same as any other flow run; there is no separate
> harness, model, or yolo choice to make. Consequently the harness/model/yolo
> prompts (menu and CLI) and `FlowAuthoring.harnessArgv`/session-launch
> machinery are removed; `orca create`/`fork` lose `--harness`/`--yolo`/
> `--no-yolo`. The wizard's model UX (`ModelCatalog`) is unaffected — it's
> still how the wizard (§4) picks each role's model.
>
> **Amendment (2026-07-25, second).** The authoring flow runs in a throwaway
> sandbox (`AuthoringSandbox`) — a temp dir with a fresh git repo, a local
> commit identity, and a pre-committed settings file with every stack key
> explicitly `off` (live lines — ADR 0019 amendment 2026-07-26; a comment
> would not do) so stack discovery never runs (a flow script has no project
> stack) — never in the user's repository. Authoring therefore works from any
> directory, never stashes or commits the user's tree, and leaves no branches
> behind; the flow writes the file at the sandbox root and, on success, the
> shell copies it out to the target tier and deletes the sandbox (a failed
> run keeps it, with a notice, for inspection). Trade-off: an interrupted
> authoring run is not resumable via `orca continue` — its session manifest
> lives in the sandbox. The filename is auto-derived (goal slug /
> `<source>-fork.sc`), uniquified on collision, and never prompted for; the
> CLI's optional `name` argument still allows an explicit choice (validated,
> collision-refused).
>
> **Amendment (2026-07-25, third).** The authoring flow is now the built-in
> `simple.sc`, not `implement-interactive.sc`: a log analysis showed the
> planner splitting a trivial "copy file, then edit it" task into two tasks
> and the review loop then fighting itself over the verbatim-copy task for
> several iterations — over-orchestration for what authoring actually needs.
> `simple.sc` runs no plan stage and a single review round (`maxIterations =
> 1`) against one custom all-round reviewer (not the full `allReviewers`
> roster), built on the configured review agent.

> **Amendment (2026-07-26).** Create and Fork gain a hand-vs-agent mode
> prompt — mode first for Create (it decides whether a filename or a goal
> comes next), after source+tier for Fork. Agent is this section's path,
> unchanged. Hand skips the harness entirely: Create writes a minimal
> compiling skeleton (version-pinned header, a description placeholder, the
> bare API import, an empty `flow(OrcaArgs(args)):` body) at a prompted
> filename; Fork copies the source straight to the auto-derived target — both
> then open the result in the editor (§6's machinery). The CLI's
> `create`/`fork` stay agent-only (unchanged) — the hand/agent split is
> menu-only.

> **Amendment (2026-07-27).** After copying the authored file out of the
> sandbox, the Project tier gets a scoped `git add`/`commit -- <path>` into
> the user's own repo (`FlowCommit`) — otherwise the new file sat untracked in
> `.orca/flows/` until a later run's `ensureClean` swept it into a stash. The
> commit is skipped, with a "commit it yourself" hint, when: the tier is
> Global (no repo to commit into), `cwd` isn't inside a git work tree, HEAD is
> unborn, or the commit fails for any reason — a commit hiccup never fails the
> authoring result.

> **Amendment (2026-07-28).** The sandbox settings file now sets `lint =
> scala-cli compile <flow-file>` (format/test stay `off`): the prompt's
> compile-verify instruction was only agent-honored, so a script the agent
> never compiled could land committed but broken. The review loop's lint gate
> makes the check mechanical, and `simple.sc` moved to `maxIterations = 3` so
> a compile failure found by the gate gets fixed *and re-verified*. The loop's
> result still isn't a hard gate — a flow that ends with findings ignored
> still exits Ok and is copied out; accepted, since the sandbox lint is
> feedback machinery, not an acceptance test.

### 10. Command-line interface

`orca` with no args starts the interactive shell unchanged (§§3–9); any argv
is the non-interactive CLI. `Main.main` dispatches on `args.head`:
`--help`/`-h`/`help` prints a curated top-level synopsis (`CliHelp.topLevel`,
hand-rolled — mainargs' no-subcommand output is a flat, undifferentiated
dump); `--version`/`-V` prints `ShellVersion.value`; a known subcommand name
goes to `Cli.dispatch`; anything else is `orca: unknown command '<tok>'` to
stderr, exit 2. Built with mainargs (already an `runner` dependency, ADR
0004), one `@main` method per verb — no new arg-parsing library.

The subcommand set mirrors every main-menu action, one short verb each, no
`-flow` suffix (the domain object *is* the flow): `run <flow> [task]`,
`view <flow>`, `edit <flow>`, `create "<goal>" [--name]`,
`fork <source> "<changes>" [--name]`, `continue [selector]`, `config`,
`clear-stack`, `list`. Flags are shared by name across commands that mean
the same thing (`--global`, `--json`, `--yes`). `create`/`fork` run the
built-in authoring flow (§9) with the configured role agents — no
harness/model/yolo flag exists for either.

> **Amendment (2026-08-27).** `run` gains `--worktree`, passed straight
> through to the flow child like `--verbose`/`--skip-branch`/`--keep-changes`.
> `RunCli` also refuses the two contradictory pairs (`--worktree` with
> `--skip-branch`, and with `--keep-changes`) itself, exiting 2 before any
> `scala-cli` spawn — the child refuses them too and stays the authority, but
> the shell holds the answer already, and spawning only to be told costs a
> dependency resolution. Both sides call the one shared decision
> (`OrcaArgs.worktreeRefusal`), so neither the wording nor the set of refused
> pairs can drift.

> **Amendment (2026-08-28).** That shared decision is now `RunTarget.from`,
> which returns the run's destination as one value instead of an optional
> refusal string. Both parsers convert their raw flags through it, so a refused
> pair cannot be carried past argv — `FlowFlags` holds a `RunTarget`, and every
> launch path takes one.

Both entry points call a shared `orca.shell.actions` package (`FlowResolution`,
`RunAction`, `ViewAction`, `EditAction`, `AuthorAction`, `SessionAction`,
`ConfigAction`, `StackAction`): each takes fully-resolved parameters and does
the work, with no prompting inside. `Main`'s interactive handlers keep only
the prompting that produces those parameters; `Cli` parses the same
parameters from argv. This is why CLI and menu behavior can't drift — they
call the same code below the point where a human would otherwise be asked.

CLI hygiene: results/data go to stdout (`view`'s source, `list`/`continue
--list` rows or `--json`, `config` show) — nothing else shares stdout with
`--json` output; diagnostics go to stderr. Exit codes are 0 success, 1 action
failure, 2 usage error (unknown subcommand, bad/missing args, missing tty),
except `run`, `edit`, and `continue`'s resume, which wrap a subprocess and so
propagate its raw exit code instead (130 on signal) — mirroring the child's
status, not the flat convention. A missing required positional (`create`'s
goal, `fork`'s changes) is always a hard error, never a tty prompt-fallback: a
subcommand's behavior must not depend on where it runs.

`create`, `fork`, `edit`, and `continue`'s resume each exec or hand off to an
interactive child (a harness session, `$EDITOR`) and so require a real
terminal; off a tty they fail cleanly with a one-line stderr error and exit 2
before constructing any UI — the interactive stack (ConsoleUI, JLine) NPEs if
built off-tty, so the gate runs before any `Terminal`/`ShellUi` is
constructed. `run`, `view`, `list`, `config`, `clear-stack` (without
`--yes`, which then requires a tty), `--help`, and `--version` all work with
no terminal, piped in or out.

Security: `continue` with no selector resumes the newest durable session
without asking — convenient, but a `.orca/cache/runs/` manifest is
project-local and a committed repo could otherwise smuggle a silent resume
into an attacker-chosen session. To keep the choice visible, `continue`
prints the resolved session's identity (name, harness, stage, workdir) to
stderr immediately before exec'ing the harness child, on the same tty-gated
terminal — giving the user a beat to Ctrl-C even with no selector typed.

## Non-goals

- Windows support beyond the existing `bash -c` contract (consistent with
  ADR 0019/0020).
- In-process flow execution (revisit condition in §2).
- `cs install` / native-image packaging at launch (possible later; §1).
- Running a shadowed flow tier from the menu (use `scala-cli run <path>`).
- A directory picker — the shell operates on the cwd it was launched from.
- An in-shell pager for view-a-flow — v1 prints the highlighted source;
  jline's `Less` (already on the classpath) is the upgrade path (§6).
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
- A non-interactive CLI surface ships alongside the menu (§10): the `shell`
  module gains a `mainargs` dependency, and every menu action's non-prompting
  body lives in the new `orca.shell.actions` package, shared by `Main` and
  `Cli`.
