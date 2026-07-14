# Epic 5: Consumption — `Configured`, reviewAndFixLoop, Examples, Docs

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans.
> Spec: `adr/0019-project-stack-settings.md` § API (the `Configured` block +
> precedence chain) and § Consequences (behavior change + migration
> shapes). Landed: `ctx.stackSettings` (populated by lifecycle; override >
> file > discovery), `Lint(commands: List[String], agent)`, `StackSettings`.

**Goal:** omission means "from settings": `reviewAndFixLoop`'s
stack-dependent params become three-state; examples lose their last
stack-specific lines; README/AGENTS document the whole feature.

## Global constraints

- `Configured` values resolve BEFORE the CheckedPar fan-out (plain data —
  resolution happens on the collecting thread at loop entry; do not move
  `ctx.stackSettings` reads inside capture-checked forks).
- Empty list from settings ≡ no gate: `FromSettings` over empty
  `stackSettings.format` formats nothing; over empty `stackSettings.lint`
  builds NO `Lint` value and wires no summariser (exactly like `Off`).
- Zero warnings; per-task commits; scalafmtAll; present-tense comments.

---

### Task 1: `Configured[+A]`

**Files:**
- Create: `flow/src/main/scala/orca/Configured.scala` (top-level `orca`
  package — scripts write `Configured.Off` bare, like `StackSettings`;
  public; NOT re-exported from runner's exports.scala — same-package
  self-reference, same as StackSettings; extend the exports header comment
  listing if it names such types)
- Test: type-level usage lands in Task 2's tests; add a
  `FlowCompilesTest` canary in Task 3.

```scala
/** Three-state default for stack-dependent parameters: omission resolves
  * from the project's [[StackSettings]] (ADR 0019). */
enum Configured[+A]:
  /** Resolve from `ctx.stackSettings` (the default). */
  case FromSettings
  /** Explicitly disabled for this call, regardless of settings. */
  case Off
  /** Explicit value; settings ignored. */
  case Use(value: A)
```

- [ ] Implement + `flow/Test/compile` green → commit:
      `git commit -m "Configured: three-state stack-dependent parameter default"`

### Task 2: `reviewAndFixLoop` resolves from settings

**Files:**
- Modify: `flow/src/main/scala/orca/review/ReviewLoop.scala` —
  `formatCommand: Option[String] = None` becomes
  `formatCommands: Configured[List[String]] = Configured.FromSettings`;
  `lint: Option[Lint] = None` becomes
  `lint: Configured[Lint] = Configured.FromSettings`. Resolution at loop
  entry (before the config object / fan-out; note the existing
  `import config.{lint as _, *}` shadow comment — keep the field name
  workable):
  ```scala
  val resolvedFormat: List[String] = formatCommands match
    case Configured.FromSettings => ctx.stackSettings.format
    case Configured.Off          => Nil
    case Configured.Use(cs)      => cs
  val resolvedLint: Option[Lint] = lint match
    case Configured.FromSettings =>
      Option.when(ctx.stackSettings.lint.nonEmpty)(
        Lint(ctx.stackSettings.lint, ctx.agent.cheap)
      )
    case Configured.Off    => None
    case Configured.Use(l) => Some(l)
  ```
  Downstream, the config carries `List[String]` for format (the format
  step runs each command in order — check how the single `formatCommand`
  is executed today and generalize the same way `lint` did: sequential
  `bash -c`, `cwd = ctx.workDir`) and `Option[Lint]` as before. Verify
  `ctx.agent` is the right lead accessor spelling on `FlowContext` (grep;
  it may be `agent` or `leadAgent` — use what exists).
- Modify: `flow/src/test/scala/orca/review/`… (`ReviewAndFixTest`,
  `ReviewFixFlowTest`, `FixLoopTest`, `ReviewLoopFixture` — whichever the
  compiler flags): migrate `formatCommand = Some(x)` →
  `formatCommands = Configured.Use(List(x))`, `lint = Some(l)` →
  `lint = Configured.Use(l)`, omissions stay omitted (now FromSettings —
  fixtures' TestFlowContext defaults to `StackSettings.empty`, so
  behavior is unchanged for them: empty ≡ no gate).
