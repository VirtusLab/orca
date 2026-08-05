# 12 — Reviewer and planner tool surface after claude loses Bash

Scope: `11-repair-read-only.md` (#78) decided to replace `--permission-mode
plan` with a `--tools` allowlist on claude. That removes `Bash` from read-only
agents. This document measures what reviewers actually did with `Bash`, tests
whether a cheaper flag already does the job, and proposes what to give back.

Method: categorising every `Bash` call in the reviewer transcripts on disk, plus
16 live `claude --print` probe runs (2.1.222, `claude-opus-5`, $1.17 total) in a
scratch directory outside the checkout.

## Answer

**The `--allowedTools "Bash(git log:*)"` route does not work.** The pattern
grants; it does not confine. In a session where the only rule was
`Bash(git log:*)`, `ls -la`, `wc -l`, `uname -r` and `git status --short` all
ran with no rule matching them and nothing recorded in `permission_denials`
(§4). Writes were denied, but by claude's own command classifier — the same
CLI-side gate orca does not control that #73 found behind plan mode. Not a
boundary orca can claim.

**MCP is the route.** Verified: with `--tools Read,Grep,Glob` and no `Bash`, an
MCP tool named only in `--allowedTools` was callable and returned its result,
and the model confirmed it had no `Bash` (§4).

**Most of what reviewers ran is already covered.** 64% of the 1016 measured
`Bash` calls are search, file reads and directory listings — `Grep`, `Read`,
`Glob` (§2). 3% write files or run programs and should simply stop.

**The git surface should be small.** 82% of git use re-derives the change set
the prompt already carries. Only 12% reaches outside it, in 24 of 87 sessions.
Two tools cover it: `git_show` and `git_file_at` (§6). No `git_diff`, no
`git_status`.

**`NetworkOnly` is broken the same way, and the corpus proves it without a
probe.** Three planner sessions called `Write` and created files (§5).
`ClaudeArgs.scala:153` claims `Enforcement.Hard` for that tier. It is false.
Measured planner `gh` usage across 6 sessions is **zero**.

---

## 1. Corpus

Claude Code transcripts for this project whose first user message carries the
`initial-review.md` prompt: **87 sessions**. Tool calls deduplicated by
`tool_use` **block id**, per the correction in #73 — message-id dedup drops
blocks.

Tool totals across the 87: `Bash` **1016**, `Read` 386, `StructuredOutput` 245,
`Skill` 14.

#73's baseline run reproduces exactly. Joining the run manifests by `wireId`,
one run holds **10 reviewer sessions with 199 `Bash` calls** — #73's figure.
Both cuts are reported below; the 87-session corpus is the wider distribution
and the one to design against.

## 2. What reviewers run in Bash

Commands are split on `&&`, `||`, `;`, `|` and newlines, and each segment
classified by the program it invokes. **759 of 1016 calls (75%) hold more than
one working segment.** Reviewers use `Bash` as a batching mechanism — a search,
a line-range read and an `echo` separator in one call — not only for things
`Read`/`Grep` cannot do.

Segments, 87 sessions:

| segment role | n | covered by |
|---|---|---|
| search (`grep` 715, `rg` 24) | 739 | `Grep` |
| pipeline filter (`head`, `tail`, `sort`, `awk` on piped input) | 579 | `Grep` `head_limit`, `Read` `limit` |
| shell glue (`echo` 467, `cd` 57, loops) | 531 | — batching artefact |
| **git read** | **482** | **nothing** |
| file read (`sed -n a,bp <file>` 272, `cat` 98, `head <file>`) | 355 | `Read` offset/limit |
| list (`ls` 74, `find` 46) | 123 | `Glob` |
| write / execute (`rm` 11, `mkdir` 11, `unzip` 6, `jar` 4, `sbt` 6, `scala-cli` 1) | 39 | — should stop |
| git mutate (`init` 4, `add`, `commit`, `push`) | 7 | — should stop |
| `wc` | 6 | `Read` |
| env probe (`which` 2, `man`, `date`) | 4 | — should stop |

Per call, by the capability it needs:

| | 87 sessions | baseline 10 |
|---|---|---|
| fully covered by `Read`/`Grep`/`Glob` | 646 (64%) | 89 (45%) |
| touches git | 342 (34%) | 108 (54%) |
| writes a file or runs a program | 29 (3%) | 4 (2%) |

The write/execute calls **ran**. Checked against the `tool_result` for each:
`mkdir`, `rm -rf`, `jar xf`, `git init`, `git add`/`commit`/`push`, `scala-cli
run` and `sbt testOnly` all produced real output. The two errors are `unzip:
command not found`, not permission denials. This is #73's finding at wider
scope: a nominally read-only reviewer wrote files, seeded a git repo, pushed to
a remote and ran the build.

### Git, by intent

491 git segments across the 87 sessions:

| | n | share |
|---|---|---|
| re-derive the change set — `git diff` / `git status` on the working tree or `HEAD` | 270 | 55% |
| re-derive it against a named base — `git diff master...HEAD` and friends | 133 | 27% |
| reach outside the diff — `git show` 22, `git log` 31, `git cat-file` 8 | 61 | 12% |
| repo state — `worktree list`, `rev-parse`, `branch --show-current`, `stash list` | 18 | 4% |
| mutating — `init` 4, `add`, `commit`, `push` | 7 | 1% |
| `git --version` | 2 | 0.4% |

Per call, and per session:

| | 87 sessions | baseline 10 |
|---|---|---|
| calls that are change-set re-derivation only | 282 (28%) | 78 (39%) |
| calls that reach outside the diff | 51 (5%) | 25 (13%) |
| calls for repo state | 9 (0.9%) | 5 (2.5%) |
| **sessions that ever reached outside the diff** | **24 / 87** | **10 / 10** |

The forms in the reach-outside bucket, in full: `git log --oneline -N` (18),
`git show <rev> -- <paths>` (13), `git log --oneline master..HEAD` (11), `git
show --stat <rev>` (9), `git cat-file -e <rev>:<path>` (8), `git log --oneline
-N -- <path>` (1), `git log --all --oneline -8 --format=…` (1). No `git show
<rev>:<path>` appears — but the corpus predates #79, which added exactly that
suggestion to the prompt.

### Verdict per category

**Already covered** — search, file reads, line ranges, listings, `wc`. 64% of
calls. Nothing to build.

**Worth replacing** — reaching outside the diff. 5% of calls but 28% of
sessions, and #79's base-commit note tells reviewers to do it. Losing it
silently makes that note a lie.

**Reviewers should stop** — re-deriving the change set (82% of git use; the
prompt already carries the diff, and #81's trailer names any file it cut and
says to read it directly), writing files, running builds, environment probes.

## 3. Batching is a real cost, and it is not recoverable

75% of calls do several things at once. `Read`/`Grep`/`Glob` are one operation
per call, so the same work costs more round-trips. This is not measured — the
transcripts show what reviewers did with `Bash`, not what the same reviewers
would do without it — and no probe was run. Flagging it as the likely cost of
the switch, not as a number.

## 4. The `--allowedTools` pattern route — probed, does not work

All probes: `claude --print`, 2.1.222, `claude-opus-5`, a scratch git repo,
`--safe-mode` (the operator's own hooks and CLAUDE.md off) unless noted. The
filesystem was checked after each.

**It grants.** `--tools Read,Grep,Glob,Bash --allowedTools "Bash(git log:*)"`,
asked to run `git log --oneline -1` — ran, output returned, no denial.

**It does not confine.** Same session shape. `ls -la` ran. `wc -l a.txt` ran.
`git status --short` ran. None matched the one rule; `permission_denials` was
empty for all three. Additive, exactly as #74 measured for plain
`--allowedTools`.

**What blocks writes is not the rule.** `mkdir d1`, `cp a.txt b.txt`,
`python3 -c "open('p1','w').close()"` and `touch x` were each denied, recorded
in `permission_denials`. A control run with no `--tools` and no `--allowedTools`
denied the same `touch`, so the denial comes from claude's built-in command
classifier, not from anything orca passes. Asking for the `Bash` call with
`dangerouslyDisableSandbox: true` changed nothing. That classifier is the same
class of unowned CLI-side gate #73 found behind plan mode: it varies (the corpus
above shows those writes running in production) and orca cannot depend on it.

**Prefix matching did not leak, in the forms tried.** `git log --oneline -1 &&
touch chained_write` was denied as a whole — the matcher decomposes the string
rather than matching the prefix. `git log --oneline -1 --pretty="$(touch
subst_write; echo %s)"` came back "Contains shell syntax (;) that cannot be
statically analyzed". Neither file appeared. So the leak is not in the matcher;
the problem is that the matcher is not the boundary.

