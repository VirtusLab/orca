# What NetworkOnly grants is answered in five files with no rendered summary

**Aspect**: complexity  **Severity**: low

## Problem

The enforcement table answers "how strongly is the tier's gate held" but not
"what did the tier grant". For `NetworkOnly` the grant differs per backend and
lives in five files:

- claude: `WebFetch`/`WebSearch` (`ClaudeBackend.DefaultNetworkTools`,
  `claude/.../ClaudeBackend.scala:390-391`) plus the host-served GitHub issue
  read (`GitHubMcpServer`, gated on `hasScopedNetwork`);
- codex: `-c sandbox_workspace_write.network_access=true`
  (`codex/.../CodexArgs.scala:172-176`);
- gemini: `--allowed-tools web_fetch` (`gemini/.../GeminiArgs.scala:67`);
- pi: `bash` (`pi/.../PiArgs.scala:29` — which is what costs the tier its
  guarantee);
- opencode: nothing — `NetworkOnly` currently behaves as `ReadOnly`
  (`opencode/.../OpencodeArgs.scala:83-87`; see finding 03).

The compensation side is similarly asymmetric and undocumented centrally:
claude's shell-less tiers get `RepoMcpServer` git reads back; pi's shell-less
tier (`read,grep,find,ls`) gets no equivalent. A reader comparing planners
across backends visits five files.

The mechanisms are genuinely per-backend — a cross-backend grant abstraction
would encode five unrelated mechanisms into one type for no behavioral gain.
This is a documentation-shaped gap, so the fix is a rendered summary, not an
abstraction.

## Proposed solution

Add a short hand-written list to AGENTS.md, directly after the enforcement
block's per-cell-rationale paragraph, stating what `NetworkOnly` grants per
backend in one line each (claude: `WebFetch`/`WebSearch` plus the host-served
GitHub issue read; codex: `network_access=true` inside the workspace-write
sandbox; gemini: pre-approved `web_fetch`; pi: `bash`, which is what costs
the tier its guarantee; opencode: nothing — see finding 03), plus one
sentence on the compensation asymmetry (claude's shell-less tiers get
host-served repo reads back; pi's get nothing). Mark it as hand-maintained,
outside the rendered block — `EnforcementTableTest` neither renders nor
checks it, and drift is accepted for a list this small.

Do NOT add a `networkGrant` member to `AgentBackend`, a per-`*Args` constant,
render machinery, or a cross-backend grant ADT, and do not move the grant
decisions out of the backends. If finding 03 lands first, write opencode's
line to match its post-fix behavior (`webfetch` on `NetworkOnly` only).

## Verification

**Verdict: CONFIRMED-REVISED.**

Checked every per-backend grant claim: claude `DefaultNetworkTools` (ClaudeBackend.scala:390-391) + GitHub server gated on `hasScopedNetwork` (:234); codex `-c sandbox_workspace_write.network_access=true` (:172-176); gemini `web_fetch` (GeminiArgs.scala:67); pi `bash` (PiArgs.scala:29); opencode nothing (:81-97, matches finding 03); pi's shell-less tier gets no repo-read compensation (verified — pi wires only the ask-user extension). All accurate; the problem (five files, no summary) is real.

Revision rationale: the original primary solution was net-negative — a new abstract `AgentBackend` SPI member whose only consumer is a doc renderer forces a stub into every backend test double (the very boilerplate finding 09 deletes), plus render machinery, for one rarely-changing five-line list. The original itself flagged this and supplied the right fallback; per the abstraction-must-pay-for-itself principle, the fallback IS the solution. The ## Proposed solution above is the revised version.