- Tests (new, in the review suite): each arm —
  1. `FromSettings` + non-empty settings → format commands run (observable
     via the existing format-step test seam) and lint gate built with the
     lead's cheap tier;
  2. `FromSettings` + empty settings → no format, no lint (≡ today's
     omission);
  3. `Off` + non-empty settings → gates off despite settings;
  4. `Use` beats non-empty settings.

- [ ] Failing tests → implement → `flow/test` green → commit:
      `git commit -m "reviewAndFixLoop defaults format/lint from stack settings (Configured)"`

### Task 3: canaries + runner compile

**Files:** `runner/src/test/scala/flowtests/FlowCompilesTest.scala` —
migrate existing `formatCommand`/`lint` canaries to the ADR's migration
shapes (`formatCommands = Use(List("cargo fmt"))`,
`lint = Use(Lint(List("sbt Test/compile"), agent.cheap))`, plus one
`Configured.Off` and one bare-omission call), fix anything else
`runner/Test/compile` flags.

- [ ] Green `runner/test` → commit:
      `git commit -m "Configured canaries; runner surface migrated"`

### Task 4: examples

**Files:** `examples/implement.sc`, `implement-interactive.sc`,
`implement-enhanced.sc`, `epic.sc`, `issue-pr.sc`, `issue-pr-bugfix.sc`.

Examples are NOT sbt-compiled (correctness gate = Task 3 canaries), so
edit textually with care:
- Drop `formatCommand = ...` / `lint = ...` lines from the
  `reviewAndFixLoop` calls — settings are the default now. Update each
  script's scaladoc: mention `.orca/settings.properties` is
  auto-discovered on first run (one sentence; the scripts stay
  stack-agnostic).
- ONE example keeps an explicit override to demonstrate the escape hatch —
  in `issue-pr-bugfix.sc` use `lint = Configured.Use(Lint(List("sbt Test/compile"), agent.cheap))`
  with a comment saying overrides beat the settings file.
- Any `Lint("cmd", ...)` single-string forms left → `Lint(List("cmd"), ...)`.

- [ ] Commit: `git commit -m "Examples read stack settings by default"`

### Task 5: README + AGENTS.md

**Files:** `README.md`, `AGENTS.md` (only if it references `.orca` or
format/lint parameters — grep first).

README (find the reviewAndFixLoop / flow-parameters sections; the README
keeps its full API reference — extend, don't restructure):
- New "Stack settings" subsection: the settings file (path, key=value
  format, repeated keys, lists semantics, own-line evidence comments,
  delete-to-rediscover), auto-discovery (one cheap read-only agent run on
  first use; evidence-cited; PATH + evidence checks demote to commented
  lines; failure aborts the run — quote the actual event lines from the
  landed integration tests), the `.orca/` layout (committed by default,
  self-ignoring `cache/`, migration warning for legacy `.orca/` ignores).
- `flow(stackSettings = ...)` in the flow-parameters reference table.
- `reviewAndFixLoop` reference: the `Configured` three states with the
  migration mapping from the ADR's Consequences (`Some(x)` → `Use(...)`,
  omission now = FromSettings, `Off` to opt out) and the behavior change
  called out (scripts that omitted `lint` gain a gate when settings
  define one).

- [ ] Commit: `git commit -m "Document stack settings, discovery, and Configured defaults"`

### Task 6: full-build gate

- [ ] Full `sbt --client test` green, zero warnings, clean status; report
      SHAs + deviations. This closes ADR 0019's implementation.

## Self-review notes

ADR § API: Configured shape + FromSettings→`Lint(stackSettings.lint,
cheap)` + empty≡Off (T1/T2), precedence chain end-to-end (T2 tests + the
lifecycle already landed), migration shapes + behavior change (T3/T5),
examples stack-agnostic (T4), README self-contained reference (T5).
