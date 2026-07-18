> Final (codebase-factual topic; no adversarial pass).

# 06 — Welcome wizard: harness detection & settings writing

Codebase sweep for topic 6 of the Orca Shell research plan
(`00-research-plan.md`). All findings from the repo as of `master`
(ce171979); file:line citations throughout.

## 1. The authoritative harness enum

`BackendTag` — `/home/adamw/orca/tools/src/main/scala/orca/agents/BackendTag.scala:15-20`:

```scala
enum BackendTag(val wireName: String):
  case ClaudeCode extends BackendTag("ClaudeCode")
  case Codex extends BackendTag("Codex")
  case Opencode extends BackendTag("Opencode")
  case Pi extends BackendTag("Pi")
  case Gemini extends BackendTag("Gemini")
```

Declaration order: **ClaudeCode, Codex, Opencode, Pi, Gemini** — this is the
order the wizard should present.

The settings-file spelling is a separate, deliberately lowercase namespace:
`AgentSpec.harnessNames` —
`/home/adamw/orca/flow/src/main/scala/orca/settings/AgentSpec.scala:16-22` —
maps `claude / codex / opencode / pi / gemini` to the tags, in the same order
as the enum. `AgentSpec.harnessNameFor` (AgentSpec.scala:28) is the reverse
map (tag → settings name), used by the role-announcement `Step`; the wizard
can reuse both.

Order consistency elsewhere:

- `WiredAgents` constructor fields — claude, codex, opencode, pi, gemini
  (`/home/adamw/orca/runner/src/main/scala/orca/runner/WiredAgents.scala:27-33`),
  and its `byTag` map iterates `BackendTag.values` (WiredAgents.scala:40-47).
- One deviation to be aware of: the parse-error message sorts harness names
  **alphabetically** (`AgentSpec.scala:43-44`); that's an error-message
  choice, not the presentation order. The wizard should iterate
  `BackendTag.values` (or the insertion order of `harnessNames`).

Note on visibility: `AgentSpec`, `AgentSettings`, `SettingsFile`,
`GlobalSettings` are all `private[orca]` / `private[settings]`. `BackendTag`
is public. If the shell module lives outside the `orca` package tree, these
need widening (or the shell lives under `orca.shell`, which suffices for
`private[orca]` but not for `private[settings]` members like `SettingsError`
and `AgentKey`).

## 2. Binary detection per harness

What each backend actually spawns:

| harness | binary | spawn shape | citation |
|---|---|---|---|
| claude | `claude` | `claude --print --input-format stream-json --output-format stream-json --verbose --include-partial-messages …` | `/home/adamw/orca/claude/src/main/scala/orca/tools/claude/ClaudeArgs.scala:36-45` |
| codex | `codex` | `codex … exec --json …` / `codex … exec resume <id> …` | `/home/adamw/orca/codex/src/main/scala/orca/tools/codex/CodexArgs.scala:34-38, 69-73` |
| opencode | `opencode` | `opencode serve --port 0 --log-level WARN` (long-lived server, ADR 0014) | `/home/adamw/orca/opencode/src/main/scala/orca/tools/opencode/OpencodeLauncher.scala:18`, `OpencodeArgs.scala:29-34`, spawn at `OpencodeServer.scala:92-97` |
| pi | `pi` | `pi --mode rpc --session-dir <dir> …` | `/home/adamw/orca/pi/src/main/scala/orca/tools/pi/PiArgs.scala:32` |
| gemini | `gemini` | `gemini -p` (fresh) / `gemini --resume <id> -p`; also `gemini --list-sessions` via `cli.run` | `/home/adamw/orca/gemini/src/main/scala/orca/tools/gemini/GeminiBackend.scala:131, 153, 166, 182` |

Findings:

- **Every binary name equals the harness/settings name.** No backend has a
  divergent executable name.
- **Opencode caveat**: the spawned argv is `launcher.prefix ++ Seq("serve",
  …)`; the default launcher is plain `opencode`
  (`OpencodeLauncher.scala:18`), but a flow can substitute a wrapper
  (`ollama launch opencode --model <m> --`, `OpencodeLauncher.scala:30-31`).
  The launcher is a programmatic override only — settings values can't select
  it — so the wizard correctly probes plain `opencode`.
