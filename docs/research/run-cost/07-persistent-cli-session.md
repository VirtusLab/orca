# One CLI process per durable session — findings (T1.4)

Investigation of holding a single agent CLI process open across the turns of a
durable session, instead of today's fresh process per turn with stdin closed.

**Recommendation: NO-GO as proposed**, on two independent grounds:

1. The primary shipped flow **cannot run** on a held-open claude process —
   `--json-schema` is argv-side and a durable session alternates between
   schema'd and un-schema'd turns (§2). **[V]** for the argv encoding and the
   alternation; **[U]** for "cannot", which holds only if `--json-schema`
   also cannot be varied per message over claude's control channel. That
   sub-question is unresolved (§2), and §6.1 calls settling it a ~30-minute
   experiment that decides the ADR. Ground 1 is contingent on its answer.
2. The headline cost benefit is **not supported by measurement** (§1), so the
   change would be bought on reliability grounds alone — and its reliability
   benefit shares a mechanism with a deadlock already documented in the code (§3).

T1.4's own declared gate, T1.3, is also still open, and a persistent process
makes T1.3's failure mode the *normal* case on every turn boundary (§4).

Method: two independent researchers over the codebase, a skeptic round against
their findings, and live probes against the installed CLIs (claude 2.1.220).
Where the researchers and the skeptic disagreed, §7 records who was taken and why.

Evidence tags: **[V]** read in the code, **[M]** measured by probe, **[I]**
inferred, **[U]** unknown.

---

## 1. The cost benefit is not supported

This was the main justification (cache writes were 46% of the baseline run), so
it was measured rather than argued.

**Controlled comparison.** Two arms, three turns each, **distinct** ~79k-token
content per arm, `--resume` arm run first so it could not inherit the other's
cache: **[M]**

| arm | turn 1 `cache_creation` | turn 2 | turn 3 | **total** |
|---|---|---|---|---|
| per-turn spawn + `--resume` (orca today) | 57,426 | 239 | 125 | **57,790** |
| one held-open process | 56,646 | 430 | 12 | **57,088** |

A 1.2% difference. The cold-start write is the same in both arms, and the
marginal per-turn write is small in both. The mechanism is that `--resume`
re-sends the same prefix and the cache is *read*, not re-created.

**What this does and does not establish.** It establishes that for cache writes
driven by **conversation growth**, process lifetime is irrelevant. It does **not**
cover the regime the baseline actually ran in: the implementer averaged 213k
tokens and ~5,900 cache-write tokens per turn, and much of that growth happens
*inside* a turn as tool results land (`Conversations.scala:138-143` notes tool
output volume is unbounded). Every probe here has **zero tool use**, so it cannot
reproduce intra-turn growth. **[M]/[U]**

No mechanism has been identified by which a held-open process would write less in
that regime — tool results grow the prefix identically either way — but that is an
argument, not a measurement. So this is recorded as **"not supported", not
"refuted"**, and the experiment that would settle it is in §6.

Either way this points the same direction as the plan's existing conclusion: the
levers are **prefix size and turn count**, not process lifetime.

It does **not** close T1.5. T1.5 asks what forces 2.0M tokens of cache creation,
and names per-turn teardown as the likely cause; the regime that produced that
figure is the implementer at a 213k prefix with real tool calls, which is the
regime the paragraph above says these probes cannot reach. Ruling teardown out
there would be a claim about a regime this section has just excluded. T1.5 stays
open on the same experiment (§6.2).

---

## 2. The blocker: per-turn config is argv, and the primary flow varies it

`ClaudeArgs.streamJson` (`claude/.../ClaudeArgs.scala:28-51`) encodes **model,
permission-mode/toolset, auto-approve, `--json-schema`, `--mcp-config` and
`--append-system-prompt-file`** in argv — all therefore frozen at spawn for the
life of a held-open process. **[V]**

That would be tolerable if a durable session used one shape throughout. It does
not. In every shipped implement-style flow the coder's durable session alternates
between:

- free-form turns — `flows/implement.sc:35` → `session.run(task.description)`,
  no schema; and
