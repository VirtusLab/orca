# 0019. Per-project stack settings with auto-discovery

Status: Accepted · Date: 2026-07-14
Related: [ADR 0018](0018-stage-bound-flow-runtime.md) (`.orca/` scratch conventions, stage commits), [ADR 0011](0011-reviewer-roster.md) (review loop)

## Context

Every example flow hardcodes its target project's tooling: `formatCommand =
Some("cargo fmt")`, `lint = Some(Lint("cargo check --tests", agent.cheap))` in
one script, `sbt scalafmtAll` / `sbt Test/compile` in another, `mvn -q
spotless:apply` in a third. The scripts are otherwise stack-agnostic — the
format/lint strings are the *only* thing tying a flow to Rust vs Scala vs
Java. A flow script should contain nothing stack-specific; the commands should
come from per-project settings resolved at run time.

Where such settings can live is constrained by what `.orca/` is today: the
run-scoped scratch directory (progress logs, `flow.lock`, spilled lint
output), which projects are *encouraged* to gitignore. The two files needing git guarantees each have a dedicated
mechanism today: the progress log punches *through* the ignore (a single-path
`git add -f` in the stage-commit protocol), while the lock is guaranteed
*ignored* even in repos that never added the ignore line (an entry in
`$GIT_COMMON_DIR/info/exclude`). Stack settings are the opposite kind
of artifact: user-visible project metadata that *should* be committed and
hand-edited, like `.scalafmt.conf`. An ignored directory is a silent trap for
such a file — git cannot re-include a path whose parent directory is excluded
(`!.orca/settings.properties` under a `.orca/` ignore line is dead), so a
hand-authored settings file would simply never reach the repo, with no signal.

Two further findings shaped the design:

- **Config-as-Scala-code was evaluated and rejected.** The payload is a few
  opaque shell strings — `"cargo chekc"` type-checks perfectly, so the
  compiler verifies nothing useful. Shape-checking would require the settings
  file to depend on orca's types, planting a second orca version pin inside
  the target repo that skews against every visiting script's own pin. eslint's
  flat config — the strongest code-config precedent — went code-only because
  config had to reference runtime objects (plugin modules); orca's one runtime
  object, the lint summariser agent, dissolves by convention (the flow
  supplies its own cheap tier; settings carry only the command).
- **A force-staged file is *less* durable than an untracked one.** `git reset
  --hard` (the failure-teardown step) deletes a staged-but-uncommitted file
  but leaves an untracked file alone. Anything orca writes for the user must
  therefore be written untracked, never pre-staged; it rides a later stage
  commit's `git add -A` or the user's own commit.

Named presets (`preset = rust-cargo`) were considered and rejected: real
projects differ in subtle ways (extra cargo features, a nonstandard sbt
command, spotless vs no formatter at all), so a preset name is one indirection
away from the values users actually need to see and tweak — and a preset
catalog drifts (mill's format command changed syntax across 0.11/0.12/1.x).
Explicit per-task commands, auto-discovered and then owned by the user, keep
the file trivially editable.

## Decision

### The settings file

`{workDir}/.orca/settings.properties` — committed, hand-editable, key=value:

```properties
# orca stack settings — edit freely, commit with the project.
# Delete this file to re-run auto-discovery.
format = cargo fmt
format = pnpm exec prettier --write .
lint = cargo check --tests
lint = pnpm run lint
test = cargo test
```

- Keys: `format` (rewrite sources in place), `lint` (cheap sanity gate — a
  fast compile/check, not the test suite), `test` (full test run). A key may
  **repeat**: each occurrence appends to that task's command list, in file
  order (the example above is a Tauri-style repo with both a Rust and a JS
  half). All keys optional; a key with no occurrences means the task is
  disabled/unknown, and a key with an empty value is equivalent to omitting
  it. Each command runs via `bash -c` (so `&&` also composes within one
  value, when fail-fast between two commands is wanted).
- List semantics per task: `format` runs every command in order (each
  formatter owns its own files); `lint` runs every command, labels each
  output with its command and exit status (the zero-usually-means-clean hint
  the summariser relies on today), and feeds the concatenation to the one
  summariser pass — unlike `&&`-chaining, a failure in the first linter
  doesn't hide the second's diagnostics from the same review round — skipping
  the LLM round-trip only when every command's output is empty; `test` is a
  list the consuming flow runs as it sees fit.
- Parsing is a strict, tiny line format — `#` comments, `key = value`, the
  value taken verbatim (trimmed) after the first `=`. NOT
  `java.util.Properties`: its backslash/unicode escape handling would mangle
  shell commands. Unknown keys are an error naming the valid keys (typo
  detection; forward-compat traded away deliberately at 0.0.x, where consumers
  are version-pinned scripts).
