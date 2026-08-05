# 11 — Drop `withReadOnly`

Scope: remove `withReadOnly` / `ToolSet.ReadOnly` from orca. The decision to
remove rather than repair is already made and is not re-argued here; this is the
plan for doing it. Nothing is removed yet.

Why, in short. `withReadOnly` is documented and tested as a hard no-edit gate.
On claude it is not one. #73 measured ten reviewer sessions, every one recording
`permissionMode: plan`, issuing 199 `Bash` calls with zero denials; one of them
wrote a file into `/tmp` and ran `scala-cli` on it
(`docs/research/run-cost/09-diff-vs-coordinates.md` §2). AGENTS.md's enforcement
table says `ReadOnly, * → Hard` for claude, and `EnforcementTableTest` asserts
it. So the repo documents and tests a guarantee the runtime does not provide.
The owner's reasons for removing instead of fixing: the feature has been
bug-prone; a guarantee that does not hold is worse than none, because designs
get built on it; and a reviewer reading and grepping files is legitimate work
that should not be restricted anyway.

## Answer

- **6 production call sites**, 2 definition points, 14 backend match arms,
  ~36 test references across 15 files, and 12 documentation locations.
- **`ToolSet` survives with two cases**: `NetworkOnly` and `Full`. It does not
  collapse — `NetworkOnly` is not the same feature (§2).
- **`EnforcementTableTest` is rewritten, not deleted** — the five `ReadOnly`
  rows go, and two surviving `NetworkOnly` cells are corrected from `Hard` to
  `PromptOnly` because they ride the same broken mechanism (§4).
- **The removal is not a no-op.** On codex, pi and opencode the write block is
  mechanically real and will be gone. Only claude is measured broken; gemini is
  unmeasured (§8).

---

## 1. Every call site

### 1.1 The two definitions

| Location | What it is |
|---|---|
| `tools/src/main/scala/orca/agents/Agent.scala:120` | `def withReadOnly: Agent[B] = withTools(ToolSet.ReadOnly)` — the public API |
| `tools/src/main/scala/orca/agents/BaseAgent.scala:43` | `override def withReadOnly: Self` — narrows the return type to the concrete tool |

`withTools(tools: ToolSet)` (`Agent.scala:114`, `BaseAgent.scala:41`) stays; it
is also the primitive behind `withNetworkOnly`.

### 1.2 The six production call sites

**1. `flow/src/main/scala/orca/review/Reviewers.scala:147` — every shipped
reviewer.**

Promises (scaladoc `:129-134`): "without `withReadOnly` the agent inherits the
base tool's permissions (typically `AutoApprove.All`) and could edit files
mid-review."

Who relies on it: `reviewAndFixLoop`. The loop samples the working-tree diff
between rounds; a reviewer that edited would put its own changes into the
fixer's payload and into the next round's reviewer selection. That is a real
dependency — but it is a dependency on reviewers *not editing*, which is
currently supplied by three of five backends and by prompt luck on claude.

Lost: a mechanical write block on codex, pi and opencode. Nothing on claude,
where it was already not holding.

**2. `flow/src/main/scala/orca/review/ReviewerSelector.scala:149` — the
LLM reviewer picker.**

One cheap turn choosing which reviewers to run. Its own scaladoc (`:83-88`)
already half-admits the gate does not do what it says: "That gates edits
everywhere but not the shell — codex's read-only sandbox still runs commands,
claude's plan mode doesn't". The second half of that sentence is the part #73
disproved.

Lost: the write block on three backends. The picker has no reason to write, and
its prompt is a selection request, so exposure is low.

**3. `flow/src/main/scala/orca/review/Lint.scala:144` — the lint summariser.**

`Lint.Summariser`'s constructor is `private[review]` (`:136-138`) for one stated
reason: so "the read-only restriction it applies can't be bypassed by handing
`lint` a write-enabled conversation". Once the restriction is gone that
justification is gone with it.

Keep the `private[review]` constructor anyway, but rewrite the scaladoc: the
honest reason is that a `Summariser` should be minted from an agent through
`Lint.summariser`, not assembled from an arbitrary chat. It is an API-shape
constraint, not a safety one.

Lost: the write block on three backends, on a turn whose whole job is to read
lint output and produce JSON.

**4. `flow/src/main/scala/orca/plan/Plan.scala:223` — `Sessioned[B, Plan].reviewed`.**