- structured turns — `ReviewLoop.scala:610-611` →
  `coderSession.resultAs[FixOutcome]`, which adds `--json-schema`. **[V]**

`reviewAndFixLoop` receives that same `coderSession` (`ReviewLoop.scala:221`,
`:306`), so this is the primary code path, not a corner case.

Some of these *may* be settable over claude's control channel — the repo already
speaks that protocol (`ClaudeConversation.scala:278-285`) — but `--json-schema`,
`--mcp-config` and `--append-system-prompt-file` have no documented per-message
equivalent. **[U]** This is the single question that gates any revival (§6.1).

---

## 3. Structural cost in orca's runtime

The reader machinery generalises; the **one-shot latches** are the problem.

- **`settledOutcome` is a single-write `Option`** with a first-write-wins
  invariant (`ForkedConversation.scala:105-121`). Under N turns on one stream, a
  stray or misattributed `result` frame settles the **session**, not just the
  turn. This is where §4's T1.3 hazard bites. **[V]**
- **`runFinalize` is one-shot**, guarded by an `AtomicBoolean`
  (`ForkedConversation.scala:487-494`). Every per-turn resource released there
  becomes session-lifetime: the `--append-system-prompt-file` temp file
  (`ClaudeConversation.scala:86-91`) and the `ask_user` MCP bundle (`:494`).
  `ClaudeBackend.scala:41-43` states the MCP server's *"lifetime tracks the
  conversation … so a long flow with many interactive calls doesn't leak Netty
  bindings"* — this change redefines "the conversation" as "the whole flow". **[V]**
- **`StderrPipeline.onFinalize` joins the stderr fork unboundedly**, and its own
  comment records that the tree kill which would unblock it runs *downstream* of
  the finalize (`StderrPipeline.scala:60-71`). **[V]** Today this reaches one
  turn's descendants; under this change it reaches every descendant accumulated
  over the session — **exactly the background work the proposal exists to keep
  alive**. Benefit (a) and this deadlock are the same mechanism. The
  direct-style-scala subprocess chapter covers this hazard; it is already live in
  the repo, and this change makes it structural.
- **Every successful turn currently SIGINTs the process** — `succeedWith` and
  `failWith` end in `source.interrupt()` (`ForkedConversation.scala:295-329`) and
  `StreamSource.fromProcess.interrupt()` is `process.sendSigInt()`
  (`StreamSource.scala:68`). But `interrupt()` is a **per-source policy**
  (`StreamSource.scala:28-33`) and the codebase already contemplates a source
  that outlives a turn (`ForkedConversation.scala:290-294`, the SSE case — not
  `StreamSource.scala`, which is 76 lines), so this is a new `StreamSource`
  implementation, not a rewrite. **[V]**
- **`FlowSession` has no lexical span.** It must be minted *outside* any stage
  (`OutsideStage`, `Session.scala:28-30`, runtime throw at `:180-185`) and closed
  over into arbitrarily many later stages (`:50-54`). There is therefore no
  lexical region corresponding to "the session" in which to put the `supervised`
  block the chapter requires. The chapter's prescribed shape —
  `run(...)(use: Handle => T)` — would change the signature of
  `agent.session(name, seed)` and every user flow script. **[V]**
- **Concurrent access is not excluded by the owner-thread assert.**
  `FlowSession.run` asserts owner-thread (`Session.scala:84, 129`), but
  `Session.scala:44-48` documents the deliberate escape hatch:
  `agent.chat(session.id)` adopts the same session id from inside a fork.
  `Agent.scala:79-85` adds *"One live continuation at a time: concurrent turns
  against the same backend conversation fail."* Today that spawns a second
  `--resume` process; with an always-live process it collides on stdin. **[V]**
- **`Dispatch` cannot express "live in this process"** — it is `Fresh | Resume`
  only (`SessionSupport.scala:20-23`) — and `willContinue` (`:153-161`) means
  "a transcript exists on disk" (claude's probe is `os.exists(...)`,
  `ClaudeBackend.scala:103-110`), which is not liveness. With a live process,
  `willContinue` returns `true` for a session whose process has died, and
  `Session.effectivePrompt` (`:319-343`) then skips re-seeding into a corpse. **[V]**
