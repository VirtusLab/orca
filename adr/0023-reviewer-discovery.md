# 0023. Reviewer discovery

Status: Accepted · Date: 2026-09-17
Related: [ADR 0011](0011-reviewer-roster.md) (the shipped roster, the prompt
format and the picker — this ADR adds the file tiers around it and changes where
a reviewer's metadata lives), [ADR 0019](0019-project-stack-settings.md)
(`.orca/` committed-by-default and its symlink rule),
[ADR 0021](0021-orca-shell.md) §5 (the same three-tier shape for flow scripts).

## Context

Every project has review rules of its own: a layering convention, a logging
policy, a rule about which module may depend on which. The only way to have a
reviewer enforce one was to write a flow script that composed its own
`List[Reviewer]` — so a project that just wanted one extra reviewer had to fork
a flow, and the rule lived in the flow rather than with the code it governs.

The reverse also bit. The shipped prompts under
`flow/src/main/resources/orca/review/prompts/reviewers/` go to every project
orca runs in, and there is no per-project way to retune one. `scala-fp` is the
clearest case: its `files:` gate already keeps it off a change with no Scala in
it, but a Scala team that disagrees with its advice has no way to say so.

ADR 0021 §5 already settled the shape for flow scripts — project, user-global,
built-in — and users know it. Reviewers are the same kind of thing: a named
prompt file that a run picks up.

## Decision

### Three tiers

A reviewer is an `.md` file with frontmatter, read from three tiers:

| Tier | Where |
|---|---|
| Project | `{workDir}/.orca/reviewers/*.md` (`OrcaDir.reviewersPath`), committed |
| Global | `$XDG_CONFIG_HOME/orca/reviewers/*.md` (`ConfigHome.reviewers`, default `~/.config/orca/reviewers/`) |
| Built-in | the shipped classpath resources ADR 0011 defines |

`ReviewerCatalog.discover(projectDir, globalDir)` reads the two file tiers and
resolves them against the shipped set. A missing tier directory contributes
nothing.

### Precedence and shadowing

A reviewer's identity is its **filename stem**, lower-cased:
`.orca/reviewers/orca.md` is the reviewer `orca`. Lower-casing matches how
`SelectedReviewers.pick` resolves the picker's reply, so `Scala-FP.md` and
`scala-fp.md` are one reviewer rather than two that both run. Two files in one
tier claiming the same slug abort the run — picking a winner would drop the
other silently.

Precedence is **project > global > built-in**, by slug. Shadowing is the
documented way to override a shipped reviewer: a file named `scala-fp.md`
replaces the shipped `scala-fp` **in place**, keeping its position in the
roster, so retuning a reviewer does not change how many run. A `name:` key in
the frontmatter cannot move a file to another slug — the filename is the only
handle, and there is one way to say which reviewer a file is.

### Additive reviewers join both presets

A slug nothing ships is appended to the roster, sorted by name, and appears in
**both** `allReviewers` and `minimalReviewers` — `ReviewerCatalog.all` and
`.minimal`. A project rule applies to a small diff as much as a large one, and
`minimal` exists to cut *orca's own* default breadth, not to overrule what the
project asked for. The picker (`ReviewerSelector.agentDriven`) is what narrows
per task, so the cost of being in the list is not the cost of running.

A **shadowing** reviewer is not additive: it replaces the shipped reviewer
wherever that reviewer already appears, so shadowing a reviewer outside the
minimal three changes `allReviewers` and leaves `minimalReviewers` alone.

### Frontmatter contract

What the shipped prompts already use, and now the contract for user-authored
files too (`reviewerFrom`). This section supersedes ADR 0011's "Prompt format",
which predates both the `files:` key and the current body shape:

- `description:` — **required**. The reviewer-picker decides from it.
- `files:` — optional regex, substring-matched against each changed path
  (`Reviewer.appliesTo`). A reviewer that sets none is never file-gated.
- `name:` — ignored. The filename stem is the identity.
- The body below the closing `---` is the reviewer's system prompt: a `## Scope`
  section saying what the reviewer owns and what belongs to other reviewers,
  then `## Aspects` bullets. No `## Output` section — the result schema, not the
  prompt, fixes the shape. A blank body aborts: a reviewer with no instructions
  spends a turn and reports nothing.

### Malformed files abort before any tree mutation

Discovery runs inside `buildContext`'s `surfaced` bracket, after the settings
read and **before** `FlowLifecycle.setup` — so a bad file stops the run before a
branch is created or anything is written. Every bad file is named in one
message, so a directory is fixed in one pass.

A missing `description:`, an empty body, an unreadable `files:` regex, and a
frontmatter block that never closes — or never opens — are all malformed. The
exemption is by **filename**, not by content: `README.md` and any `_`-prefixed
name sit in the directory as documents, and every other `.md` must parse as a
reviewer. A content rule would swallow the likeliest authoring mistake, an
author who forgot the block, and a reviewer silently missing from the roster
reads as a clean review — the failure this whole design refuses.

### Symlinks

A symlinked `.md` under `{workDir}/.orca/reviewers/` aborts, naming the link.
That directory is committed and orca runs against arbitrary cloned repos, so
reading through a link there would make a file from outside the tree a
reviewer's system prompt — ADR 0019's rule. Aborting rather than skipping,
because a reviewer the user installed and never ran reads as a clean review.
`os.isDir` follows links, so the tier directory itself is guarded by
`OrcaDir.assertNoOrcaSymlinks` at the call site in `buildContext`.

The **global** tier is read through links. It is the user's own config home, not
untrusted input, and `settings.properties` beside it is already read that way —
a dotfiles manager (stow, chezmoi) that links each file in is normal there, and
refusing would block every run until the links were replaced with copies.

### One step, no cap

When a tier contributes anything, discovery emits one `Step`
(`ReviewerCatalog.describe`) naming each discovered reviewer, its tier, and what
it shadows:

```text
discovered reviewers: orca (project); scala-fp (project, shadows built-in)
```

A run that found nothing says nothing. There is **no cap** on how many reviewers
a project may add: the picker narrows per task, which is the mechanism that
already bounds cost for the shipped roster.

### Metadata travels with the reviewer

Discovery forced a correctness change. The picker previously read a reviewer's
description and file pattern from by-name maps
(`ReviewerPrompts.descriptionsBySlug` / `filePatternsBySlug`, and
`agentDriven`'s `descriptions`/`filePatterns` parameters), keyed by slug — so a
reviewer the maps did not know reached the picker as a bare name with an empty
blurb and no gating. Every discovered reviewer is exactly that case.

The maps and both parameters are gone. Metadata travels with the reviewer:

- `ReviewerAgent(definition: Reviewer, agent: Agent[B])` is what
  `buildReviewers` returns and the loop entry points take — a reviewer's
  definition beside the read-only agent built from it.
- `RosterEntry` exposes `name`, `description`, `filePattern` and `appliesTo`
  off that definition; `ReviewerSelector.agentDriven(agent, instructions)` reads
  them directly.
- `ReviewerAgent`'s constructor is `private[review]`, so `buildReviewers` is the
  only mint and the agent always carries the definition's name, system prompt
  and read-only gate.

### Exposure

`FlowContext.reviewerCatalog` holds the resolved catalog, frozen for the run
like `stackSettings`; the `reviewerCatalog` accessor reaches it in a body.
`allReviewers`/`minimalReviewers` build from it, so the shipped flows pick up
discovered reviewers with no change. `ReviewerCatalog`'s public surface is `all`
and `minimal` alone — tier and shadow provenance is runner-internal, and
`discover` is the only way to build a catalog with anything in it.

## Consequences

**Positive**

- A project adds or retunes a reviewer by committing a file. No forked flow, no
  code, and the rule rides the branch with the code it governs.
- A user's own reviewers follow them across projects via the global tier.
- The picker sees every reviewer's real purpose, discovered or shipped, because
  there is one place a description can come from.
- Reviewer tiers read exactly like flow tiers (ADR 0021 §5): same precedence,
  same labels, same "shadows" vocabulary.

**Negative**

- Two more directories orca reads before a run, and a malformed file in either
  fails the run rather than degrading. Deliberate, per the abort rule above, but
  it does mean a typo in a reviewer file blocks work until fixed.
- `.orca/reviewers/` is committed, so a project reviewer is visible to everyone
  on the branch — a per-developer reviewer belongs in the global tier.
- One slug per directory, so two teams both wanting a reviewer called
  `layering` in one repo must rename one — a same-tier collision aborts rather
  than picking a winner.
- The by-name maps are gone from `ReviewerPrompts`; a flow that fed
  `agentDriven` its own `descriptions`/`filePatterns` maps composes a
  `List[Reviewer]` instead.

## Out of scope

Listing, viewing or editing reviewers from the orca shell. The shell does this
for flows (ADR 0021 §5–§6), and the same for reviewers is a natural follow-up, but
nothing here depends on it: the tiers are plain directories a user edits with
their own tools, and the setup step already says what a run picked up.
