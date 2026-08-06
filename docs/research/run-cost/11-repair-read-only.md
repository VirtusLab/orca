# 11 — Keep the read-only tier, repair claude

**Decision (owner, 2026-08-05): `withReadOnly` / `ToolSet.ReadOnly` stays.**
The earlier decision to remove it is reversed. claude gets repaired instead.
This file records why the decision changed and plans the repair. It replaces
the removal plan that was here before (`11-drop-read-only.md`); §5 lists the
errors that plan contained, because a repair touches the same places.

## 1. Why the premise flipped

The failure was never the feature. It is the mechanism two backends use.
Backends that **remove the capability** enforce it. Backends that **set an
approval mode and ask the model to respect it** do not.

| backend | mechanism | measured |
|---|---|---|
| codex | `--sandbox read-only` (`CodexArgs.scala:134`) | **blocked** — codex policy refusal plus kernel `EROFS`; uses system `/usr/bin/bwrap` (selected by probe); Landlock fallback also blocks, with `EACCES`; `strace`-confirmed |
| opencode | write/edit/bash/patch disabled on the message body (`OpencodeArgs.scala:84-90`) | **blocked** — `OpencodeIntegrationTest.scala:94-105`, live server |
| pi | `--tools read,grep,find,ls` (`PiArgs.scala:16,52`) | **unverified** — flag-level only; `PiArgsTest.scala:35-42` pins the emitted string, nothing measures pi |
| claude | `--permission-mode plan` (`ClaudeArgs.scala:123`) | **not blocked** — the `init` tool list is byte-identical to default mode; #73 measured 199 `Bash` calls, zero denials, one wrote to `/tmp` |
| gemini | `--approval-mode plan` (`GeminiArgs.scala:78`) | **unverified** — no credentials on this host; gemini also silently overrides `plan` to `default` in untrusted folders, which is where orca runs agents |

Removing the feature would have discarded three working mechanisms — one of
them an OS sandbox — to fix two that never could work.

Reproduced here against the installed claude (2.1.222), default vs plan mode,
same prompt: both `init` frames advertise the same 32 built-in tools, including
`Bash`, `Write` and `Edit`. `permissionMode` reports `plan`. Plan mode changes
what the model is told, not what it has.

## 2. The repair: three candidates, one works

**claude's own sandbox — no.** With `sandbox.filesystem.denyWrite` in force and
`--permission-mode bypassPermissions` (so only the sandbox could block), the
`Write` tool created a file and `Edit` rewrote another. It covers `Bash` only.
Separately, `denyWrite` on the cwd breaks `Bash` entirely (`bwrap: Can't create
file at <cwd>/.gitconfig`), and cwd = the repo is orca's shape.

**`--disallowedTools` — no.** With `Write,Edit,NotebookEdit,Bash` disallowed,
opus still wrote a file through the `Monitor` tool, which runs in the same shell
environment as `Bash`. `Task`/`Agent` and `EnterWorktree` are further write
primitives. A denylist only removes the names you thought of.

**`--tools` (allowlist) — yes.** With `--tools Read,Grep,Glob,WebFetch,WebSearch`:

- only those five plus MCP tools are advertised;
- seven escape routes under `bypassPermissions` all failed, with
  `permission_denials: []` — there was nothing to deny;
- `ToolSearch select:Bash,Write,Edit,Monitor,Task,NotebookEdit,EnterWorktree`
  returned "No matching deferred tools found" for all seven, so it filters the
  deferred registry too;
- subagents inherit it;
- it survives `--resume`, which matters because orca resumes every turn.

Reproduced here on 2.1.222: `--tools Read,Grep,Glob,Skill,WebFetch,WebSearch`
gives an `init` list of exactly those six, plus two `mcp__…` tools (task R5).

