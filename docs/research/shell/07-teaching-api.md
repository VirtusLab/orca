> Final. Skeptic-amended (§Amended recommendation): bundled README + examples
> survives as primary, but extraction targets the HARNESS'S WORKSPACE
> (`.orca/cache/orca-api-<ver>/` for project flows; cwd `~/.config/orca/` for
> global flows) — out-of-workspace reads are not approval-free on claude/
> opencode and hard-fail on gemini. The scoped-AGENTS.md fallback is demoted:
> no harness auto-loads it from a root-cwd session.

# 07 — Teaching a harness the Orca API

Scenario: from the shell menu the user picks "create a new flow". The shell
launches the chosen harness in its own interactive terminal UI with an initial
prompt: "the goal of this session is to write a <xyz> global/project-specific
Orca flow, saved to <path>". The agent must learn Orca's flow-DSL API from
somewhere. This document compares the delivery options.

## Ground facts

- **README.md** (`/home/adamw/orca/README.md`): 46,111 chars, 814 lines,
  ~6,260 words → **~11–13k tokens**. It is deliberately self-contained: full
  API reference tables (built-in tools, flow methods, sessions, planning/review
  utilities, data structures), the authoring rules, settings grammar, and a
  complete worked example flow. Per the project's doc policy, the reference
  tables stay in the README — so it *is* the API reference; there is no
  separate reference doc to point at.
- **Release-pinned correctness**: `build.sbt` runs `UpdateVersionInDocs` on
  release (lines ~197–204), rewriting the `//> using dep
  "org.virtuslab::orca:X.Y.Z"` lines in `README.md` and the examples. So the
  README **at a given git tag** always shows the matching dependency
  coordinate — a tag-pinned copy is internally version-consistent for free.
- **Tag convention**: `git tag -l` → `v0.0.0` … `v0.0.17`, i.e. `vX.Y.Z`.
  Raw pinned URL shape:
  `https://raw.githubusercontent.com/VirtusLab/orca/v0.0.17/README.md`.
- **Examples** (`/home/adamw/orca/examples/*.sc`): 6 flows, 2.5–10.6 KB each,
  26.8 KB total (~7k tokens for all six; `implement.sc` alone is 2.7 KB /
  ~700 tokens). Each opens with the `//> using` header and a scaladoc comment
  explaining the flow's shape — excellent few-shot material. (Topic 4 moves
  built-ins to `flows/`; the same files serve here.)
- **AGENTS.md** (17.6 KB, ~4.5k tokens) is contributor-facing (internals,
  capability tokens, sbt recipes). It is the wrong document for a flow
  *author*: flow scripts never touch `RuntimeInStage`, sbt, or the module
  layout. Do not feed it to the flow-writing agent.
- **JAR resource status**: the README is *not* currently on any module's
  classpath. The build already uses a `resourceGenerators` task (flow module,
  `cc-test-classpath.txt`, build.sbt:139), so bundling `README.md` +
  `examples/*.sc` into the shell module's resources at build time is an
  established one-task pattern — no release-time fetching needed.

## Option (a): link to the tag-pinned README on GitHub

The prompt contains the raw URL; the agent fetches it.

Per-harness fetch capability (verified July 2026):