- **Cost accounting would break silently.** The reported figure is preferred
  (`InboundMessage.scala:90` → `Pricing.resolve`) and `CostTracker` **sums** it
  (`flow/.../CostTracker.scala:63, 80-81`) **[V]**; on a held-open process
  claude's `total_cost_usd` is **cumulative for the session** (0.02138 → 0.02560
  → 0.02882, increments matching per-process figures) **[M]**. Left alone this
  over-counts quadratically — corrupting the measurements Epic 0 exists to
  produce.
- **`EnvCookieSweep.afterTurn`** is keyed on the per-spawn cookie
  (`Conversations.scala:194`); a per-session process makes the per-turn leak
  report name the agent's own live process. opencode already needed this
  accommodation (`StreamSource.scala:52-59`). **[V]**
- **PR #47's shipped system-prompt rule becomes false.** The composed prompt
  tells agents the harness is torn down each turn and long commands must run in
  the foreground. The rule's *text* would have to be retracted in the same
  change; the tests would not fight it. `SystemPromptComposerTest` asserts on
  `BackgroundWorkAbandonedAtTurnEnd` in **six** of its seven tests, but through
  the symbol, so what they pin is composition — which turns get the rule, and in
  what order it joins the others — not its wording. Rewording the rule breaks
  none of them; dropping it from read-only or write-capable turns breaks
  several. **[V]**
- **Ox is pinned at 1.0.5** (`project/Dependencies.scala:10`);
  `abandonOnInterruptReads` needs ≥ 1.0.6. A one-line bump, but today
  destroy-then-EOF is the only mitigation and this change removes it from the
  turn boundary. **[V]**

---

## 4. The declared gate, T1.3, is unmet — and this change makes it worse

`01-development-plan.md:133, 423` — *"Depends on T1.3"*, *"T1.3 gates T1.4."*
T1.3 is still `[TODO]`: on `--resume` the CLI emits a `result` with
`is_error: true` ~70 ms **before** orca's prompt reaches stdin, and orca settles
on it.

Under a persistent process, "which `result` belongs to which prompt" stops being
a `--resume` edge case and becomes the **normal question at every turn
boundary** — against a `settledOutcome` that can only be written once (§3).
Attribution must be solved before, not after.

---

## 5. Backend coverage and the fan-out

| Backend | Transport | Multi-turn on one process? |
|---|---|---|
| claude | `--print --input-format stream-json` | **yes** — verified **[M]** |
| pi | `--mode rpc`; orca already holds stdin open *within* a turn under a lock (`PiConversation.scala:60-74, 153-156`) | plausible; the RPC loop survived two prompts, credentials blocked confirming context carry-over **[M]/[U]** |
| codex | prompt on argv, stdin closed (`CodexBackend.scala:201-204`) | **no** — ADR 0007:51-56, `codex exec --help` **[V]** |
| gemini | prompt on argv (`GeminiBackend.scala:161-169`); `-o stream-json` is output-only | **no** on the path orca uses; `--acp` exists, unused **[V]** |
| opencode | HTTP + SSE against a per-run `opencode serve` | **already has the property** **[V]** |

Claude probe detail: two user messages 25 s apart on one held-open stdin were
both answered, sharing one `session_id`, with context carried ("BANANA" recalled
on turns 2 and 3), the first `result` arriving 4.79 s in **with stdin still
open**. **[M]**

**The fan-out cannot share a process, and need not.** Two messages 0.2 s apart on
one stdin are **serialised** by the CLI, not interleaved. **[M]** But reviewer
conversations are ephemeral by construction — `agent.chat()` mints a **fresh**
`SessionId` (`Agent.scala:77`), used by the unbounded
`CheckedPar.mapParUnordered(tasks.size)` fan-out over 8 reviewers plus lint
(`ReviewLoop.scala:429, 510`; `Reviewers.scala:78-87`) — so they were never
candidates. Scoping to `FlowSession` and excluding `Chat` is therefore a clean
boundary.

---

## 6. What remains unknown, and the experiment for each