**MCP passes through `--tools`.** A minimal stdio MCP server exposing one tool,
with `--tools Read,Grep,Glob` (no `Bash`) and the tool named in
`--allowedTools`: the tool was listed, called, and returned its marker string;
the model confirmed it had no `Bash` tool. Confirms #78's claim, which had not
been tested against a live server.

**Aside, and a real hazard.** The first probe ran with the operator's actual
config rather than `--safe-mode`. `git log --oneline -1` reached the tool as
`rtk git log --oneline -1` and was **denied** — matching neither
`Bash(git log:*)` nor the built-in classifier. Whether the rewrite came from the
`PreToolUse` hook or from the user's CLAUDE.md was not established. Either way:
anything that rewrites the command string before the permission check breaks
command-scoped matching, and orca spawns claude inside whatever config the user
has.

## 5. `NetworkOnly` is broken the same way

`ClaudeArgs.scala:123-131` gives `NetworkOnly` `--permission-mode plan` plus
`--allowedTools "WebFetch,WebSearch,Bash(gh issue view:*),Bash(gh pr view:*),
Bash(gh search:*),Bash(gh repo view:*),Bash(gh api:*)"`. `ClaudeArgs.scala:153`
calls that `Enforcement.Hard`.

**Probed with the production flag set.** Asked for three commands one at a time:
`uname -r` ran, `git log --oneline -1` ran — neither in the allowlist, neither
denied, no prompt raised. `mkdir` was refused **by the model**, which said so
itself: "This was my own refusal, not a permission-system denial." That is the
mechanism #78 named as the thing that is not enforcement.