- Read from `workDir` only — no upward search. In a monorepo, flows target a
  subproject; per-workDir settings are the correct semantics.

JSON (+ generated schema), HOCON, and TOML were all heavier than three shell
strings deserve; key=value needs no parser dependency, diffs cleanly, and
carries comments — the natural annotation here is *why* a command deviates
("--all-features because of feature-gated code"), which no schema hover text
can express.

### `.orca/` flips to committed-by-default

The settings file makes `.orca/` definitively mixed content, so the polarity
flips to the safe direction:

```
.orca/
  settings.properties      # committed project metadata
  progress-<hash>.json     # committed during runs (as today)
  cache/
    .gitignore             # written by orca, contains "*" (self-ignoring)
    CACHEDIR.TAG           # backup/sync tools skip the dir
    flow.lock
    lint-*.txt             # spilled lint output
```

- Ephemeral state moves under `.orca/cache/`, which orca creates with a
  self-ignoring `.gitignore` (`# Automatically created by orca.` + `*` — the
  ruff/pytest/virtualenv convention) and a `CACHEDIR.TAG` (beginning with the
  spec's `Signature: 8a477f597d28d172789f06886806bc55` line, or backup tools
  ignore it) — projects need
  zero gitignore lines, and a forgotten ignore fails *loudly* (untracked
  noise in `git status`) instead of silently losing a user's settings file.
  One shared helper owns creating `.orca` and `cache/` (today `FlowLock`,
  `Lint`, and `ProgressStore` each create `.orca` independently, and `Lint`
  resolves it against `os.pwd` where the others use `workDir` — reconciled
  while moving).
- The progress-log `forceAdd` **stays**. It is load-bearing, not a hack: in a
  repo that still gitignores `.orca/`, a plain stage `git add -A` would skip
  the log, and failure teardown's `reset --hard` would then leave an on-disk
  log claiming stages completed that were just reset away — a corrupt resume.
  On a non-ignored path, `add -f` degrades to a plain `add`, so keeping it is
  free.
- Migration: at flow start, if `git check-ignore` reports the settings path as
  ignored, log a prominent warning with the exact gitignore line to remove.
  Legacy repos keep working (force-add still carries the log); the settings
  file is the only casualty, and it is warned about on every run.
- `FlowLock` moves to `.orca/cache/flow.lock`; the `info/exclude` machinery
  is retired — `cache/.gitignore` is written by the same helper that creates
  the directory, before the lock, so the exclusion is always in place first.

### Auto-discovery

When `flow(...)` starts and no settings file exists (and no programmatic
override was passed), orca discovers the stack, **writes the file**, and
reports what it found (surfaced as flow events, like the other setup notes,
so non-terminal interactions see it too):

```
[orca] no .orca/settings.properties — running stack discovery
[orca]   format = cargo fmt              # Cargo.toml (rustfmt ships with the toolchain)
[orca]   lint   = cargo check --tests    # compiles main+test code, runs nothing
[orca]   test   = cargo test             # Cargo.toml; CI runs it in .github/workflows/ci.yml
[orca] written to .orca/settings.properties — review and edit as needed.
```