- **No `--version` invocation exists anywhere in the codebase** (grep for
  `--version` over all `*/src/main`: zero hits). The closest existing probe is
  gemini's `cli.run(Seq("gemini", "--list-sessions"))`
  (`GeminiBackend.scala:182`).
- **No Windows/.cmd handling exists.** Processes are spawned via
  `os.proc(args).spawn` with the bare binary name
  (`/home/adamw/orca/tools/src/main/scala/orca/subprocess/OsProcCliRunner.scala:38-49`);
  no `os.name` checks, no `.cmd`/`.exe` suffixing anywhere. ADR 0019/0020
  both list Windows support beyond the `bash -c` contract as a non-goal
  (`adr/0019-project-stack-settings.md:389-392`,
  `adr/0020-configurable-role-agents.md:231-233`). The wizard should not
  invent OS-specific probing the rest of the codebase doesn't have.

### Existing PATH-probe precedent

Stack discovery already solves "is this word runnable" —
`StackDiscovery.unresolvedReason`
(`/home/adamw/orca/runner/src/main/scala/orca/runner/StackDiscovery.scala:167-187`):

```scala
os.proc("bash", "-c", """command -v -- "$1"""", "bash", word)
  .call(cwd = workDir, check = false, stdout = os.Pipe, stderr = os.Pipe)
```

The word travels as an *argument* (never interpolated into the script), and
`bash -c 'command -v'` resolves in exactly the environment stage-time
`bash -c` inherits.

### Proposed detection per harness

For each of the five names, in `BackendTag` order:

1. **Presence**: `bash -c 'command -v -- "$1"' bash <name>` — exit 0 =
   installed. Reuses the `StackDiscovery` shape verbatim (worth extracting the
   probe into a small shared helper rather than duplicating).
2. **Optional version display**: `<name> --version` via
   `CliRunner.run` with a short timeout, purely to show the version string
   next to the detected entry (e.g. `claude ✓ 2.1.0`). Recommendation: run it
   only for binaries that passed step 1, tolerate non-zero/garbage output
   (show "found" without a version), and don't let a hung CLI block the
   wizard — a hard timeout of a few seconds. If simplicity wins, skip
   `--version` entirely; `command -v` is sufficient signal for pre-selection,
   and the harness will fail loudly at first use anyway (consistent with the
   trusted-but-fallible stance and with discovery's deliberately narrow
   "resolves is not is-right" checks, ADR 0019).

Detection should mark undetected harnesses as still selectable (the user may
install later or the probe may be wrong), just not pre-selected.

## 3. Model choice: harness-only, no model step

Settings grammar (ADR 0020 §1, `adr/0020-configurable-role-agents.md:54-63`):
`harness[:model]`, split at the FIRST `:`; the model string is passed
**verbatim** to the backend's `withModel` — orca does not normalise or
validate model ids (`AgentSpec.scala:5-9, 34-38`).

What a bare harness (no model pin) resolves to, per
`RoleAgents.one` (`/home/adamw/orca/runner/src/main/scala/orca/runner/RoleAgents.scala:198-212`)
and the backend defaults:

| harness | bare default | named tiers in code | citation |
|---|---|---|---|
| claude | Opus 1M (`claude-opus-4-8[1m]`) | `haiku`=claude-haiku-4-5, `sonnet`=claude-sonnet-5, `opus`, `fable`=claude-fable-5 | `/home/adamw/orca/claude/src/main/scala/orca/tools/claude/ClaudeAgents.scala:18-25`, `DefaultClaudeAgent.scala:30-32, 73, 79` |
| codex | installed CLI's own default (no pin) | `mini`=gpt-5.4-mini | `/home/adamw/orca/codex/src/main/scala/orca/tools/codex/CodexAgents.scala:13-14`, `DefaultCodexAgent.scala:35` |
| opencode | server's configured default (model field omitted) | `anthropicOpus/Sonnet/Haiku`, `openaiSol/Terra/Luna` (provider-qualified `provider/model`) | `/home/adamw/orca/opencode/src/main/scala/orca/tools/opencode/OpencodeArgs.scala:36-39`, `DefaultOpencodeAgent.scala:35-41` |
| pi | pi's own CLI config selects | none (only `withModel`) | `/home/adamw/orca/tools/src/main/scala/orca/agents/Agent.scala` (PiAgent trait), `PiAgents.scala:13-14` |
| gemini | Gemini Pro (`gemini-2.5-pro`) | `flash`=gemini-2.5-flash | `/home/adamw/orca/gemini/src/main/scala/orca/tools/gemini/DefaultGeminiAgent.scala:32, 54` |