1. **Can `--json-schema` / model / permission-mode be varied per message over the
   control channel?** Gates everything (§2). *Experiment:* drive one held-open
   `claude --print --input-format stream-json` and attempt each override. ~30
   minutes, and it decides the ADR. If `--json-schema` cannot change, this is
   dead for claude as proposed.
2. **Does the cache result hold at 213k with tool use?** (§1.) *Experiment:*
   distinct freshly-generated content per arm, randomised interleaved arm order,
   ≥3 replicates, ≥200k prefix, **real tool calls producing multi-KB results**,
   plus a >1h-gap arm; report `usage` with `Option` presence distinguished from
   zero. Success criterion: does the per-turn difference approach the baseline's
   ~5,900 tokens/turn? Better still, skip the synthetic probe — T0.2's per-turn
   prompt-size record over a T0.3 benchmark flow answers this on real traffic.
3. **Does claude's stream-json input have a per-turn abort that leaves the
   process alive?** Nothing bounds a turn today — no timeout anywhere in
   `agents/` or `backend/` **[V]**, and `OpencodeBackend.scala:51-54` says so
   outright. Today the backstop is process exit: EOF ends the reader and
   `outcomeFromExit` produces an outcome. A persistent process never EOFs.
   *Experiment:* start a long tool call, send a candidate control frame, observe
   whether the turn aborts and the process survives.
4. **Does the session-teardown deadlock in §3 actually fire?** *Experiment:* hold
   a claude process open, have it spawn a non-detached background child holding
   the stderr write-end, tear the session down, and see whether
   `StderrPipeline.onFinalize`'s unbounded join hangs.
5. **Can a second message be written while a turn is in flight?** All probes
   waited for `result`. *Experiment:* write message 2 ~1 s after message 1.
6. **Does pi accept a second `prompt` after `agent_end`, with context carried?**
   *Experiment:* re-run the two-prompt RPC probe with a pi provider key.
7. **Do 9 persistent processes with growing contexts change the memory /
   rate-limit profile?** *Experiment:* measure RSS and rate-limit headers across a
   full `reviewAndFixLoop` round.

---

## 7. Where the researchers and the skeptic disagreed

- **"The cache benefit is refuted" vs "unpowered null."** Skeptic taken,
  partially. The first probes (~30k, writes of 18–430) were indeed too small to
  detect a ~5,900/turn effect. The controlled 79k run answers that objection for
  growth-driven writes, but the skeptic's deeper point — zero tool use, so no
  intra-turn growth — stands. Recorded as **not supported** rather than refuted,
  with the experiment named. The recommendation does not depend on it: §2 is an
  independent blocker.
- **"`runReader` needs a rewrite" vs "a new `StreamSource` suffices."** Skeptic
  taken. `interrupt()` is per-source policy and the class already documents an
  SSE source outliving a turn (`ForkedConversation.scala:290-294`). The genuine
  structural items are `settledOutcome`'s single write and `runFinalize`'s
  one-shot guard — which the research missed and the skeptic found.
- **"Background work already survives, so benefit (a) is hollow."** Skeptic
  taken. Detached work (`nohup`/`setsid`) escapes today — that is why PR #62
  exists — but the default an agent writes, `cmd &`, is a non-detached descendant
  that `destroyForciblyTree()` does kill (`StreamSource.scala:74`). Benefit (a)
  is real. The correct objection is §3's: the mechanism that lets that work
  survive is the same one that hangs the stderr join at session end.
- **"Ctrl-C would orphan a long-lived agent."** Skeptic taken; the research
  overstated it. `ChildTerminal.scala:22-41` documents that there is **no
  `setsid` anywhere in the launch chain**, so the agent CLI shares the shell's
  foreground process group and Ctrl-C reaches it directly. The orphan window is
  SIGKILL/SIGTERM only.
- **Cross-process cache sharing.** Neither side settled it. An uncontrolled run
  showed a fresh `--resume` process paying `cache_creation = 0` for a 128k prefix
  a prior process had just written, but the skeptic correctly noted the arithmetic
  is equally consistent with other explanations. Dropped — the controlled run in
  §1 does not rely on it.