**The corpus says the same without a probe.** Six planner sessions are
identifiable by their prompts (2 `planning`, 4 `assess-then-plan`). Three of
them called `Write` and created files, each returning "File created
successfully". A tier that is `Hard` no-edit created files.

**Measured planner `gh` usage is zero.** 59 `Bash` calls across the 6 sessions;
not one invokes `gh`, and there are no `WebFetch` or `WebSearch` calls either.
Every one of the 59 is `grep`, `ls`, `find` or `sed` — covered by
`Grep`/`Glob`/`Read`. The allowlist is wider than any observed use. Orca also
already fetches issues host-side (`GitHubTool.readIssue`,
`readIssueComments`), so the issue body reaches the planner through the prompt,
not through `gh`.

## 6. Proposed surface

One MCP surface for both tiers, on the bridge ADR 0012 already stands up. Fixed
subcommands, typed arguments, no command string and no flag passthrough — orca
builds the argv. Every tool is a read; none takes a `WorkspaceWrite`.

### Reviewers — `--tools Read,Grep,Glob,Skill`, plus:

```
git_show(rev: String, paths: List[String] = Nil, stat: Boolean = false): String
    # git show [--stat] <rev> [-- <paths>]

git_file_at(rev: String, path: String): String
    # git show <rev>:<path>
```

`git_show` covers the 22 `git show` calls measured. `git_file_at` covers the
idiom #79's prompt names and that reviewers lose outright without `Bash`; it has
no corpus support because the corpus predates #79.

`rev` accepts a hex sha, `HEAD`, or a branch name — validated against
`^[A-Za-z0-9._/-]+$` and rejected otherwise, so nothing can look like a flag.
`paths` are repo-relative, rejected if absolute or containing `..`. Output
bounded by the `BoundedDiff` renderer that already exists.

**Not proposed:** `git_diff` and `git_status`, despite being 82% of measured git
use. All of it re-derives what the prompt already holds, and #81's trailer names
any cut file and says to read it directly. Adding them buys back the round-trips
#73 measured the inline diff removing.

**Deferred:** `git_log`. 31 calls, almost all `--oneline -3`/`-5` orientation
rather than evidence. Add it if reviewers ask for it, with
`git_log(rev_range: Option[String], limit: Int, paths: List[String])` and
one-line output only.

### Planner — `--tools Read,Grep,Glob,Skill,WebFetch,WebSearch`, plus:

```
github_issue(owner: String, repo: String, number: Int): String
github_pr(owner: String, repo: String, number: Int): String
```

Thin wrappers over `GitHubTool.readIssue` / `readIssueComments`, which orca
already has. No `gh api`, no `gh search` — measured use of both is zero, and
`gh api` is the one entry that can mutate GitHub (`-X POST`), which the current
scaladoc admits.

**Why not the other two shapes.** Keeping `Bash` in `--tools` and scoping it
with `--allowedTools` is the option §4 falsified: the tier would stop being
no-edit and would have to drop to `PromptOnly`, and the scoping would not hold
anyway. Dropping `gh` entirely and keeping only `WebFetch`/`WebSearch` matches
the measured usage — but the owner has decided planning keeps network access,
and the MCP route costs two functions over an existing tool while keeping the
tier honest.

### Cost

MCP tools are advertised in every turn's tool list, so each one adds to the
fixed preamble (#68). Four small tools is a few hundred tokens per turn. Not
measured.

## 7. What I could not measure

1. **Why the classifier's behaviour differs.** In the corpus, `mkdir`, `rm -rf`,
   `git init` and `scala-cli run` ran. On 2.1.222 they are denied. The corpus
   ran under plan mode with orca's full flag set on an older build, so version,
   flags and permission mode are all confounded. Not isolated. The conclusion
   does not depend on it — either way the gate is claude's, not orca's.
2. **Per-round figures.** Only 2 transcripts carry the current `re-review.md`
   wording, so #73's 2.53 rounds/session cannot be re-derived here. All figures
   above are per call and per session. #73's per-round numbers stand on their
   own measurement.
3. **The cost of losing batching** (§3). No probe run.
4. **Whether reviewers would use `git_show` more than the corpus suggests.** The
   corpus predates #79's base-commit note, which invites exactly that.
5. **Where the `rtk` rewrite comes from** — `PreToolUse` hook or user CLAUDE.md.
   Not established.
6. **gemini.** No credentials on this host; unchanged from #78.
7. **MCP round-trip cost against `Bash`.** Not measured.

## Related

- #78 (`11-repair-read-only.md`, branch `research/drop-read-only`) — the
  `--tools` decision this builds on.
- #80 (`10-…`, branch `research/filesystem-sandbox`) — OS-level sandbox: no.
- #73 (`09-diff-vs-coordinates.md`) — the 199-call baseline and the block-id
  dedup correction.
- #74 — plain `--allowedTools` measured as additive.
- #79 — added the base commit to the initial review prompt.
- #81 — capped the review diff and named what it cut.
- ADR 0012 — the MCP host bridge these tools would live on.
