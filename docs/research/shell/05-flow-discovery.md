> Final (codebase-factual topic; no adversarial pass).

# 05 — Flow discovery & well-known paths

Topic 5 of the [shell research plan](00-research-plan.md): where discoverable
flow scripts live (project / user-global / built-in), how a listing extracts a
one-line description, and what happens when the same filename exists at more
than one tier.

## 1. Current `.orca/` inventory — is `flows/` free?

All `.orca/` writes route through `tools/src/main/scala/orca/OrcaDir.scala`
(the single owner of the layout, per ADR 0019). Current contents:

| Entry | Owner | Committed? |
|---|---|---|
| `.orca/settings.properties` | `OrcaDir.settingsPath`, `SettingsFile`, `FlowLifecycle` (ADR 0019/0020) | yes (warned about if gitignored) |
| `.orca/progress-<promptHash>.json` | `OrcaDir.progressPath`, `ProgressStore` (ADR 0013/0018) | yes — force-added, rides the branch |
| `.orca/cache/` | `OrcaDir.ensureCache` | no — self-ignoring `.gitignore` (`*`) + `CACHEDIR.TAG` |
| `.orca/cache/flow.lock` | `runner/.../FlowLock.scala` | no (in cache) |
| `.orca/cache/lint-*.txt` | `flow/.../review/Lint.scala` (spilled lint output) | no (in cache) |

Not in `.orca/`: claude's transient MCP config is `.orca-mcp-<port>.json` in
the *workDir root* (`ClaudeBackend`, ClaudeBackend.scala:232), so it is
irrelevant here. `GitTool`
excludes `.orca/*` from review diffs via pathspec `:(exclude).orca/*` — a
flows subdirectory would automatically be excluded from review diffs too,
which is the right behaviour (a flow script is orchestration bookkeeping, not
the change under review).

**`.orca/flows/` is collision-free.** Nothing creates a `flows` entry today;
the only subdirectory is `cache/`. Two implementation notes:

- Creation must go through `OrcaDir` (AGENTS.md hard rule: every `.orca/`
  write goes through `ensureRoot`/`ensureCache`, which run the symlink guard
  `abortIfOrcaComponentSymlink`). Add an `OrcaDir.flowsPath` /
  `ensureFlows` alongside the existing helpers. The shell mostly *reads* this
  dir (listing), which needs no guard; the guard matters if the shell ever
  writes a generated flow there (the "create a flow via harness" menu item).
- The progress files are flat at the `.orca/` root (`progress-*.json`), so
  the listing code should enumerate only `.orca/flows/*.sc`, never `.orca/*`.

### The gitignore question — is there tension?