Resumes the planning session for a self-critique turn. On codex this call site
is **already a no-op**: the session was created `NetworkOnly` at `Plan.scala:179`,
and `CodexArgs.execResume` deliberately emits no sandbox flag on resume
(`CodexArgs.scala:84-91`, rationale at `:55-58`) because codex rejects
`--sandbox` on `exec resume` and the thread inherits the sandbox it was created
with. So the resumed turn already runs under `--full-auto`, not
`--sandbox read-only`. Removing `withReadOnly` here changes nothing on codex.

Lost: the write block on pi and opencode, and the (already false) one on claude.

**5. `runner/src/main/scala/orca/runner/StackDiscovery.scala:91` — stack
discovery.**

One cheap turn reading the repo to guess format/lint/test commands.

This is the site where a design leans on the guarantee, which is the failure
mode the owner named. `adr/0019-project-stack-settings.md:215-216` justifies a
choice with "(orca-side by necessity — the discovery agent's read-only toolset
has no shell)". The *choice* is right regardless: orca verifies each proposed
command with `command -v` and checks each cited evidence file exists, and you
would want those checks even if the agent had a shell, because you do not trust
an agent's claim that a command works. So this is a wording correction in the
ADR, not a design change. Fix the parenthetical; keep the checks.

Lost: the write block on three backends, on a turn that runs before any stage
and can see the whole repo.

**6. `tools/src/main/scala/orca/agents/Agent.scala:163` — `cheapOneShot`.**

`cheap.withReadOnly.quietTextTurn(prompt)`, used for branch names and default
commit messages. A one-line text reply. On codex today this runs under
`--sandbox read-only`; afterwards it would run under
`--dangerously-bypass-approvals-and-sandbox`. That is a real widening for a turn
that only needs to produce a string.

### 1.3 The backend arms

Fourteen places match on `ToolSet.ReadOnly`. Each maps it to flags, or
classifies the result.

| Backend | Flags emitted | Where | Real? |
|---|---|---|---|
| claude | `--permission-mode plan` | `ClaudeArgs.scala:123` | **No** — measured not to hold on opus (#73) |
| codex | `--sandbox read-only` | `CodexArgs.scala:134` | **Yes** — OS-level sandbox |
| gemini | `--approval-mode plan` | `GeminiArgs.scala:78` | Unmeasured |
| opencode | `write/edit/bash/patch: false` on the message body | `OpencodeArgs.scala:84-90` | **Yes** — server won't dispatch them |
| pi | `--tools read,grep,find,ls` | `PiArgs.scala:16,52` | **Yes** — allowlist, no write tool exists |

Plus the `Enforcement` classifications (`ClaudeArgs.scala:153`,
`CodexArgs.scala:165`, `GeminiArgs.scala:104`, `OpencodeArgs.scala:110`,
`PiArgs.scala:78`), codex's two secondary arms (`CodexArgs.scala:91` resume,
`:151` network config), and pi's `NetworkOnly` arm that reuses `ReadOnlyTools`
(`PiArgs.scala:54`).

One more place reads the tier without naming `ReadOnly`:
`tools/src/main/scala/orca/backend/SystemPromptComposer.scala:78-80` gates the
`RuntimeOwnsGit` standing rule on `config.tools == ToolSet.Full`. That is how
read-only turns currently avoid being told "leave your edits uncommitted". §5
replaces that gate.

### 1.4 Tests

Roughly 36 references across 15 files. Three groups:

- **Asserts the gate itself — must go or move.**
  `EnforcementTableTest.scala:47-51` (five rows, §4);
  `ClaudeArgsTest.scala:97-103,127-130`; `CodexArgsTest.scala:76-85`;
  `GeminiArgsTest.scala:55-60`; `OpencodeArgsTest.scala:106-124,134`;
  `PiArgsTest.scala:35-42`; `SystemPromptComposerTest.scala:15-22,31-39`;
  `DefaultOpencodeToolTest.scala:112-115`.
  The opencode and pi suites already carry equivalent `NetworkOnly` cases
  (`OpencodeArgsTest.scala:122-131`, `PiArgsTest.scala:44-51`), so deleting the
  `ReadOnly` ones loses no coverage there.