Discovery is one **read-only run of the leading agent's cheap tier** — the
same tier the lint summariser uses (`cheap.withReadOnly.resultAs[...]`, the
established text-in/JSON-out shape). The run is agentic: the agent reads
what it needs with its read-only tools; nothing is inlined into the prompt,
so repo size is bounded by the agent's own selective reading. The result
type is a private sibling of `StackSettings` that carries, per command, the
**evidence** that justifies it (the file — plus key, task, or line — that
shows the tool is set up here), and a one-line reason for each task left
unset. Evidence and reasons are written into the settings file as the
comments shown above — they are what makes the post-hoc "review and edit"
step actually reviewable.

The prompt is **principle-based, with no catalog of real stack→command
examples**. Its elements:

- semantic definitions of the three tasks — in particular `lint` is defined
  by its properties (covers test sources, executes nothing, substantially
  faster than the suite; a compile/typecheck is usually right; never the
  test runner), not by the word "linter";
- a survey procedure: build definitions, lockfiles, task runners, tool
  configs, and **CI workflows** — CI shows which commands the project itself
  trusts, recovering calibrated commands from the repo rather than from the
  model's prior;
- prefer the project's own entry points over reconstructing them: a
  `justfile`/`Makefile` recipe, `package.json`/`composer.json` script, or
  build wrapper (`./gradlew`, `./mill`) encodes the project's opinion — emit
  `just fmt`, not the commands `just fmt` happens to run;
- configured-before-proposed: a tool being conventional for the ecosystem is
  NOT evidence; its config, plugin declaration, or script entry must be
  present in this repo;
- every command carries its evidence citation; no citable evidence → the
  task stays unset with a one-line reason, stated as a correct and complete
  answer (cheap models otherwise fill slots);
- multi-stack repos contribute commands per stack; orca's own flow scripts
  (`.sc` files pinning orca) are not part of the project's stack;
- exactly one **fictional-stack example** anchors the JSON shape and the
  lint-vs-test distinction (`acme compile --include-tests` vs `acme test`) —
  a made-up tool cannot leak into output as a runnable command.

Real-stack examples (a simplified form of the rejected matrix) were
considered and rejected as too suggestive: verbatim-copying bias is
quantified and worst on cheap tiers, and a seeded plausible command is the
one failure class nothing downstream catches — `sbt scalafmtAll` in a repo
without the plugin resolves on `PATH`, looks exactly canonical to a human
reviewing the file, and then fails or no-ops every review round. An
under-calibrated principle-derived command (`cargo check` missing `--tests`)
degrades visibly instead, and both cost the same one-line edit. A catalog in
the prompt would also anchor the model away from reading the repo —
reinstating the rejected matrix as a soft prior. Recorded escalation path:
if real use shows cheap tiers flubbing flag-level calibration on mainstream
stacks, add a single real example of the lint-vs-test *distinction* — never
per-stack rows.