| Harness | Out-of-the-box URL fetch | Notes |
|---|---|---|
| claude | **Yes** — `WebFetch` built-in | Prompts for approval in interactive mode (user is present, fine). |
| codex | **No full-page fetch.** Built-in web search returns *snippets* from a cached/indexed store by default; even `web_search = "live"` / `--search` does not scrape full page content. `curl` via shell needs a network-enabled sandbox — off by default in workspace-write mode. | Link-only fails or degrades to snippets. Sources: [developers.openai.com/codex/cli](https://developers.openai.com/codex/cli), [firecrawl.dev glossary on Codex web access](https://www.firecrawl.dev/glossary/web-search-apis/codex-cli-agent-browse-web), [codex config basics](https://developers.openai.com/codex/local-config/). |
| gemini | **Yes** — `web_fetch` built-in (asks confirmation; falls back to a local fetch when the API-side fetch fails). [Docs](https://github.com/google-gemini/gemini-cli/blob/main/docs/tools/web-fetch.md). |
| opencode | **Yes** — `webfetch` built-in (HTML→markdown, 5 MB limit, permission-controllable). [Docs](https://opencode.ai/docs/tools). |
| pi | **No web tool by default** — deliberately minimal (read/write/edit/bash + grep/find/ls). Web fetch is an install-it-yourself extension ([pi-web-access](https://pi.dev/packages/pi-web-access)). `bash` + `curl` works if the user approves and has network. |

Verdict: **fails as the sole mechanism** — codex (no page fetch, sandboxed
network) and pi (no web tool) can't be relied on. Also costs a network
round-trip and an approval prompt even on the harnesses that can fetch.
Staleness is solved by the tag pin, but only if the shell knows the right tag
(it does — it knows its own orca version, topic 4).

## Option (b): embed the README (inline, or temp file + path)

Two sub-variants:

- **Inline in the initial prompt**: +~12k tokens on turn one, unconditionally,
  even if the user immediately asks for something trivial. Also makes the
  visible initial prompt in the harness UI enormous — poor UX in an
  *interactive* session where the user sees the prompt.
- **Write to a file, reference the path** (better): the shell extracts the
  bundled README to a scratch location (e.g. `.orca/cache/orca-api-<ver>.md`
  in the project, or `$XDG_CACHE_HOME/orca/` for global flows) and the prompt
  says "the Orca API reference is at <absolute path>; read it before writing
  the flow". Every harness reads local files reliably — this is the one
  channel with **uniform, offline, approval-free reliability across all five
  backends** *[refuted as stated — holds only for in-workspace paths; see
  Skeptic review]*. The agent pays the ~12k tokens only when it actually reads the
  file, and harnesses that read selectively (grep/offset reads) can pay less.

Shipping: bundled via the resource-generator mechanism described under Ground
facts / research 04 §4 — version-pinned by construction.

## Option (c): VirtusLab/cellar

What it is ([github.com/VirtusLab/cellar](https://github.com/VirtusLab/cellar)):
a **CLI** (not an MCP server) that answers API queries about JVM dependencies
by reading **TASTy** (Scala 3: full signatures, flags, docstrings), Scala 2
pickles (best-effort), and Java class files, resolving artifacts via
**Coursier**. Output is plain Markdown on stdout, designed to be dropped into
an LLM context. Relevant commands:

```sh
cellar get-external    <group:artifact_3:version> <fqn>   # one symbol, full signature + doc
cellar list-external   <group:artifact_3:version> <pkg>   # list public symbols
cellar search-external <group:artifact_3:version> <query> # substring search
cellar get-source      <group:artifact_3:version> <fqn>   # decompiled/attached source
```

e.g. `cellar search-external org.virtuslab:orca_3:0.0.17 reviewAndFixLoop`.
Full coordinates required (`::` shorthand unsupported); `latest` resolves the
newest release. It also auto-detects sbt/mill/scala-cli projects for
classpath-aware queries. Agent integration exists: a **Claude Code plugin**
(`/plugin marketplace add virtuslab/cellar`), suggested `CLAUDE.md` snippets,
and a non-interactive mode that emits `{"status":"needs_input",...}` JSON
instead of blocking on telemetry prompts.

State: **v0.1.0-M10 (July 2026), 17 releases, ~76 stars — active but
milestone-stage**, from the same org as orca.

Realistic assessment for this scenario:

- Works through plain shell access, so *in principle* every harness can use it
  (all five have bash). But it must be **installed** (`cs install --contrib
  cellar` / Nix / binary) — the shell can't assume it, so it's a conditional
  path: detect on `PATH`, mention in the prompt only if present.
- First-use artifact resolution needs network (Coursier fetch) — the same
  codex-sandbox problem as (a), though usually the orca artifacts are already
  in the local Coursier cache (the shell itself runs from them).
- Signature-level answers are its strength; what it does **not** convey is the
  *usage model* — authoring rules, stage/session semantics, settings grammar,
  the "mutations inside stages" discipline. An agent writing its first flow
  needs the README's prose, not just signatures. Cellar answers the follow-up
  questions ("exact parameters of `reviewAndFixLoop`?") better than the
  opening one ("how do I write a flow?").
- Milestone maturity + install dependency + telemetry prompt edge cases →
  not fit to be the *primary* channel yet.

Verdict: **excellent optional supplement**, wrong primary. One prompt line:
"if `cellar` is on PATH, you can query exact signatures with `cellar
get-external org.virtuslab:orca_3:<ver> <fqn>`".

## Option (d): condensed purpose-written flow-authoring guide

A hand-maintained "flow-authoring guide" (subset of README + template +
few-shot examples) shipped with the shell. Token-optimal (~3–5k), but it
**duplicates the README's reference content**, and the project's documented
doc policy is the opposite: the README *is* the self-contained reference, with
repeated explanations cut elsewhere. A second reference doc is a standing
staleness/divergence liability that `UpdateVersionInDocs` does not cover
(it only rewrites version strings, not API drift). Generating the condensed
doc from the README at release time (strip sections) is fragile section-name
coupling for a saving of ~7k tokens the agent pays at most once per
flow-authoring session.

What *is* worth taking from (d): the **few-shot examples**. Ship 1–2 example
flows (e.g. `implement.sc`, ~700 tokens, whose header comment already says it
"mirrors the README example") alongside the README and point the prompt at
them: "example flows are at <paths>; start from the closest one."

## Option (e): prior art — how tools teach agents an API

- **llms.txt** ([llmstxt.org](https://llmstxt.org/)): a root-level curated
  markdown index of docs for LLM consumption. Orca could publish one, but it's
  a *discovery* convention for web crawlers/fetchers — it inherits option
  (a)'s fetch-capability problem and adds nothing for local sessions. Low
  priority; cheap to add to the repo later for organic (non-shell) agents.
- **AGENTS.md / CLAUDE.md scoped instruction files**
  ([agents.md](https://agents.md/), now stewarded by the Linux Foundation's
  Agentic AI Foundation; adopted by 60k+ repos as of mid-2026): read natively
  by codex, opencode, gemini and cursor/copilot/zed etc.; Claude Code reads
  AGENTS.md too (CLAUDE.md remains its richer native format). Pi reads
  AGENTS.md as well. This is the one convention **all five harnesses honour
  without prompting**. Relevance here: for a *project* flow, the shell could
  drop a scoped instruction file next to the flow (e.g.
  `.orca/flows/AGENTS.md`: "flows in this directory use the Orca DSL —
  reference at <path>, pin version <ver>, verify with `scala-cli compile`"),
  so *future* editing sessions in any harness pick the context up
  automatically, not just the shell-launched one. Writing files into the
  user's repo root is invasive; a directory-scoped file under `.orca/flows/`
  is not (the directory is orca's own).
- **Skill files** (Anthropic Skills, `SKILL.md`): richer packaging
  (instructions + resources loaded on demand) but claude-specific — same
  cross-harness problem as (a), inverted. Not a fit as the common channel;
  could later ship as a cellar-style optional plugin for claude users.

## Prompt content beyond the API reference

Whatever the channel, the initial prompt itself must state:

1. **The exact version pin**: the flow must start with
   `//> using scala 3.8.4`, `//> using dep "org.virtuslab::orca:<version>"`,
   `//> using jvm 21` — with `<version>` filled in by the shell (it knows its
   own release; topic 4). Don't rely on the agent copying it from the
   reference — state it verbatim in the prompt. (The tag-pinned README shows
   the same version in its examples, so the two sources agree.)
2. **The target path and scope**: global (`~/.config/orca/flows/<name>.sc`)
   vs project (`.orca/flows/<name>.sc`) — per topic 5's well-known paths —
   and the first-line `//` description convention for flow listings.
3. **How to verify**: "after writing, run `scala-cli compile <path>` and fix
   errors until it compiles". This is cheap (no agents run), catches most DSL
   misuse thanks to the compile-time capability gating (mutations outside
   stages don't compile — the type system is itself a teaching channel), and
   requires only that the pinned version is resolvable (published to Maven
   Central; for dev builds, `//> using repository ivy2Local` +
   `sbt publishLocal`). Optionally suggest a `--server=false` compile for
   speed. Actually *running* the flow is out of scope for the authoring
   session (it would launch agents against a repo).

## Comparison

| Option | Reliability across harnesses | Token cost | Staleness risk | Effort |
|---|---|---|---|---|
| (a) tag-pinned URL | Poor — fails codex, pi; approval prompts elsewhere | ~12k when fetched | None (tag-pinned) | Trivial |
| (b) bundled README → temp file + path in prompt | **Uniform — local file read works everywhere** *[refuted as stated — holds only for in-workspace paths; see Skeptic review]* | ~12k, paid only when read | **None (bundled at release, version-rewritten)** | Small (one resourceGenerator + extract-on-launch) |
| (c) cellar | Conditional (must be installed; network for cold cache) | Low per query | None (queries the pinned artifact) | Small (PATH probe + prompt line) |
| (d) condensed guide | Uniform (local file) | ~3–5k | **High — second reference to keep in sync** | Ongoing maintenance |
| (e) scoped AGENTS.md under `.orca/flows/` | Uniform (all five read it natively) *[refuted — none load `.orca/flows/AGENTS.md` from a root-cwd session; see Skeptic review]* | Tiny (points at the reference) | None if it only points | Small |

## Recommendation

**Primary: option (b), file-reference variant.** Bundle `README.md` and 1–2
example flows into the shell artifact at build time (via the
resource-generator mechanism described under Ground facts / research 04 §4);
on "create a new flow", extract them to a cache path and hand the harness an initial prompt
containing: the goal + target path, the verbatim `//> using` header with the
pinned version, the absolute path of the API reference and examples ("read
the reference before writing"), and the `scala-cli compile` verification
instruction. This is the only channel that is offline, approval-free, and
uniform across claude/codex/opencode/pi/gemini, with zero staleness by
construction.

**Fallbacks/supplements:**
- For a *project* flow, additionally write a small scoped `AGENTS.md` into
  `.orca/flows/` pointing at the reference and stating the pin + compile
  check, so later ad-hoc editing sessions in any harness inherit the context
  (option e). *[refuted — none load `.orca/flows/AGENTS.md` from a root-cwd
  session; see Skeptic review]*
- Mention the tag-pinned raw URL
  (`https://raw.githubusercontent.com/VirtusLab/orca/v<ver>/README.md`) in the
  prompt as a fallback if the local file is somehow missing (option a).
- If `cellar` is detected on `PATH`, add one prompt line advertising
  `cellar {search,get,list}-external org.virtuslab:orca_3:<ver> …` for exact
  signatures (option c). Do not depend on it.

Rejected as primary: (a) — codex and pi can't fetch pages out of the box;
(d) — duplicates the reference the project's doc policy keeps single-sourced.

## Sources

- https://github.com/VirtusLab/cellar and its README (v0.1.0-M10, July 2026)
- https://developers.openai.com/codex/cli · https://developers.openai.com/codex/local-config/ · https://www.firecrawl.dev/glossary/web-search-apis/codex-cli-agent-browse-web
- https://github.com/google-gemini/gemini-cli/blob/main/docs/tools/web-fetch.md
- https://opencode.ai/docs/tools
- https://pi.dev/packages/pi-web-access · https://dev.to/arshtechpro/pi-the-open-source-ai-coding-agent-you-probably-havent-tried-yet-2h0h
- https://agents.md/ · https://llmstxt.org/
- Local: `/home/adamw/orca/README.md`, `/home/adamw/orca/AGENTS.md`, `/home/adamw/orca/build.sbt` (UpdateVersionInDocs, resourceGenerators), `/home/adamw/orca/examples/`, `git tag -l`

## Skeptic review

Independently verified July 2026: local measurements re-run, per-harness
capabilities re-researched against current docs and (for codex/gemini) the
tools' source on `main`. Two of the proponent's claims fail; the primary
recommendation survives but its *mechanism* needs a one-line amendment.

### Verdicts on load-bearing claims

**Ground facts — CONFIRMED.** Re-measured: `README.md` = 46,111 chars /
814 lines / 6,261 words (~11.5k tokens at ~4 chars/token; the 11–13k range is
fair — markdown tables tokenize dense). `git tag -l` → `v0.0.0`…`v0.0.17`,
all `vX.Y.Z`. `AGENTS.md` = 17,593 B. Examples: 6 flows, 26,806 B total,
2,502–10,583 B each — all match. The `resourceGenerators` precedent exists
(build.sbt:139, `Test`-scoped, flow module) — pattern claim holds.

**"codex cannot fetch URLs" — CONFIRMED, and still current.** Re-verified
against the codex source (`codex-rs/ext/web-search/src/extension.rs`,
`protocol.rs`) and current docs (note: `developers.openai.com/codex/*` now
redirects to `learn.chatgpt.com/docs/*`). `web_search` has four modes
(`disabled`/`cached`/`indexed`/`live`), default `cached` = OpenAI-maintained
index, `external_web_access = false`; even `live` is a *search* tool with no
"open this URL" parameter. No dedicated fetch/browse tool exists in the CLI
(that's what MCP servers are for). `curl` under default `workspace-write`:
`network_access: bool` is documented "false by default" in `protocol.rs`.
One softening: under the default `on-request` approval policy the agent *can
ask* the user to approve an out-of-sandbox `curl` — so the URL fallback is
"approval friction", not impossible, on codex. Still correctly rejected as
primary.

**"pi has no web tool" — CONFIRMED, twice over.** Orca's own driver documents
it: `/home/adamw/orca/pi/src/main/scala/orca/tools/pi/PiArgs.scala:18` — "Pi
has no web/fetch tool, so the only network path is the general `bash` tool".
Current upstream (project moved: `badlogic/pi-mono` →
`github.com/earendil-works/pi`, npm `@earendil-works/pi-coding-agent`;
`pi.dev` is the official site) ships 7 built-in tools, web access only via
optional extensions (`pi-web-access` et al.). Minor correction to the ground
facts: `grep`/`find`/`ls` exist but are *off by default* (`--tools` to
enable); `read`/`write`/`edit`/`bash` are the active defaults.

**"local file read is uniform and approval-free across all five" — REFUTED
as stated.** This is the doc's central mechanism claim and it is wrong for
out-of-workspace paths (`$XDG_CACHE_HOME/orca/…`), which the draft explicitly
proposes for global flows:

| Harness | Read of absolute path outside launch dir |
|---|---|
| claude | **Approval prompt** (security docs: reads outside the workspace boundary "possible after an approval prompt"; avoid via `--add-dir` / `additionalDirectories`) |
| codex | OK — sandbox restricts writes, not reads (`has_full_disk_read_access` is unconditionally `true` in `protocol.rs`); no prompt |
| gemini | **Hard failure, no prompt** — `read_file` → `validatePathAccess` errors with "Path not in workspace" unless the dir was added via `--include-directories` / `/directory add` |
| opencode | **Approval prompt** — `external_directory` permission defaults to `ask` and covers `read`/`grep`/`glob`/path-taking bash |
| pi | OK — deliberately no permission system; any path readable |

So the claim is true for exactly two of five harnesses; on gemini the
proposed `$XDG_CACHE_HOME` reference doesn't degrade — it *breaks*. The fix
is cheap (below): the uniformity claim holds if and only if the extracted
files live **inside the directory the harness is launched in**.

**"full README vs curated subset" — proponent RIGHT, now with numbers.** The
non-API content is only the tail (lines 769–814: authenticating agents,
setup, docs links, license) = 1,638 B ≈ **3.6%** of the file; the intro
(1,113 B) is useful orientation. The README is ~95% flow-author-relevant, so
a curated subset or byte-range/section pointer would save ~0.4k of ~11.5k
tokens — noise. Rejecting (d) is correct, and section pointers are not worth
specifying either. Reference-not-embed also stands: 814 lines fits a single
default read in every harness, and a 46 KB argv/visible prompt is strictly
worse UX for zero savings.

**"`scala-cli compile` catches most DSL misuse" — PARTIAL.** What compile
*does* catch in a plain script (no CC imports): missing-capability errors —
`InStage`/`WorkspaceWrite`/`FlowControl` are context parameters provided
only by `stage(...)`/`flow(...)` bodies, so "mutations outside stages don't
compile" holds — plus all ordinary type/signature misuse. What it does
*not* catch (README CC section; ADR 0018 §5 "authoring rules the compiler
cannot enforce", §6): fork-boundary violations (`stage` inside a fork, a
`WorkspaceWrite` captured into a fork) — givens are lexically in scope in
fork lambdas, so these **compile** in a plain script and fail at *runtime*
via the owner-thread guard; capture/separation checking is opt-in via the
two `language.experimental` imports and full fork-boundary checking in
scripts awaits Ox's CC adoption; and R12-class ordering rules
(push-after-commit, no-concurrent-stages). Two operational caveats the draft
missed: (i) on **codex**, `scala-cli compile` writes to `~/.cache/coursier`
/ `~/.scala-build` and may need network for first resolution — both blocked
by the default workspace-write sandbox, so the verification step will
trigger an approval escalation there (user present, acceptable, but not
frictionless as implied); (ii) the pin is only compilable if resolvable —
fine for releases, but a dev shell reports version `"dev"` (topic 4:
manifest `Implementation-Version`, `"dev"` fallback), so the shell must
suppress flow-creation or inject `//> using repository ivy2Local` for dev
builds (the `_seed_lib.sh` precedent). Version story otherwise cross-checks
against topic 4: the shim floats `latest.release`, the shell knows its
resolved version from the jar manifest, and the bundled README is
version-correct because `updateDocs` runs before tagging and CI builds from
the tag.

**"AGENTS.md is honoured natively by all five" — REFUTED.** Current state:

| Harness | Root AGENTS.md | Scoped `.orca/flows/AGENTS.md`, session at repo root |
|---|---|---|
| claude | **No** — reads `CLAUDE.md` only; official docs say to add a `CLAUDE.md` containing `@AGENTS.md` or a symlink | No (but a *nested* `CLAUDE.md` in the directory is loaded on demand — claude is ironically the only one with true directory-scoped loading) |
| codex | Yes (global + git-root→cwd chain) | **No — codex stops at the cwd, never descends** (open feature request, openai/codex #12115) |
| gemini | **No by default** — `GEMINI.md`; adding AGENTS.md to defaults was closed `not_planned` (gemini-cli #12345, 2026-05-08); needs user's `context.fileName` setting | No |
| opencode | Yes (upward traversal from cwd + global) | No — upward only |
| pi | Yes (upward traversal + `~/.pi/agent/`) | No — upward only |

For the stated purpose — future ad-hoc sessions *started at the project
root* picking up context when editing `.orca/flows/*.sc` — the scoped file
is read by **zero** of the five harnesses. It only works when the session's
cwd is inside `.orca/flows/` (then codex/opencode/pi find it; claude needs a
`CLAUDE.md` shim; gemini still needs user config). The fallback is nearly
worthless as designed and must be demoted/reshaped.

### Amended recommendation

Option (b) survives as primary, with one mechanism change and a demotion:

1. **Extract into the harness's workspace, never `$XDG_CACHE_HOME`.** For
   project flows: `{workDir}/.orca/cache/orca-api-<ver>/` (README + example
   flows) — `.orca/cache/` already exists, is created via `OrcaDir`, and
   self-gitignores with a `CACHEDIR.TAG` (topic 5), so nothing lands in the
   user's repo history. For global flows: launch the harness with
   cwd = `~/.config/orca/` and extract under it (e.g. `cache/`), with the
   target `flows/<name>.sc` also inside — required anyway, since gemini's
   *write* boundary is the same workspace. This restores the "uniform,
   approval-free" property the draft claimed. Per-harness workspace-extension
   flags (`--add-dir`, `--include-directories`) are the fallback if extraction
   into the workspace is ever impossible, not the default.
2. **Prompt content**: unchanged (verbatim pin, target path, compile check),
   plus two lines the compile-caveat analysis motivates: state that
   fork/ordering rules are enforced at runtime, so "follow the README's
   authoring rules; the compiler will not catch everything", and optionally
   suggest adding the two capture-checking language imports for stricter
   compile-time checking. On codex, expect one approval escalation for the
   first `scala-cli compile` (network + home-dir caches).
3. **Demote the scoped-AGENTS.md fallback.** If shipped at all, it must be a
   *pair*: `.orca/flows/AGENTS.md` + a one-line `.orca/flows/CLAUDE.md`
   containing `@AGENTS.md` — and be documented as effective only for
   sessions started inside `.orca/flows/` (claude's nested on-demand loading
   is the sole root-cwd case that works). Do not describe it as "all five
   honour it natively"; today none of them honour it from a root-cwd session.
4. **URL fallback**: keep as a last-resort prompt line, reworded — it works
   with an approval prompt on claude/gemini/opencode, and on codex only via
   an out-of-sandbox `curl` approval; on pi only via `bash`+`curl`. The
   cellar supplement is unchanged (its assessment held up). Housekeeping:
   the draft's pi citations should point at `pi.dev` /
   `github.com/earendil-works/pi` (the project moved out of
   `badlogic/pi-mono`).

### Skeptic sources

- codex: `github.com/openai/codex` — `codex-rs/protocol/src/protocol.rs`
  (`has_full_disk_read_access`, `network_access` default false),
  `codex-rs/ext/web-search/src/extension.rs` (`WebSearchMode`);
  learn.chatgpt.com/docs/sandboxing · /docs/agent-configuration/agents-md;
  openai/codex issue #12115 (nested AGENTS.md, open)
- claude: code.claude.com/docs/en/memory (AGENTS.md → `@AGENTS.md` import),
  /docs/en/security (out-of-boundary reads prompt), /docs/en/permissions
  (`additionalDirectories`, `--add-dir`)
- gemini: `packages/core/src/config/config.ts` (`validatePathAccess`),
  `packages/core/src/tools/memoryTool.ts` (`DEFAULT_CONTEXT_FILENAME =
  'GEMINI.md'`), gemini-cli issue #12345 (closed `not_planned` 2026-05-08),
  geminicli.com/docs/tools/file-system · /docs/tools/web-fetch
- opencode: opencode.ai/docs/permissions (`external_directory: ask`),
  /docs/rules (upward traversal), /docs/tools
- pi: pi.dev · pi.dev/packages · `github.com/earendil-works/pi`
  (coding-agent README: tools, context files, no permission system)
- Local: `PiArgs.scala:18`, `CodexArgs.scala` (`networkConfigArgs` — network
  off by default), `adr/0018-stage-bound-flow-runtime.md` (§5 R12, §6 CC
  amendments), README CC section (runtime guards, opt-in imports),
  `04-packaging-launch.md` (version story), `05-flow-discovery.md`
  (`.orca/cache/` self-ignoring)