**Recommendation: the wizard offers harness only.** Rationale:

- Every bare harness already resolves to a sensible strong-model default;
  the cheap tier is automatic per role (`Agent.cheap`, `Agent.scala:117-127`).
- Orca deliberately doesn't validate model ids, so a wizard-curated model
  list would be a new maintenance surface (exactly the drift problem ADR 0019
  cites for presets), and a free-text model prompt adds a step with no
  guardrails for the 90% case.
- The settings file is hand-editable by design; the wizard can print one line
  after writing: "to pin a model, edit `<path>` — values are
  `harness[:model]`, e.g. `claude:sonnet`".

If model selection is ever added, it should be an "advanced" free-text field,
not a curated list.

## 4. Settings file writing

### What exists today

- **Read**: `SettingsFile.parse(content, scope)` —
  `/home/adamw/orca/flow/src/main/scala/orca/settings/SettingsFile.scala:94-105`.
  Strict line format: `#` comments (first non-space char), blank lines,
  `key = value` split at the FIRST `=` with the value verbatim-trimmed
  (SettingsFile.scala:80-87, 232-235). Deliberately NOT
  `java.util.Properties` (escape handling would mangle shell commands, ADR
  0019). Errors are hard parse failures naming the line: no `=`, unknown key,
  value starting with `#`, duplicate agent key, invalid agent spec, stack key
  in global scope (`SettingsError`, SettingsFile.scala:9-38).
- **Keys** (`SettingKey`, SettingsFile.scala:46-63): stack keys `format`,
  `lint`, `test` (`StackKey`, :50-53 — project-only, repeatable/appending)
  and agent keys `planningAgent`, `codingAgent`, `reviewAgent` (`AgentKey`,
  :56-59 — both scopes, single-valued). **The global file accepts ONLY the
  three agent keys** — a stack key there is
  `SettingsError.NotAllowedInGlobal` (SettingsFile.scala:131-132), and any
  other key is `UnknownKey`. So a legal global file can contain nothing but
  comments, blank lines, and the three agent lines.
- **Write**: exists but is discovery-shaped. `SettingsFile.render(entries)` /
  `renderAppend(entries)` (SettingsFile.scala:177-187) take
  `List[SettingsEntry]` (`Command`/`Unset`/`Demoted`,
  `/home/adamw/orca/flow/src/main/scala/orca/settings/SettingsEntry.scala:6-32`)
  and `render` always prepends the stack-discovery `Header`
  (SettingsFile.scala:167-170: "orca settings — edit freely, commit with the
  project. / Delete the stack lines … to re-run auto-discovery") — wrong
  wording for a user-global file. The only write call site today is the
  stack-discovery write of the **project** file:
  `/home/adamw/orca/runner/src/main/scala/orca/runner/FlowLifecycle.scala:279-294`
  (`os.write.over(OrcaDir.settingsPath(workDir), …, createFolders = true)`).
- **Nothing writes the global file today.** `readSettings`
  (FlowLifecycle.scala:421-467) only reads it if it exists
  (:445-452, absent → `AgentSettings.empty`); `runFlow` takes
  `globalSettingsPath: os.Path = GlobalSettings.default`
  (`/home/adamw/orca/runner/src/main/scala/orca/flow.scala:222`). The path is
  `$XDG_CONFIG_HOME/orca/settings.properties`, default
  `~/.config/orca/settings.properties`
  (`/home/adamw/orca/flow/src/main/scala/orca/settings/GlobalSettings.scala:10-19`).

### Proposed write approach for the wizard

The wizard should write **only the global file** — the project file is
discovery's territory (stack keys plus its append-below-agent-lines protocol,
ADR 0020 §7) and per-project agent pins are a hand-edit, not a first-run
concern.

