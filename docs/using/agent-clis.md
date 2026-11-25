# Agent CLIs

Orca drives the coding-agent CLIs you already have, which it calls
[harnesses](../glossary/users.md#agents-and-conversations): `claude`, `codex`,
`opencode`, `pi`, `gemini` and `cursor-agent`. Each harness manages its own authentication,
and Orca stores no secrets. Before you run a flow, log in to the harness you
use, following its own instructions, and to `gh` if the flow opens PRs or reads
issues.

## Run in a sandbox

```{warning}
Run Orca in a sandbox. Flows run unattended by default: the coding agent has
the full tool set (`ToolSet.Full`) and every tool call is auto-approved
(`AutoApprove.All`), so it edits files and runs shell commands without asking.
```

You can narrow an agent's tools or auto-approval in the flow itself, see
[Choosing agents](../authoring/choosing-agents.md). For an unattended run,
however, the practical boundary is a VPS or a local sandbox such as
[Sandcat](https://github.com/VirtusLab/sandcat) or
[Docker Sandboxes](https://docs.docker.com/ai/sandboxes/).

## Your instruction files apply

Orca's agents are ordinary harness sessions started in your repository, so
they load the same instruction files (`~/.claude/CLAUDE.md`, `CLAUDE.md`,
`CLAUDE.local.md`, `AGENTS.md`, `GEMINI.md`, …), MCP servers, plugins and
hooks as your own sessions do.

The difference is that no one is present to approve tool calls. This has a few
consequences:

- Coding turns auto-approve every tool by default.
- On claude, the read-only roles (the planner, the reviewers, and the agent
  that picks reviewers) can use only the tools Orca allows. In particular,
  your MCP tools are blocked unless your claude settings `permissions.allow`
  them.
- On claude, opencode and pi, cheap one-shots such as branch names and default
  commit messages run with no tools and no MCP servers.

Because of this, it is worth checking your instruction files for two things:

- **Mandatory tool calls.** An instruction like "always call X first" only
  works if X is allowed; otherwise, write "if available".
- **A human in the loop.** In an autonomous flow, "ask me before X" or "wait
  for confirmation" cannot work, as there is nobody to answer.

### Allowing MCP tools for claude's read-only roles

The read-only roles see your MCP servers, but a call is denied unless a
`permissions.allow` rule names it. The run's summary lists such calls as
`denied tool calls: mcp__<server>__<tool>`. Allow a whole server or single
tools in `~/.claude/settings.json` (all your repositories) or a committed
`.claude/settings.json` (this repository):

```json
{
  "permissions": {
    "allow": ["mcp__docs", "mcp__tracker__get_issue"]
  }
}
```

Allow only read-only tools: anything allowed here runs unprompted in every
claude session, not only in Orca's. If you previously answered "don't ask
again" in a session, that rule went to `.claude/settings.local.json`, which
is not committed.

## OpenCode with a local Ollama model

There are two ways to point OpenCode at a model served by Ollama.

### Launcher, zero config

Pass a launcher in the flow script:

```scala
flow(OrcaArgs(args), opencode = Some(w => OpencodeAgents.default(w, OpencodeLauncher.ollama("qwen3-coder"))))
```

Orca then starts the server via `ollama launch opencode`, which injects
Ollama's provider config and pins that one model. Use bare `opencode` in this
case, without `withModel`. This needs the `ollama` CLI with the model already
pulled. See [Extending flows in code](../authoring/extending.md).

### Manual config

Declare an `ollama` provider in `~/.config/opencode/opencode.json` (with
baseURL `http://localhost:11434/v1`, your models, and `num_ctx` raised for tool
use), then select a model with `opencode.withModel("ollama", "qwen3-coder")`
(see [Backends](../api/backends.md)). This way you can declare several models
and switch between them per turn.

## Cursor Agent

Orca selects Cursor with the settings name `cursor` and detects/spawns the
`cursor-agent` binary. Authenticate it interactively before running a flow.
Orca preserves your existing trust and permission settings.

An unfamiliar checkout, including a fresh `--worktree` checkout, can refuse
print-mode calls with **Workspace Trust Required**. Orca reports the exact
checkout path. Open a terminal in that directory, run `cursor-agent`
interactively, explicitly trust that checkout, exit, then rerun the same Orca
command. Trusting the parent checkout may not trust its worktrees. The shell's
flow-filename suggestion reports the same instructions and uses its local
fallback name. Orca does not grant workspace trust automatically.

Restricted turns (`ReadOnly`, `NetworkOnly`, `NoTools`) keep `--plan`, whose
limits are prompt-only. Full turns use `--force`; Cursor cannot enforce an
`AutoApprove.Only(...)` allowlist. Structured output is prompt-enforced, and
Orca's `ask_user` bridge is unavailable.

Cursor mints the session UUID. To recover across attempts, Orca uses optional
`sqlite3` on PATH to verify persisted history in Cursor's chat database:

```text
<cursor-config-dir>/chats/<checkout-path-md5>/<UUID>/store.db
```

Orca uses `CURSOR_CONFIG_DIR` when set to a nonblank value, then
`$XDG_CONFIG_HOME/cursor`, then `~/.cursor`. Relative paths resolve from the
recorded checkout. Use the same profile when retrying a flow or running
`orca continue`.

The checkout is resolved to its canonical absolute path through symlinks
(including macOS `/var` to `/private/var`). The directory key is the lowercase
MD5 of that path's UTF-8 bytes. The UUID is validated before constructing the
path, so history is specific to that checkout. JSONL transcript exports do not
establish that Cursor can restore the conversation.

Orca opens a properly encoded absolute file URI with `mode=ro&immutable=1`,
using `sqlite3 -batch -noheader -readonly -init /dev/null` and one scalar query:

```sql
SELECT EXISTS (SELECT 1 FROM meta WHERE key = '0') AND EXISTS (SELECT 1 FROM blobs);
```

Only a successful scalar `1` verifies history. The check reads no message
contents and neither invokes Cursor nor creates a store. A missing `sqlite3`,
missing or unreadable database, corrupt or unsupported schema, or initialized
store without history makes a durable flow re-seed with its existing seed.
The probe has a ten-second deadline; expiry kills the probe and finishes its
readers, treating the history as unverified. Cancelling Orca also stops the
probe and propagates cancellation.
Immutable mode ignores WAL files; history available only in an uncheckpointed
WAL safely re-seeds.
Confirmed same-attempt conversations continue without another disk probe.
Cursor can silently open a blank conversation for a nonexistent resume UUID,
so attempting a resume is not a history check.

`orca continue` requires the recorded working directory and the same database
check. When history cannot be verified, it refuses: install `sqlite3` on PATH
if missing and retry, or rerun Orca in the recorded checkout to re-seed the
conversation. Verified history hands the terminal to
`cursor-agent --resume=UUID` in that checkout, without print mode. Interactive
continuation does not re-seed.

Cursor's `inputTokens` is fresh uncached input; cache reads and writes are
separate buckets. Orca sums them for total input tokens and leaves dollar cost
unknown when Cursor supplies no price.