Less than the plan feared. ADR 0019 already flipped `.orca/` to
**committed-by-default**: settings and progress ride the branch; everything
ephemeral lives under `.orca/cache/`, which self-ignores. README ("Discovery
internals and the `.orca/` directory") documents this, and
`FlowLifecycle.warnIfSettingsIgnored` nags on every run if a legacy repo still
gitignores `.orca/`. So "project flows SHOULD be committed" is not in tension
with the existing policy — it is exactly the existing policy. Legacy repos
that ignore `.orca/` wholesale would hide their flows from git, but they
already get a per-run warning telling them to remove that line.

### `.orca/flows/` vs a root-level `flows/` — prior art

- **`.github/workflows/`** — committed executable definitions nested inside a
  tool-owned dotdir. Precedent *for* `.orca/flows/`: users demonstrably accept
  committed runnable content inside a dot-directory; discoverability comes
  from the tool's listing UI, not from the directory being visible at root.
- **`.claude/commands/`** (Claude Code slash commands) — same shape:
  committed markdown "scripts" in a tool dotdir, with a parallel per-user tier
  (`~/.claude/commands/`), listed by the tool with origin labels. This is the
  closest analogue to the shell's three-tier listing.
- Root-level `flows/` (cf. `scripts/`, `tasks/`): more visible in file
  browsers, but claims a generic name in the user's project namespace, needs
  its own "is this orca's?" convention, and splits orca's footprint across two
  locations (settings in `.orca/`, flows elsewhere).

**Recommendation: `.orca/flows/*.sc`.** One orca-owned root, consistent with
ADR 0019's committed-by-default polarity, zero collision risk, and matching
the `.github/workflows` / `.claude/commands` precedent. A flow there remains
directly runnable (`scala-cli run .orca/flows/my-flow.sc`), so the shell adds
discovery without becoming mandatory.

## 2. User-global flows: config home, not data home

Global settings live at `$XDG_CONFIG_HOME/orca/settings.properties`
(`flow/src/main/scala/orca/settings/GlobalSettings.scala`, ADR 0020), with the
spec-mandated fallback to `~/.config/orca/` and relative-value rejection.

XDG Base Directory spec distinction: `XDG_CONFIG_HOME` is for "user-specific
configuration files"; `XDG_DATA_HOME` for "user-specific data files". In
practice the split is *user-authored & portable* (config) vs *app-managed or
downloaded* (data — caches of durable value, downloaded fonts, app databases).
User-written flow scripts are things the user edits, wants in their dotfiles
repo, and expects to survive app reinstalls — the same category as fish
functions (`~/.config/fish/functions/`), nvim lua config, or zsh custom
snippets, all of which are executable user-authored code living under config
home. Data home would be appropriate for e.g. a cached copy of built-in flows
downloaded by the shell — not for flows the user wrote.

**Recommendation: `$XDG_CONFIG_HOME/orca/flows/` (default
`~/.config/orca/flows/`).** Reuse `GlobalSettings`' env handling (extract the
`configHome` resolution so settings and flows share it) — same fallback, same
relative-path rejection. Co-locating with `settings.properties` gives users a
single `~/.config/orca/` to back up.

## 3. Built-in flows: discovery interface

Topic 4 owns the packaging mechanics (outcome: jar resources + a generated
index). For discovery, the shell needs one interface over all three tiers:

```scala
enum FlowOrigin:
  case Project   // {workDir}/.orca/flows/
  case Global    // $XDG_CONFIG_HOME/orca/flows/
  case BuiltIn   // shipped with the shell distribution

case class DiscoveredFlow(
    name: String,            // filename incl. `.sc`, the identity for shadowing
    description: Option[String], // extracted per §4; None → filename-only row
    origin: FlowOrigin,
    source: FlowSource       // os.Path for Project/Global; resource/URL for BuiltIn
)
```

Each tier contributes `(name, description, origin)`; only `source` differs.
Built-in listing must not require network or compilation — whatever topic 4
picks, the names + first lines must be readable cheaply (e.g. a generated
index in the jar, or just reading the bundled `.sc` resources).

Listing format (menu row): `name — description  [origin]`, e.g.

```
implement.sc            Plan a prompt into tasks and implement each…   [built-in]
release.sc              Tag, publish and announce a release.           [project]
```

## 4. Description extraction rule

### scala-cli directive placement — what the docs and the tool actually do

The scala-cli guide states directives "can be only declared **before any other
Scala code**" (Using directives guide,
<https://scala-cli.virtuslab.org/docs/guides/introduction/using-directives/>);
the reference page adds no placement rules. The docs do *not* say whether
plain comments may precede or interleave with directives, so this was verified
empirically against scala-cli 1.14.0:

| Leading content | Directive honored? |
|---|---|
| plain `//` comment, then `//> using scala 3.7.1` | yes (compiled with 3.7.1) |
| blank lines, then `//` comment, then directive | yes |
| `/** … */` block comment, then directive | yes |
| directive interleaved *between* two directives' comments | yes |
| `val x = 1`, then directive | **no** — warning: `Ignoring using directive found after Scala code` |

So the directive block is the leading run of blank lines, comments, and
directives; only real code terminates it, and a description comment placed
*above* the `//> using` lines is safe.

### The rule

> The description of a flow script is the first non-empty line, within the
> file's leading block of blank lines / `//` comments / `//>` directives, that
> is a `//` comment but **not** a `//>` directive — with the `//` marker and
> surrounding whitespace stripped. Scanning stops at the first line that is
> none of these (code, or a `/*`/`/**` block comment); if nothing matched by
> then, the flow has no description and is listed by filename alone.