Orca then applies two mechanical checks to each proposed command (orca-side
by necessity — the discovery agent's read-only toolset has no shell): its
first word — after stripping leading `VAR=` assignments — must resolve via
`bash -c 'command -v -- <word>'` (the execution environment's own
resolution, builtins included) or be an executable path inside the repo;
and each cited evidence file must exist. Failures are demoted to
commented-out lines carrying the failure reason
(`# lint = just check   (just: not found on PATH)`). The checks are
deliberately narrow — "resolves" is not "is right". A resolvable-but-wrong
guess is acceptable per the trusted-but-fallible stance: discovered commands
run inside stages with exactly the capability the coder agent's own shell
already has there, stage failure teardown bounds tree damage, and the guess
sits visibly in a committed file from its first run. The checks run once,
in the same process env stage-time `bash -c` inherits; the frozen file is
not re-checked on other machines, where a missing tool fails visibly at
stage time — consistent with freezing. The guesses are written to the file,
so a wrong one is a one-line edit, not a library fix — and the model call
happens **once**: the result is frozen in the committed file, and re-running
discovery means deleting it. The non-determinism of a model-driven guess is
confined to that first run.

A discovery **failure** — backend unavailable, spawn failure, structured
output still invalid after the retry policy — aborts the run as an ordinary
surfaced setup failure. It is deliberately *not* degraded to writing an
all-commented file: under the frozen-file semantics that would turn a
transient outage into a permanently recorded "gates off", and the run needs
the same backend minutes later anyway. (Contrast branch naming, which has a
deterministic fallback because a name must never block the flow; settings
have no safe deterministic fallback.)

An earlier draft specified a deterministic marker-file/probe matrix (per
ecosystem: markers, static capability probes, command tables) instead. It
was rejected after two review rounds found most of their defects inside that
matrix — plugin-specific gradle task names, config-filename variants, and a
self-detection bug where orca's flow scripts made every consumer repo look
like a scala-cli project. That is the maintenance profile of a hardcoded
catalog chasing every ecosystem's tooling forever; an agent reading the
actual repo has none of it, covers arbitrary stacks (CMake, Bazel, Elixir —
no out-of-the-box list to fall off of), and costs one cheap-model call on a
run that is about to spend minutes of model time anyway.

When the agent finds no evidence for a task (or for any stack at all), the
file is still written — the missing keys commented out, with the agent's
one-line reason (`# format = (no formatter config found)`) — and a warning is
logged that the corresponding gates are disabled. Recording the outcome in a
visible, committable file (rather than erroring, or silently re-detecting
every run) makes the "gates are off" state discoverable and the fix obvious.
Deleting the file re-runs discovery.

Discovery happens **eagerly, as a lifecycle setup step**, not lazily at first
use: a missing-settings surprise at the first `reviewAndFixLoop` would land
minutes into a run, after planning tokens are spent, and inside a stage whose
failure teardown discards uncommitted work. At setup time the cost of a wrong
outcome is one log line. When the file exists, discovery does not run at all —
an ordinary flow start reads the file and makes no model call. Running an
agent during setup is already established: the leading agent names the branch
in the same window.

Reading and validating an *existing* file happens before `ensureClean` — a
malformed file aborts before any tree mutation, and that pre-`ensureClean`
read also decides whether discovery runs (so a hand-written file that
`stash -u` sweeps out of a dirty tree is not mistaken for absent). Only the
discovery **write** is sequenced against the two lifecycle steps that would
otherwise sweep it: it runs **after `ensureClean`** (whose `stash -u` would
stash a just-written untracked file straight back out of the tree) and
**immediately before the header commit**, whose `add -A` then includes the
new file — deliberately. On a resumed run with no file (delete-to-rediscover,
then resume), discovery runs in the same post-`ensureClean` slot; there is
no header commit on that arm, so the file stays untracked until the next
stage commit's `add -A` — the same ride the legacy-ignored-repo case takes.
In a committed-`.orca` repo the flow itself commits the discovered guesses as
part of the branch's first commit; the log's "review and edit" is post-hoc —
an edit-and-recommit, not a review gate. In a legacy repo that still ignores
`.orca/`, both mechanisms skip the file (`add -A` and `stash -u` leave
ignored paths alone) and it simply stays untracked for the user to commit.
Residual: a run failing in the narrow window between the write and the
header commit leaves the file untracked, and the next run's `ensureClean`
stashes it with the usual dirty-tree warning before discovery re-runs —
spending a second model call and possibly guessing slightly differently.
Accepted: the window is a few git commands wide.

### API

```scala
/** Resolved per-project tooling commands (each run via `bash -c`).
  * An empty list means the task is disabled/unknown. */
case class StackSettings(
    format: List[String],
    lint: List[String],
    test: List[String]
)
```

- `FlowContext` gains `def stackSettings: StackSettings` (not `settings` —
  too generic next to the context's other accessors), populated during the
  setup step sequenced above (override > file > discovery-and-write) and
  frozen for the run — context construction stays pure, preserving the
  runtime's construction-before-lifecycle invariant. A resumed run, or a
  separate process running against the same `workDir`, finds the now-written
  file and resolves to the same values.
- `flow(...)` gains `stackSettings: Option[StackSettings] = None`. When
  passed, the file is neither read nor written — the caller owns the values
  (this is the language-specific-flow escape hatch).
- No `SettingsReader` service trait: `GitTool`/`FsTool` are traits because
  they wrap subprocess state; reading one small file needs no seam. Tests
  write a real file into their temp `workDir` or pass the override.
- `reviewAndFixLoop`'s stack-dependent parameters become three-state so that
  *omission* means "from settings" while "explicitly off" stays expressible:

```scala
enum Configured[+A]:
  case FromSettings   // resolve from ctx.stackSettings (the default)
  case Off            // explicitly disabled for this call
  case Use(value: A)  // explicit value; settings ignored

def reviewAndFixLoop[B <: BackendTag](
    ...,
    formatCommands: Configured[List[String]] = Configured.FromSettings,
    lint: Configured[Lint] = Configured.FromSettings,
    ...
)
```

  `Lint` grows to `Lint(commands: List[String], agent)`: it runs each command,
  labels and concatenates the outputs, and keeps the single summariser pass.
  `FromSettings` resolves `format` directly and builds the lint gate as
  `Lint(stackSettings.lint, <lead agent>.cheap)` — the summariser tier every
  example currently hand-writes. An empty list resolves to no gate at all:
  `FromSettings` over an empty `stackSettings.lint` behaves exactly like
  `Off` (no `Lint` value constructed, no summariser wired), and an empty
  `stackSettings.format` formats nothing. `Configured` values are plain data, so the
  parameters remain compatible with the capture-checked `ReviewLoop` fan-out
  (ADR 0018 §6).
- The `test` key is not consumed by `reviewAndFixLoop` (the lint gate stays
  deliberately cheap); it is exposed via `ctx.stackSettings.test` for flows
  to use in their own verification stages, and discovery fills it so the file
  is complete when such a stage wants it.

Precedence, end to end: `reviewAndFixLoop(formatCommands = Use(...)/Off)` >
`flow(stackSettings = Some(...))` > `.orca/settings.properties` > auto-discovery
(which writes the file).

## Consequences

- Example scripts lose their last stack-specific lines;
  `reviewAndFixLoop(coderSession = session, reviewers = allReviewers(agent),
  task = ...)` works unchanged against a Rust, Scala, or Go repo.
- **Behavior change:** a script that today omits `lint` deliberately gets a
  lint gate the moment the target project's settings define one. At 0.0.x
  with version-pinned consumers this is accepted; scripts that want
  format-only pass `lint = Configured.Off`. Migration for explicit call
  sites: `formatCommand = Some("cargo fmt")` → `formatCommands =
  Use(List("cargo fmt"))`, `lint = Some(Lint(cmd, a))` → `lint =
  Use(Lint(List(cmd), a))`.
- New public surface: `StackSettings`, `Configured`, the
  `flow(stackSettings = …)` parameter, the settings-file format, and the
  discovery behaviour (documented in the README).
- `.orca/` becomes committed-by-default with `cache/` scratch; repos that
  keep their old `.orca/` ignore line get a per-run warning naming the line
  to remove. The `info/exclude` lock mechanism is retired; a stale
  `.orca/flow.lock` line may linger in existing repos'
  `$GIT_COMMON_DIR/info/exclude` — harmless, since the lock now lives under
  the self-ignored `cache/`.
- Discovery guesses are visible and cheap to correct (edit the file).
  First-run discovery needs a working agent backend and spends one
  cheap-model call; runs with an existing settings file — the steady state,
  including CI — make no model call.
- Not in scope, recorded as non-goals: named presets, a user-level
  (`~/.config/orca`) settings layer (no machine-scoped read site exists yet),
  per-flow-type profiles, upward/monorepo settings search, and Windows
  support beyond the pre-existing `bash -c` contract.