Because the global file's legal content is so constrained (three agent keys +
comments + blanks — anything else already fails every run's `parseOrAbort`,
FlowLifecycle.scala:446-451), "preserving unknown keys" is a non-problem:
there are no legal unknown keys. Two viable shapes:

1. **Fresh-file write** (file absent, the first-run case): write a short
   wizard-appropriate header comment (NOT `SettingsFile.Header`, whose
   stack-discovery wording doesn't apply globally) plus up to three
   `planningAgent = …` lines. Trivial.
2. **Surgical line-level update** (file exists, the re-configure case):
   read lines; for each of the three agent keys, replace the existing live
   `key = …` line in place (identified via the same first-`=` split the
   parser uses — reuse/expose `splitAssignment`, SettingsFile.scala:232-235,
   currently `private`) or append missing keys at the end; leave every
   comment/blank line untouched. This preserves user comments (the one thing
   a full rewrite would destroy) at ~15 lines of code. If the existing file
   is *malformed* (parse fails), don't guess: show the parse error and offer
   "rewrite from scratch" — a malformed global file otherwise aborts every
   flow run, so the wizard is the natural repair point.

Recommendation: implement 1 + 2 as one small `GlobalSettingsWriter` (or new
methods on `SettingsFile` beside `render`, keeping the format knowledge in
one file, consistent with the `hasStackLines`-reuses-`splitAssignment`
pattern at SettingsFile.scala:244-255). A rendered value is just
`harnessNameFor(tag)` + optional `:model` — the exact inverse of
`AgentSpec.parse`, worth pinning with a round-trip test.

## 5. First-run detection

Proposed rule: **run the wizard when the global settings file
(`GlobalSettings.default`) does not exist, OR it parses cleanly to
`AgentSettings` with all three roles `None`** (a comments-only or
empty-values file — the parser treats empty values as absent,
SettingsFile.scala:127). This matches the plan's suggestion and covers the
"file created but never filled" edge.

Verified: nothing else creates the global file today — the only settings
write in the codebase is the project-file discovery write
(FlowLifecycle.scala:289-294); `GlobalSettings` itself is a pure path
computation. So "file absent" is a reliable first-run signal, with the
all-three-`None` clause as belt-and-braces.

Two edge cases:

- **Malformed existing file**: `SettingsFile.parse` fails → not "first run",
  but the shell should surface the error and offer the wizard as repair
  (see §4). Do not silently treat malformed as absent — that would clobber a
  user's typo'd hand edits.
- A user who genuinely wants the built-in default (claude everywhere) for all
  roles: the wizard should write explicit `… = claude` lines (or at minimum
  a comment) so the file exists and the wizard doesn't re-trigger every
  launch. Writing explicit lines is better: it also makes the
  role-announcement `Step` say `(global)` instead of `(default)`
  (RoleAgents.scala:156-163, 181-186), which is honest about where the choice
  came from.

## 6. Re-configure flow

Same wizard, two differences:

1. **Pre-selection**: the reader fully supports it. `SettingsFile.parse(content,
   SettingsScope.UserGlobal)` returns `ParsedSettings.agents: AgentSettings`
   (SettingsFile.scala:75-78) — per role an `Option[AgentSpec(backend,
   model)]` (`AgentSpec.scala:10, 50-60`). `AgentSpec.harnessNameFor`
   (AgentSpec.scala:28) maps the backend back to its display/settings name.
   So: parse the current file, pre-select each role's current harness
   (falling back to the detected-or-claude default used on first run), and
   preserve an existing `:model` pin verbatim when the user keeps the same
   harness (dropping a pin the user hand-wrote just because they re-ran the
   wizard would be surprising; if they *change* the harness, the old model
   pin is meaningless and is dropped).
2. **Write path**: the surgical update from §4 (comments preserved), rather
   than the fresh-file write.

Binary detection can re-run identically (it's cheap); its result decorates
the choices (`✓ found` / `not found on PATH`) but never filters them.

One knock-on the shell should surface after re-configuration: changing a
role's harness makes previously recorded sessions for that role mint fresh
with a warning at the next resumed run (ADR 0020 §8,
`adr/0020-configurable-role-agents.md:186-193`) — a one-line note in the
wizard's closing summary avoids a confusing warning later.