---

## 8. If it is ever revisited

Only under all of these:

1. **§6.1 resolves favourably.** Otherwise stop.
2. **T1.3 is answered first** — turn attribution is a prerequisite, not a
   follow-up (§4).
3. **Justified on reliability, never on cost**, unless §6.2 overturns §1.
4. **Scoped to `FlowSession` on claude only**, leaving `Chat`, the reviewer
   fan-out, codex and gemini on process-per-turn; opencode needs nothing.
5. **`--resume` retained as the recovery path**, with `resumeWireId` still
   persisted every turn — a live process cannot survive an orca restart, so this
   change converts `--resume` from the steady-state path to the cold-start path
   rather than removing it.
6. **These land first, each independently useful:** a per-turn timeout; a
   `CostTracker` that does not sum cumulative cost fields; Ox ≥ 1.0.6; and a fix
   for `StderrPipeline.onFinalize`'s unbounded join.
7. **Modelled on `OpencodeServer`**, which already solves this shape correctly:
   lazily-spawned, run-scoped, idempotent `shutdown()`, drains in the flow scope,
   teardown in the flow body's `finally` before the join
   (`OpencodeServer.scala:14-27, 63-80`; `flow.scala:326-337`).

### ADR-worthy decisions, if it proceeds

- **Process lifetime** — per `FlowSession`, spawned lazily on first turn, closed
  at flow teardown via the `ctx.close()` seam.
- **Cancellation** — a per-turn abort distinct from session teardown; today
  `succeedWith`'s `source.interrupt()` conflates them.
- **Crash recovery** — unchanged: `--resume` from the persisted `resumeWireId`.
  A live process is an optimisation over that path, never a replacement.
- **A turn that never ends** — an explicit per-turn deadline, and a defined answer
  for whether a timed-out turn kills the session or only the turn.

---

## 9. Corrections to make regardless of this proposal's fate

- **Stale claim, twice.** `ClaudeBackend.scala:154-157` and the
  `PipedCliProcess` trait contract at `CliProcess.scala:34-38` both state that
  claude needs stdin EOF before it produces output. Measured false at 2.1.220
  (first `result` at 4.79 s, stdin open) **[M]**, and already contradicted in
  passing by ADR 0007:51-56. The SPI docs are the more important of the two.
- **`ProgressLog.scala:75-76`** says pi's sessions live in a `deleteOnExit` temp
  dir so a resumed run always re-seeds; `PiBackend.scala:67-68` and ADR 0018's
  2026-07-28 amendment say pi is durable. **[V]**
- **Latent write-after-close.** `ClaudeBackend.scala:221` closes stdin
  immediately after writing the opening turn; `ClaudeConversation.respond`
  (`:278-285`) later writes a `control_response` to it, and
  `OsProcCliRunner.scala:159-163` writes to the closed stream. The defect is
  real, but **the trigger first named for it was wrong**. Which path reaches it:
  - Not the `Deny` branch. `Conversations.scala:105-116` responds `Deny` to
    `ApproveTool`, but that event is only enqueued when
    `ClaudeConversation.autoApproves(name)` is false (`:243-252`), which under
    the shipped `AutoApprove.All` (`AgentConfig.scala:21`) it never is. No orca
    call site sets `AutoApprove.Only`, so the branch is dead in shipped
    configuration. **[V]**
  - The live path is the `Allow()` reply at `:244`, which writes to the same
    closed stdin.
  - Either way, nothing gets that far: stdin is closed before any
    `control_request` could arrive, so orca cannot deliver *any* decision. No
    `control_request` frame appears in any of the baseline run's ten reviewer
    transcripts. **[M]**

  So the write-after-close is unreachable in practice rather than latent, and
  the code that would exercise it cannot work at all.
  Unit tests cannot catch it: the fake is lenient about writes after close
  (`FakePipedCliProcessTest.scala:65-69`). Not verified live. **[V]/[I]**
- **`StderrPipeline.onFinalize`'s unbounded join** (`:60-71`) is a real
  mis-sequencing today for codex/gemini/pi, independent of this proposal.