Deliberate simplifications, and why:

- **No scaladoc parsing.** All six existing examples start their prose with a
  `/** … */` block; pulling its first line would work today but drags in
  block-comment parsing (open/close, `*` gutters, one-liners) for marginal
  gain. Instead, add one `//` description line to each flow (drafts below) —
  the convention the shell will teach flow authors anyway.
- **Line 1 is the convention, but not the requirement.** Recommended layout
  puts the description as the very first line (verified safe above); the rule
  also finds a description placed after the directives, so existing
  directive-first files need only a one-line insertion anywhere in the
  leading block.
- `//>` is checked before `//` (every directive is also a `//` prefix match).

### Description drafts for the existing examples

Runnable examples (`examples/runnable/*/`) contain no `.sc` files — their seed
scripts copy `implement.sc` / `implement-interactive.sc` from `examples/`, so
only the six top-level scripts need lines. Proposed line 1 of each file
(imperative, ≤80 chars including the marker):

Paths below become `flows/*.sc` after topic 4's move out of `examples/`.

| File | Proposed first line |
|---|---|
| `examples/implement.sc` | `// Plan a prompt into tasks and implement each with a review-and-fix loop.` |
| `examples/implement-enhanced.sc` | `// Plan (with a self-review pass), implement per task, then open a PR.` |
| `examples/implement-interactive.sc` | `// Plan interactively, asking clarifying questions, then implement the tasks.` |
| `examples/epic.sc` | `// Run a multi-task epic: plan, implement per task, cross-agent review, docs.` |
| `examples/issue-pr.sc` | `// Turn a GitHub issue (owner/repo#N) into an implemented, opened PR.` |
| `examples/issue-pr-bugfix.sc` | `// Triage a Scala bug report; reproduce with a failing test, fix, open a PR.` |

These are drafts for the implementation phase, to be inserted as line 1
(before the `//> using` block — verified harmless).

## 5. Shadowing & precedence

**Resolution order: project > global > built-in**, keyed on filename. This
matches every relevant precedent in and around orca: settings precedence is
programmatic > project > global > default (ADR 0020, `runner/.../flow.scala`);
`.claude/commands/` puts project commands over user commands; PATH-like
resolution generally favors the most local definition. The rationale is the
same everywhere: the closer to the project, the more specific the intent — a
project that ships its own `implement.sc` has deliberately specialized it.

**Show shadowed entries, labeled — don't hide them.** Claude Code lists both
project and user commands with `(project)` / `(user)` origin labels rather
than silently dropping one, and that transparency is worth copying: a user
whose global flow stopped appearing would otherwise have no clue why. But a
selection menu should have one row per name, so:

- One row per filename, the *winning* tier's description, its origin label.
- A shadowed name annotates the winner's row: `implement.sc … [project,
  shadows built-in]`.
- Selecting always runs the winner; no UI to pick a shadowed tier in v1
  (running a shadowed flow directly via `scala-cli run <path>` always works).

This keeps the menu unambiguous while making shadowing visible and
debuggable.

## Summary of proposals

1. Project flows: `{workDir}/.orca/flows/*.sc`, committed (consistent with ADR
   0019's committed-by-default `.orca/`); created via a new `OrcaDir` helper.
2. Global flows: `$XDG_CONFIG_HOME/orca/flows/`, sharing `GlobalSettings`'
   config-home resolution; config home, not data home, because flows are
   user-authored, dotfile-portable content (fish-functions precedent).
3. Discovery interface: `DiscoveredFlow(name, description, origin, source)`
   with `FlowOrigin.{Project, Global, BuiltIn}`; built-in tier's listing must
   be cheap (no compile/network).
4. Description = first non-directive `//` comment line in the leading
   blank/comment/directive block; convention: file's line 1. Verified against
   scala-cli 1.14.0 that comments before/between directives are harmless and
   that directives after code are ignored with a warning.
5. Precedence project > global > built-in; one menu row per name with origin
   label plus a "shadows <tier>" annotation.