- **`OpencodeIntegrationTest.scala:94-104` — "read-only turn cannot write a
  file".** Do **not** delete this one. Retarget it to `ToolSet.NetworkOnly`,
  which emits the identical gate (`OpencodeArgs.scala:84-90`). It is the only
  live measurement backing any `Hard` cell that survives the removal.
- **Uses `ReadOnly` incidentally as a cheap config — retarget to the default.**
  `CodexIntegrationTest.scala:136,159`; `PiIntegrationTest.scala:28,38`;
  `ClaudeBackendTest.scala:234`; `CodexBackendTest.scala:372`;
  `GeminiBackendTest.scala:75`; `PiBackendTest.scala:122,166`;
  `WithCheapModelTest.scala:31`; `FlowCompilesTest.scala:83,125`.

`PiBackendTest.scala:154` ("interactive read-only config includes ask_user
extension and tool") and `PiArgsTest.scala:53-65` need care: under `Full`,
`PiArgs.toolsArgs` emits no `--tools` flag at all (`PiArgs.scala:51`), so
`includeAskUser` becomes dead on that path. Retarget both to `NetworkOnly`,
which still builds the allowlist. Before doing so, confirm against the pi CLI
that the `--extension` ask-user tool is available with no `--tools` flag — that
is the combination `Full` interactive turns will now use.

### 1.5 Documentation

`AGENTS.md:160-176` (§3); `README.md:171,180,229-230,389,607`;
`adr/0016-toolset-capability-axis-and-planner-network.md` (whole ADR);
`adr/0011-reviewer-roster.md` (referenced as "reviewers run read-only" from ADR
0016:4); `adr/0019-project-stack-settings.md:157-160,215-216`;
`flow/src/main/scala/orca/accessors.scala:49`;
`runner/src/main/scala/orca/runner/WiredAgents.scala:47`;
`tools/src/main/scala/orca/agents/Agent.scala:111,247`;
`flow/src/main/resources/orca/review/prompts/select-reviewers.md:6-9` (§5).

---

## 2. What `ToolSet` becomes

**Two cases: `NetworkOnly` and `Full`.** It does not collapse.

`NetworkOnly` is not the read-only gate under another name, even though ADR 0016
carved it out of `ReadOnly`. It selects a materially different, narrower
configuration than `Full` on two backends:

- codex: `--full-auto` (workspace-write sandbox, network on) versus
  `--dangerously-bypass-approvals-and-sandbox` (no sandbox at all).
- opencode: write tools off on the message body versus everything on.

Dropping it would put planner turns on codex outside any sandbox, and would give
opencode planners a shell they do not have today. That is a separate loss with a
separate argument behind it, and it is not what was decided.

What must change about it:

- **Its scaladoc** (`AgentConfig.scala:74-86`) currently sells the enum as "the
  capability tier … the read-only tiers gate writes; how strongly each backend
  enforces that gate is `Enforcement`". Rewrite so `NetworkOnly` reads as "a
  narrower per-turn configuration plus network access", with the no-edit
  property stated as what it is: prompt-level on claude, codex and pi,
  mechanical on opencode, unmeasured on gemini.
- **Its claude and gemini enforcement rows** are wrong for the same reason the
  `ReadOnly` rows were — `--permission-mode plan` and `--approval-mode plan` are
  the same class of CLI-side approval mode (§3).
- `AgentConfig.tools` keeps its `Full` default and `withTools` keeps its
  signature, so nothing else in the config shape moves.

Consequence for users: `withReadOnly` disappears from the public surface.
Consumers are version-pinned scripts, so there is no compatibility obligation;
the replacement at every former call site is `withReviewOnly` (§5).

---

## 3. The documentation correction

`AGENTS.md:160-176` should say this instead:

- Drop the `ReadOnly, *` row entirely.
- Change `NetworkOnly` for claude from `Hard` to `PromptOnly`. It emits
  `--permission-mode plan` plus a network allowlist (`ClaudeArgs.scala:124-132`).
  #73 measured plan mode not blocking `Bash` on opus while blocking it on haiku,
  same flag and same CLI build. A gate that depends on which model answers is
  not a mechanical gate.
- Change `NetworkOnly` for gemini from `Hard` to `PromptOnly`. `--approval-mode
  plan` (`GeminiArgs.scala:78-85`) is the same class of mechanism as claude's.
  This one is **inferred, not measured** — say so in the prose. A probe would
  settle it, and if it holds the row can go back up.
- Leave `NetworkOnly` for opencode at `Hard`. Its gate is a tool-disable map on
  the message body, and it is the one surviving `Hard` cell with a live test
  behind it (§1.4).
- Leave codex and pi `NetworkOnly` at `PromptOnly`, and all `Full` rows
  unchanged.

The corrected table:

| tools, approve  | claude     | codex         | gemini     | opencode | pi         |
|-----------------|------------|---------------|------------|----------|------------|
| NetworkOnly, *  | PromptOnly | PromptOnly    | PromptOnly | Hard     | PromptOnly |
| Full, All       | Hard       | Hard          | Hard       | Ignored  | Ignored    |
| Full, Only(_)   | Hard       | SandboxApprox | Ignored    | Ignored  | Ignored    |

`Hard` on the `Full` rows means something different from `Hard` on
`NetworkOnly` — for `Full` the thing being enforced is the approval policy
itself ("approve everything", honoured), not a write block. `Enforcement`'s own
scaladoc (`AgentConfig.scala:57-70`) already says this; the AGENTS.md prose
should repeat it in one sentence so the table is not read as five write
guarantees.

Also add one sentence naming why the claude and gemini cells moved, with a
pointer to `09-diff-vs-coordinates.md` §2. Without it the next person will
"fix" the table back.

`README.md:229-232` loses its `ReadOnly` example and its "the no-edit guarantee
is hard on claude, gemini and opencode" claim. `README.md:389` explains claude's
`haiku` → `claude-haiku-4-5` rewrite by "the read-only turns reviewers use";
reword to name planner turns, which still use plan mode. Same for the scaladoc
at `ClaudeArgs.scala:53-64`. Do not delete the rewrite itself — it is still the
correct pin, and `NetworkOnly` still puts claude in plan mode.

ADR 0016 gets a `Superseded in part` note and a pointer here, not a rewrite; it
correctly records why the axis was introduced.

---

## 4. `EnforcementTableTest`

**Rewrite. Do not delete.**

It is the machine check that keeps AGENTS.md honest, and after the removal there
are still rows worth pinning. Deleting it would let the corrected table drift
back.

Changes: delete lines `47-51` (the five `ReadOnly` rows). Change
`("claude", ToolSet.NetworkOnly, All, Hard)` → `PromptOnly` and
`("gemini", ToolSet.NetworkOnly, All, Hard)` → `PromptOnly`. Leave the rest.
Update the class scaladoc (`:15-23`) to say the table is now about which
*configuration* a `(ToolSet, AutoApprove)` pair produces and how much of it the
backend actually encodes — not about a write guarantee.

After that, nothing in the suite asserts an unenforceable property:

- Every surviving `PromptOnly` and `Ignored` cell asserts weakness, which is
  always safe to assert.
- The one surviving `Hard` write block (opencode `NetworkOnly`) is a tool-disable
  map the server honours, and the retargeted `OpencodeIntegrationTest` measures
  it against a live server.
- The `Hard` cells on `Full` rows assert that "approve everything" is passed
  through, which is a statement about flags, not about the filesystem.

---

## 5. Prompt-level guidance

The prompts may still say "review only, do not change files". They should read
as guidance, because that is what they are.

### Where the text lives

One new standing rule beside the two that exist:
`tools/src/main/resources/orca/backend/prompts/report-dont-change.md`, loaded as
`SystemPromptComposer.ReportDontChange` next to `RuntimeOwnsGit` (`:31-32`) and
`BackgroundWorkAbandonedAtTurnEnd` (`:56-59`). Same shape as those: one
unwrapped paragraph, no hard wrap, joined with blank lines.

Draft text:

> Your job on this turn is to inspect and report, not to change the project.
> Do not edit, create or delete files in the working tree, and do not run
> commands that change it. Reading, grepping and running read-only commands is
> expected — open whatever you need to check a claim. Nothing stops you from
> writing; this is how the result is used, so a change you make here is a change
> nobody asked for and nobody will review.

The last clause is the honest part. It tells the agent the rule is a convention
rather than a wall, which is true, and gives the reason rather than the
assertion.

### How it is delivered

`SystemPromptComposer` needs a gate. Add `AgentConfig.reviewOnly: Boolean = false`
and `Agent.withReviewOnly`, replacing `withReadOnly` at the same call sites.

The flag is prompt-only by construction: nothing but `SystemPromptComposer`
reads it, and no backend `*Args` sees it. Its scaladoc must say that in the first
line, so it cannot be mistaken for the thing that was just removed. It carries no
`Enforcement` entry and appears in no enforcement table — a flag that only
changes text has nothing to enforce.

Composition rule in `composeAll` (`SystemPromptComposer.scala:71-83`):

- `reviewOnly` → append `ReportDontChange`.
- otherwise, and not `selfManagedGit` → append `RuntimeOwnsGit` as today.

The two are mutually exclusive, and that matters: `RuntimeOwnsGit` says "make
your edits and leave them uncommitted in the working tree", which directly
contradicts "do not change files". A review turn must get one or the other,
never both.

This also removes `SystemPromptComposer`'s only dependency on `ToolSet`
(`:78-80`) — the `tools == ToolSet.Full` gate becomes `!reviewOnly`.

### Which turns set it

Set `withReviewOnly` at all six former `withReadOnly` sites (§1.2), plus the two
planner entry points, which today get no standing rule at all because they run
`NetworkOnly` and the git rule is `Full`-gated:

| Turn | Site | Today |
|---|---|---|
| reviewers | `Reviewers.scala:147` | no standing rule |
| reviewer picker | `ReviewerSelector.scala:149` | no standing rule |
| lint summariser | `Lint.scala:144` | no standing rule |
| plan self-review | `Plan.scala:223` | prompt says it (`plan/prompts/review.md`) |
| stack discovery | `StackDiscovery.scala:91` | no standing rule |
| `cheapOneShot` | `Agent.scala:163` | no standing rule |
| autonomous planners | `Plan.scala:179` | prompts say it (`triage.md`, `planning.md`) |

`cheapOneShot` is the one worth arguing about: adding ~80 words of standing rule
to a prompt that asks for a branch name is disproportionate. Set it anyway. That
turn already carries `BackgroundWorkAbandonedAtTurnEnd`, so the marginal cost is
small, and on codex it is the turn that loses the most sandbox (§1.2).

### Prompt text that becomes false

`flow/src/main/resources/orca/review/prompts/select-reviewers.md:6-9` currently
tells the picker "You have read-only file access" and "You may have no shell, so
don't depend on running commands". Both stop being true. Rewrite to say the
picker should open the changed files, and drop the shell hedging — after the
removal every backend gives it a shell. Keep the `git diff HEAD` warning: that
is about the diff being wrong, not about the shell being absent.

`flow/src/main/resources/orca/review/prompts/initial-review.md` and
`re-review.md` need nothing added — the standing rule covers them, and putting
the same paragraph in eight reviewer `.md` files would be worse.

---

## 6. Order of work

Four steps. Each compiles and ships on its own.

**Step 1 — tell the truth, change no behaviour.** Correct the AGENTS.md table
and `EnforcementTableTest` for the claude and gemini `NetworkOnly` cells (§3,
§4), leaving the `ReadOnly` rows in place but corrected the same way. Pure
documentation and test change. Mergeable alone, and it stops the false claim
immediately even if the rest stalls.

**Step 2 — add the guidance, keep the gate.** Add `report-dont-change.md`,
`AgentConfig.reviewOnly`, `Agent.withReviewOnly`, and the `SystemPromptComposer`
composition rule (§5). Set `withReviewOnly` *alongside* `withReadOnly` at every
site. Behaviour change: those turns gain a paragraph. No capability change.
Doing this before step 3 means there is never a window where a reviewer has
neither the gate nor the guidance.

**Step 3 — remove.** Delete `Agent.withReadOnly`, `BaseAgent.withReadOnly`,
`ToolSet.ReadOnly` and the fourteen backend arms; drop the `ReadOnly` rows from
`EnforcementTableTest`; retarget the tests in §1.4. The compiler finds every arm
— all five backends match exhaustively on `ToolSet`, which is why `ClaudeArgs`
notes at `:148-149` that the match is written out "so a future `ToolSet` case
fails compilation here". Removing a case works the same way in reverse.

**Step 4 — documentation sweep.** README, ADR 0016 supersession note, ADR 0019
wording, `select-reviewers.md`, and the scaladoc mentions in §1.5.

---

## 7. What could break

Ordered by size of the observable change.

**codex reviewers lose their sandbox.** `--sandbox read-only` →
`--dangerously-bypass-approvals-and-sandbox`. This is the largest single change
in the removal. Eight reviewers per round, each previously confined by an OS
sandbox, now running unconfined against the working tree the flow is about to
commit. Nothing in the loop detects a reviewer that edits. The mitigation is the
standing rule and the fact that reviewers have not historically tried.

**pi reviewers gain a shell.** `--tools read,grep,find,ls` → no `--tools` flag,
so every built-in including `bash` and the write tools. Same shape of risk.

**opencode reviewers gain a shell.** `write/edit/bash/patch: false` → no gate.
Note this one cuts both ways: `09-diff-vs-coordinates.md` records opencode
reviewers being unable to run commands as a *limitation*, so some flows get
better here.

**gemini reviewers move from `plan` to `yolo`.** Size unknown, because gemini's
plan mode was never probed for write blocking.

**claude reviewers move from `plan` to `bypassPermissions`.** On opus, close to
no change — #73 shows they already had `Bash` and used it 199 times. On haiku
and sonnet, where plan mode does hold, they gain tools they did not have. Any
flow running `reviewAgent = claude:haiku` sees a real capability increase.

**The reviewer picker's prompt becomes self-contradictory** if
`select-reviewers.md` is not updated in the same change. It currently asserts
the picker has no shell.

**`Plan.reviewed` on codex: no change at all**, for the reason in §1.2 — the
resume path already ignores the sandbox flag. Worth stating so this call site is
not counted as a regression.

**`RuntimeOwnsGit` gating flips owner.** Today read-only turns skip the git rule
because `tools != Full`; afterwards they skip it because `reviewOnly` is set. If
step 2 is skipped or the gate is wired wrong, every reviewer starts being told
"make your edits and leave them uncommitted", which is worse than saying
nothing. `SystemPromptComposerTest` should get a case pinning that a
`reviewOnly` turn gets `ReportDontChange` and *not* `RuntimeOwnsGit`.

**pi interactive turns lose the `--tools` allowlist path.** Under `Full`, pi
emits no `--tools` flag, so the ask-user extension tool is no longer named in an
allowlist. Verify against the real CLI that the extension tool is still reachable
(§1.4).

---

## 8. What is genuinely lost

The feature was not a no-op. It worked on three of five backends:

| Backend | Mechanism | Held? |
|---|---|---|
| codex | `--sandbox read-only`, an OS sandbox | Yes |
| pi | `--tools` allowlist — no write tool exists in the process | Yes |
| opencode | write tools disabled on the message body | Yes, measured by a live integration test |
| claude | `--permission-mode plan` | No — measured (#73) |
| gemini | `--approval-mode plan` | Never measured |

So the honest accounting: the guarantee was real on the three backends where the
mechanism was a tool allowlist or a sandbox, and false on the one backend where
it was a CLI-internal approval mode — which is also the default backend and the
one the fleet runs. It is being removed because a guarantee that holds on some
backends and some models is not a guarantee you can design against, and because
it was documented and tested as if it held everywhere.

Two things follow from that which should not be papered over:

1. Removing it makes reviewer turns on codex, pi and opencode strictly more
   capable than they are today. That is a real reduction in containment, not a
   cleanup of dead code.
2. The alternative — keeping it only where it holds — was considered and
   rejected by the owner, because a per-backend guarantee is exactly the kind of
   thing a flow silently comes to depend on. Recording it here so the option is
   visibly closed rather than merely absent.

The mechanism that would give the real guarantee is an OS-level filesystem
sandbox applied by orca itself, outside the agent CLI, so it does not vary by
backend or by model. That is being researched separately in
`docs/research/run-cost/10-filesystem-sandbox.md` (bubblewrap, Landlock,
`sandbox-exec`). Nothing in this plan blocks it: a sandbox applied at process
spawn needs no `ToolSet` case, and would restore the property for every backend
at once rather than three of five.

---

## 9. What this plan does not decide

- Whether `NetworkOnly` should keep its name. It reads as a no-edit claim it
  does not make. Renaming is churn on a two-case enum; left alone deliberately.
- Whether gemini's `--approval-mode plan` actually blocks writes. One probe
  would settle it and would move a cell in the enforcement table either way.
- Whether `AutoApprove.Only` should survive. ADR 0016:73 already records it as
  latent and unused by flows. Unrelated to this removal, but the next person
  looking at `AgentConfig` will ask.
