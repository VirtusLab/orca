# Claude's per-turn MCP grant assembly threads each host server through six sites

**Aspect**: complexity  **Severity**: medium

## Problem

In `ClaudeBackend.openConversation`
(`claude/src/main/scala/orca/tools/claude/ClaudeBackend.scala:229-276`), each
host-served MCP server is threaded through parallel `Option` chains that must
stay in step. For `repoReads` alone:

1. the gated start (232: `Option.when(ClaudeArgs.losesShell(config.tools))`),
2. the `mcpConfig` presence check (236-238),
3. the `perTurn` resource list (249-252),
4. the `mcpTools` approval names (273-275, via the companion's
   `RepoToolNames`),
5. `writeMcpConfig`'s dedicated `Option` parameter (303-318),
6. `writeSystemPrompt`'s dedicated `includeRepoHint` boolean (239-244,
   341-350).

Same for `githubReads`; the name/slug/timeout/hint constants are split between
`RepoMcpServer`/`GitHubMcpServer` and the `ClaudeBackend` companion
(`RepoToolNames` 409-410, `GitHubToolNames` 415-418, `qualifiedToolName`
421-422). This is where "what can a restricted claude turn actually do" is
decided; adding a third read-server means touching ~6 sites, each an easy miss
with a quiet failure mode (missed hint = invisible tool; missed approval =
every call denied; missed resource = leak).

## Proposed solution

Bundle the per-server facts once and derive everything by mapping one list.
In the claude module (`askUser` stays special — it owns a bridge lifecycle and
is handed to `ForkedConversation` separately):

```scala
/** One host-served MCP server this turn stands up: its registration facts
  * and the live host. Everything a turn derives from a server — config
  * entry, approvals, hint, teardown — maps over one list of these. */
private final case class TurnMcp(
    name: String,
    slugs: Seq[String],
    timeout: FiniteDuration,
    hint: String,
    host: McpHost
)

private def turnServers(tools: ToolSet)(using Ox): List[TurnMcp] =
  Option.when(!tools.writeCapable)(
    TurnMcp(RepoMcpServer.ServerName, RepoMcpServer.ToolSlugs,
      RepoMcpServer.ToolTimeout, RepoMcpServer.Hint, RepoMcpServer.start(git))
  ).toList ++
    Option.when(tools.hasScopedNetwork)(
      TurnMcp(GitHubMcpServer.ServerName, GitHubMcpServer.ToolSlugs,
        GitHubMcpServer.ToolTimeout, GitHubMcpServer.Hint,
        GitHubMcpServer.start(new OsGitHubTool(cli, workDir)))
    )
```

Then:

- `writeMcpConfig` takes `askUser: Option[McpHost]` +
  `servers: List[TurnMcp]` and maps `entryJson` over them;
- approvals become
  `servers.flatMap(s => s.slugs.map(qualifiedToolName(s.name, _)))` — the
  companion's `RepoToolNames`/`GitHubToolNames` constants delete;
- `writeSystemPrompt`'s three booleans (341-346) become
  `hints: List[String]` (`servers.map(_.hint)`, plus the ask-user hint);
- `perTurn` resources become `servers.map(_.host)` ++ the file resources.

`ClaudeArgs.losesShell` (`ClaudeArgs.scala:146-151`) is a private one-call-site
forwarder for `!tools.writeCapable` with a six-line doc; inline it at the
`turnServers` gate with a one-line comment ("the read-only tiers drop `Bash`
with the writes, so the host serves the repo reads back").

Optional polish, only if it stays net-negative in lines: lift
`name/slugs/timeout/hint` into a small trait implemented by the `*McpServer`
objects so `TurnMcp` is `(definition, host)`. Not required — the local case
class already pays for itself.

Tests: existing `ClaudeBackendTest`/`DefaultAgentCallTest` assertions on
mcp-config contents, approvals and hints must pass unchanged. Must NOT change:
which tiers get which server (`!writeCapable` → repo reads,
`hasScopedNetwork` → GitHub reads), the resource-release ordering contract
(the `open`-vs-`onFinalize` split documented at 245-248), or ask_user's
separate lifecycle.

## Verification

**Verdict: CONFIRMED-REVISED** (approach unchanged; one internal contradiction fixed).

Checked all six threading sites in `ClaudeBackend.openConversation` (232, 236-238, 249-252, 273-275, `writeMcpConfig` 303-327, `writeSystemPrompt` booleans 239-244/341-350) — exact; the split constants (`RepoToolNames` 409-410, `GitHubToolNames` 415-418, `qualifiedToolName` 421-422); `ClaudeArgs.losesShell` 146-151 (one call site). `RepoMcpServer`/`GitHubMcpServer` expose `ServerName`/`ToolSlugs`/`ToolTimeout`/`Hint` and `start(...)(using Ox)` — the proposed `TurnMcp` shape is directly buildable; the tier gates (`!writeCapable`/`hasScopedNetwork`) match the current code exactly; ask_user's separate lifecycle and the open-vs-onFinalize release ordering are correctly preserved.

Solution revision — the original contradicts itself on one point: `ClaudeBackendTest.scala:114` and `:174` consume `ClaudeBackend.RepoToolNames`/`GitHubToolNames` to compute expected `--allowedTools` values, so "the companion's constants delete" conflicts with "existing ClaudeBackendTest assertions must pass unchanged". Either keep those two `private[claude]` vals (derived via the same qualified-name mapping the production list uses), or update the two test sites to derive the expected names from `RepoMcpServer.ToolSlugs`/`GitHubMcpServer.ToolSlugs` inline. Do not leave the tests referencing deleted symbols.

Ordering: sequence after 01 (01's claude wiring changes `permissionArgs`' signature; 07 then reshapes the call site that feeds it grants) — or merge the claude portions of 01 and 07 into one PR. Implement before 06 (06 renames the run methods that call `openConversation`).