**Corrected during R1 (PR #89): the last two bullets are wrong.**

`--tools` does **not** survive `--resume`. Measured on 2.1.222: a session
created under `--tools Read,Grep,Glob,Skill`, resumed without re-passing the
flag, comes back with the full default set of 28 tools — `Bash`, `Edit` and
`Write` included. The flag is per-invocation, not session state. Orca is safe
because `ClaudeArgs.streamJson` rebuilds every turn's flags from that turn's own
config, which `ClaudeArgsTest` now pins; a resume path that reused stored args
would silently unlock every reviewer.

"Subagents inherit it" describes a path that cannot be taken: `Task` is not in
the allowlist, so a read-only turn cannot spawn a subagent at all.

## 3. Development plan

### **[TODO]** R1 — Replace the mechanism
`ClaudeArgs.scala:123`: `--permission-mode plan` → a `--tools` allowlist.
Proposed `ReadOnly`: `Read,Grep,Glob,Skill`. Proposed `NetworkOnly`: the same
plus `WebFetch,WebSearch`, and the two MCP tools settled below.

Verify every name against the installed claude rather than trusting this list.
Two things already found on 2.1.222: `TodoWrite` no longer exists (it is
`TaskCreate`/`TaskUpdate`), and `Glob` is accepted by `--tools` but is **not**
in the default `init` list — so "appears in the default list" is not the test
for whether a name is valid.

`NetworkOnly` does not survive a straight swap either. Its point is that a
planner can read an issue or PR (`Plan.scala:162-166`), and today it gets that
from `--allowedTools` entries that are command-scoped `Bash(gh …)`
(`ClaudeBackend.scala:302-310`). `--tools` takes bare built-in names, so
`Bash(gh issue view:*)` cannot appear in it.

**The shape is settled** — `12-reviewer-tool-surface.md` (#84) probed it:

- Keeping `Bash` and scoping it with `--allowedTools` does not work. The
  patterns grant; they do not confine. With `Bash(git log:*)` as the only rule,
  `ls -la`, `wc -l` and `git status --short` all ran, none matching a rule and
  none denied (`12-…` §4). The production `NetworkOnly` flag set behaves the
  same way: `uname -r` and `git log` ran under it, unlisted and undenied
  (`12-…` §5). That route would end the no-edit tier and buy no real scope.
- The planner does not use `gh` anyway: **zero** invocations in 59 `Bash` calls
  across 6 planner sessions, every one of them `grep`, `ls`, `find` or `sed`
  (`12-…` §5). Orca already fetches issues host-side
  (`GitHubTool.readIssue`/`readIssueComments`), so the issue body reaches the
  planner through the prompt.
- So `NetworkOnly` becomes `--tools Read,Grep,Glob,Skill,WebFetch,WebSearch`
  plus two MCP tools — `github_issue` and `github_pr` (`12-…` §6). MCP tools
  pass `--tools` unfiltered and were verified callable in a session with no
  `Bash` (`12-…` §4). `github_issue` wraps `GitHubTool.readIssue`;
  `github_pr` needs a host-side read `GitHubTool` does not have yet (it reads
  PR comments, not the PR body). The tier stays genuinely no-edit; it does not
  drop to `PromptOnly`.

*Done when:* `ClaudeArgsTest` pins the emitted flags for both tiers.

### **[TODO]** R2 — Assess what loses `Bash`, and say so plainly
This is the real cost of the repair and must not be glossed. Six production
call sites; for each, what breaks without a shell:

| site | turn | without `Bash` |
|---|---|---|
| `Reviewers.scala:147` | every shipped reviewer | the bet, below |
| `ReviewerSelector.scala:149` | reviewer picker | fine — it is handed the task title, the changed file names and the reviewer descriptions; its own brief already hedges on the shell (`ReviewerSelector.scala:83-88`) |
| `Lint.scala:144` | lint summariser | fine — it reads captured lint output and emits JSON; large output already spills to a file in `.orca/cache/` that it opens with `Read` (`Lint.scala:108-122`) |
| `Plan.scala:223` | plan self-review | resumes the planning session, created `NetworkOnly` (`Plan.scala:179`), but the turn itself runs `withReadOnly` — so under R1 it gets the reviewer surface: no `gh`, and not the planner's GitHub tools either |
| `StackDiscovery.scala:91` | stack discovery | **unaffected** — ADR 0019:215-216 already asserts "the discovery agent's read-only toolset has no shell", and orca runs the `command -v` and evidence-file checks itself |
| `Agent.scala:163` | `cheapOneShot` | fine — one line of text, for branch names and commit messages |

**Reviewers are the bet.** They measurably use `Bash` today: #73 counted 199
calls across ten sessions, and the T6.2 re-measurement counted 47 `Bash` and 22
`Read` across five (`06-preamble-measurement.md:314-318`). The claim is that
they no longer need it, because the diff has been inlined into the prompt since
#73. That is a bet, not a measurement. What would falsify it: reviewers under
the allowlist reporting fewer or shallower findings than the same reviewers with
a shell on the same change set, or reviewers saying in their output that they
could not check something.
*Done when:* the table above is in the PR body, and one flow has been run under
the allowlist with its reviewer findings compared against a shell-enabled run of
the same change set.

### **[TODO]** R3 — Make the enforcement claims true
`EnforcementTableTest.scala:47-51` asserts `ReadOnly → Hard` for all five
backends. After R1 that becomes true for claude. It is **not** established for
gemini and must not be asserted.

`EnforcementTableTest.scala:80` compares against the live backend
(`get(name).enforcement(tools, approve)`), so moving a cell means changing the
backend too: `ClaudeArgs.scala:153` and `GeminiArgs.scala:104` each return
`Hard` for `ReadOnly | NetworkOnly` in one arm, and have to be split.

That `Hard` is false for `NetworkOnly` on claude today, not merely unmeasured:
three of the six planner sessions in the corpus called `Write` and created
files, each returning "File created successfully" (`12-…` §5). It becomes true
with R1's allowlist.

What should read what:

- `AGENTS.md:160-178` — `ReadOnly` row: claude `Hard` (allowlist), codex `Hard`,
  opencode `Hard`, pi `Hard`, gemini `PromptOnly` with one sentence saying it is
  unmeasured, not weak. `NetworkOnly` row: claude `Hard` once R1 lands, since
  the tier keeps its network access through MCP rather than `Bash`.
- `ClaudeArgs.scala:102-117` and `:142-149` — the scaladoc says plan mode "makes
  Edit/Write/Bash unavailable (not just non-auto-approved) — a hard no-edit
  guarantee". False. It becomes true of the allowlist.
- `GeminiArgs.scala:91-101` — "`plan` makes writes and shell mechanically
  unavailable". Unverified; say so.
- `AgentConfig.scala:74-86` (`ToolSet`) and `:57-70` (`Enforcement`) —
  `ReadOnly` reads "reads only; no shell, no edits", which is the intent and is
  what the allowlist delivers. Keep the wording, add the per-backend caveat.
- `ReviewerSelector.scala:83-88` — "codex's read-only sandbox still runs
  commands, claude's plan mode doesn't" is backwards on claude. After R1 no
  read-only turn runs commands on claude, and the picker brief in
  `select-reviewers.md:6-9` can drop its shell hedging.

*Done when:* no scaladoc, no `AGENTS.md` cell and no test asserts a guarantee
that has not been measured on that backend.

### **[TODO]** R4 — Pin the allowlist with an integration test
Unknown tool names are dropped **silently**. Measured on 2.1.222: `--tools
Read,Grep,NoSuchTool` yields an `init` list of `Grep,Read`, exit 0, no warning.
So a CLI rename would strip a tool from every read-only turn with no signal —
reviewers would quietly lose `Grep` and nobody would know.
*Done when:* an integration test runs claude with the shipped `ReadOnly`
allowlist and asserts the `init` frame's tool list equals the expected set
(ignoring `mcp__*`), so a dropped name fails the build.

### **[TODO]** R5 — Record that `--tools` is not a complete boundary
MCP tools pass through `--tools` unfiltered. Measured: with
`--tools Read,Grep,Glob,Skill,WebFetch,WebSearch` the `init` list also carried
two `mcp__…` tools from an installed MCP server. Orca's own `ask_user` MCP tool
surviving is fine and wanted; an MCP server that can write would not be covered
by the allowlist at all.
*Done when:* the caveat sits on `ClaudeArgs.autoApproveArgs`' scaladoc next to
the allowlist itself, where the next person changing it will read it.

## 4. The rest of the surface a repair touches

The removal plan's inventory is still the right inventory — a repair edits the
same places, it just does not delete them.

**Two definitions.** `Agent.scala:116-120` (`withReadOnly`, the public API) and
`BaseAgent.scala:43` (return-type narrowing). Both stay.

**Fourteen backend arms** match `ToolSet.ReadOnly`. Flags:
`ClaudeArgs.scala:123`, `CodexArgs.scala:134`, `GeminiArgs.scala:78`,
`OpencodeArgs.scala:84-90`, `PiArgs.scala:52`. Enforcement:
`ClaudeArgs.scala:153`, `CodexArgs.scala:165`, `GeminiArgs.scala:104`,
`OpencodeArgs.scala:110`, `PiArgs.scala:78`. Secondary: `CodexArgs.scala:91`
(resume), `CodexArgs.scala:151` (network config), `PiArgs.scala:54`
(`NetworkOnly` reusing `ReadOnlyTools`). Only claude's two change.

`SystemPromptComposer.scala:79` reads the tier without naming `ReadOnly`: it
gates the `RuntimeOwnsGit` standing rule on `config.tools == ToolSet.Full`,
which is how read-only turns avoid being told "leave your edits uncommitted".
Keeping the tier keeps that gate correct; no change needed.

**Tests: 37 references across 17 files.** Nothing has to be retargeted, because
nothing is deleted. Two need updating with R1/R3:
`EnforcementTableTest.scala:47-51` (the cells) and `ClaudeArgsTest.scala:97-103`
and `:127-131` (the emitted flags). `CodexArgsTest.scala:165-190` — the
`execResume` test, whose `ReadOnly` case is at `:171-177` — was missing from the
removal plan's sweep; it is unaffected here, and listed so the next sweep has it.

**Documentation** naming the tier: `AGENTS.md:160-178`,
`README.md:171,180,229-230,389`,
`adr/0016-toolset-capability-axis-and-planner-network.md`,
`adr/0019-project-stack-settings.md:157-160,215-216`,
`flow/src/main/scala/orca/accessors.scala:49`,
`runner/src/main/scala/orca/runner/WiredAgents.scala:47`,
`tools/src/main/scala/orca/agents/Agent.scala:111,247`,
`flow/src/main/resources/orca/review/prompts/select-reviewers.md:6-9`,
`flow/src/main/scala/orca/review/Reviewers.scala:129-134`,
`flow/src/main/scala/orca/review/Lint.scala:130-134`. Most become true after R1;
the sentences that stay false are listed in R3.

## 5. Errors in the removal plan this file replaces

Recorded so they are not repeated, not because removal is still live.

- **Its Step 1 was not "a pure documentation and test change".**
  `EnforcementTableTest.scala:80` compares against the live backend, so a cell
  cannot move without `ClaudeArgs.scala:153` and `GeminiArgs.scala:104` moving
  with it.
- **`Plan.reviewed` on codex is not a no-op.** Dropping the tier there makes the
  config `Full` + `AutoApprove.All`, and `resumeSandboxArgs`
  (`CodexArgs.scala:85-89`) emits `--dangerously-bypass-approvals-and-sandbox`
  for exactly that combination — which `exec resume` does accept
  (`CodexArgs.scala:55-58`). That is a widening from the inherited
  workspace-write sandbox to no sandbox at all. Moot now, but it belongs on the
  record.
- **`CodexArgsTest.scala:165-190` was missing from the test sweep.**
- **The file count was wrong**: it said ~36 references across 15 files while its
  own list named 17. Actual: 37 across 17.

## 6. Unverified, and staying that way for now

- **pi.** `--tools read,grep,find,ls` is an allowlist, the same shape as the
  claude repair, so it is very likely real — but nothing has measured pi.
  `PiArgsTest` pins the emitted string only.
- **gemini.** No credentials on this host. Same mechanism class as claude's
  broken one, so `Hard` at `GeminiArgs.scala:104` should not be treated as
  established. gemini additionally overrides `--approval-mode plan` to `default`
  in untrusted folders — which is where orca runs agents — so even a passing
  probe in a trusted folder would not settle it.
- **Whether reviewers still find what they found with a shell.** R2's bet.

## Related work

- `12-reviewer-tool-surface.md` (PR #84) — what reviewers and planners actually
  did with `Bash`, and the tool surface to give back. It settles R1's
  `NetworkOnly` question and measures the `NetworkOnly` `Hard` cell as false.
- `10-filesystem-sandbox.md` (PR #80) — whether an orca-owned OS sandbox could
  replace the per-backend mechanisms. Answer: no. It breaks codex, which nests
  its own bubblewrap per shell command, and codex is the backend whose
  enforcement already works. That file is being corrected separately; this one
  does not restate it.
- The false scaladocs listed in R3 are being fixed separately on master. R3 says
  what they should read; it does not own the edit.
