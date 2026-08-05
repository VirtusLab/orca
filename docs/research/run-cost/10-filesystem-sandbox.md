# 10 — An OS-level filesystem sandbox for agent subprocesses

Can orca run an agent CLI so that it can read the workspace but not write to
it, enforced by the operating system rather than by asking the model?

Background: `withReadOnly` / `ToolSet.ReadOnly` does not deliver that today. Ten
reviewer sessions recording `permissionMode: plan` issued 199 `Bash` calls with
zero permission denials, and one of them wrote a file to `/tmp` and ran
`scala-cli` (`09-diff-vs-coordinates.md:83-101`). The feature is being removed.
This asks whether an honest replacement exists.

## Answer

**Linux: it works, and it is not worth building as an orca-owned wrapper.**

Two independent mechanisms deliver the guarantee, both verified end to end
against a real, write-capable `claude` turn that actively tried its `Write` tool
and then `Bash` (§2, §3). Both cost almost nothing at runtime. But both break
codex, and codex is the one backend whose `ReadOnly` is genuinely enforced today
(§4). So a generic wrapper would trade a working guarantee for a new one and
leave orca maintaining per-backend exemptions and per-backend write allowlists
anyway.

**macOS: possible on paper, unverified, and orca should not own the profile.**
`sandbox-exec` still works and children inherit it, but it is deprecated with no
replacement and no removal date, and a wrong profile is a silent no-op — a
shipping agent product was escaped exactly that way (§6). Everything in §6 is
read, not run.

**The cheap route already exists: each backend's own sandbox.** codex ships one
and orca already uses it. claude 2.1.222 ships one too (§5). Both cover Linux
and macOS with no native code and no profile for orca to get wrong.

**Whatever is decided, `Enforcement` should stop claiming `Hard` for
`ReadOnly` on backends where it is not** (§8).

Evidence tags: **[V]** read in the code, **[M]** measured by running it here,
**[I]** inferred, **[U]** unknown.

Everything tagged **[M]** was run on one Linux host: Ubuntu, kernel
`7.0.0-28-generic`, x86_64, `kernel.apparmor_restrict_unprivileged_userns = 1`,
bubblewrap 0.11.1, Landlock ABI 8, landrun 0.1.15, Docker 29.1.3, claude 2.1.222,
codex-cli 0.145.0, gemini 0.50.0, opencode 1.17.10, pi 0.80.2. **No macOS was
available**, so §6 is documentation only and is held to a lower confidence than
the rest. Two packages were installed to run the probes: `socat` (a claude
sandbox dependency) and `landrun`.

---

## 1. The requirement is not "read-only", it is "read-only plus an allowlist"

A pure read-only filesystem breaks every backend. What each one needs to write
was measured, not guessed: a real turn under `strace -f` tracing
`openat/open/creat/mkdir/rename/unlink/link/symlink/truncate`, keeping only
calls carrying `O_WRONLY|O_RDWR|O_CREAT|O_APPEND|O_TRUNC`.

**claude, one trivial read-a-file turn (6,625 traced calls):** **[M]**

| path | what |
|---|---|
| `/dev/null`, `/dev/tty` | 11 opens; `> /dev/null` is in nearly every shell command |
| `~/.claude/**` | transcripts (`projects/<slug>/`), `sessions/`, `session-env/`, `plugins/cache/**` |
| `~/.claude.json`, `~/.claude.json.lock` | in `$HOME` itself, not under `~/.claude` |
| `~/.cache/claude-cli-nodejs/**` | MCP server logs |
| `~/.npm/**` | `_cacache`, `_npx/*/concurrency.lock`, `_logs` — from MCP servers launched via `npx` |
| `/tmp/**` | `node-compile-cache`, `claude-<uid>` |
| `~/.local/state/claude/locks` | attempted |

Two things to note. `~/.claude.json` means the allowlist cannot stop at
`~/.claude`. And the `~/.npm` traffic comes from plugins configured on this
machine — the write set is **environment-dependent**, not a fixed list.

**codex, same turn:** `~/.codex/**` (`state_5.sqlite`, `sessions/`,
`shell_snapshots/`, `tmp/arg0/`, `cache/`, `plugins/cache/`), `/dev`, and
`/tmp/codex-bwrap-synthetic-mount-targets-<uid>/`. **[M]**

**pi** writes inside the workspace: `<workDir>/.orca/cache/pi-sessions/<id>/`
(`OrcaDir.scala:89-98`, `PiBackend.scala:63`). **[V]** So "the workspace is
read-only" is already false as a blanket statement — `.orca/cache/` has to be
carved out.

**gemini and opencode were not measured. [U]** The method above is ~10 minutes
per backend.

The rest of `.orca/` is written by orca's own JVM (`ProgressStore.scala:65`,
`Lint.scala:44`), which is outside any sandbox, so it needs nothing. **[V]**

Network is untouched by everything in this document: both mechanisms restrict
the filesystem only, and `https://api.anthropic.com/v1/messages` answered `405`
(i.e. connected) from inside each. **[M]**

---

## 2. Bubblewrap

`bwrap --ro-bind / / --dev /dev --proc /proc --tmpfs /tmp --bind <rw paths> --die-with-parent --chdir <workspace>`

| check | result |
|---|---|
| runs unprivileged, no setuid (`/usr/bin/bwrap` is mode 755) | yes **[M]** |
| workspace write | blocked, `EROFS` **[M]** |
| workspace read, `git status`, `git diff` | fine **[M]** |
| rw allowlist (`~/.claude`, `.orca/`, `/tmp`) | writable **[M]** |
| child process write | blocked **[M]** |
| `setsid` detached child write | blocked **[M]** |
| `unshare -Um` inside | denied **[M]** |
| nested `bwrap` inside | denied **[M]** |
| network | works **[M]** |
| spawn cost | ~10.7 ms (321 ms for 30 spawns vs 26 ms bare) **[M]** |

**End to end.** A `claude -p` turn with `--permission-mode acceptEdits
--allowedTools "Bash,Write,Read"` — so nothing but the OS was stopping it — was
told to create a file in the workspace. It tried the `Write` tool, got `EROFS:
read-only file system`, fell back to `Bash`, got `Read-only file system`, and
reported the failure. The turn completed normally and the file did not exist
afterwards. **[M]**

**Descendants are covered on other hosts too, not just this one.** Here the
nested-namespace attempts were blocked by Ubuntu's AppArmor policy (the child
runs as `bwrap//&unpriv_bwrap (enforce)` **[M]**), which is host-specific. The
portable guarantee is in the kernel: `mount_namespaces(7)` point [5] — "The
mount(2) flags MS_RDONLY, MS_NOSUID, MS_NOEXEC … become locked when propagated
from a more privileged to a less privileged mount namespace, and may not be
changed in the less privileged mount namespace." Point [3] says a nested
namespace *can* stack a new mount on top of a locked one, but writes then land
on the stacked mount, not on the workspace files. **[V]** Not tested on a host
without AppArmor. **[U]**

**Costs.**

- **Needs installing** (`apt install bubblewrap`). Present here; not everywhere.
- **Ubuntu 24.04+ needs an AppArmor profile.** Works here because Ubuntu ships
  `/etc/apparmor.d/bwrap-userns-restrict`. Without a profile,
  `apparmor_restrict_unprivileged_userns=1` denies the user namespace. **[M]**
- **Does not work in a stock container.** Every Docker variant was measured: **[M]**

  | docker flags | result |
  |---|---|
  | default | `Creating new namespace failed: Operation not permitted` |
  | `--security-opt seccomp=unconfined` | `Failed to make / slave: Permission denied` |
  | `--security-opt apparmor=unconfined` | namespace creation still denied |
  | `--cap-add SYS_ADMIN` | `Failed to make / slave: Permission denied` |
  | both unconfined, no `--unshare-user` | namespace creation denied |
  | both unconfined, **with `--unshare-user`** | works |
  | `--privileged` | works |

  So running orca in a container means turning off two container hardening
  layers to turn on one. That is a bad trade on its face.

---

## 3. Landlock

Landlock is an in-kernel LSM. No privilege, no setuid helper, no namespaces, no
mounts. Kernel ABI 8 here. **[M]**

The right shape for this job is narrow: declare **only write rights** in
`handled_access_fs`, then grant those rights on the allowlist. Rights that are
not "handled" are unrestricted, so reads need no rules at all and cannot be
broken by a missing one. Everything else on the filesystem becomes read-only.

Two ways to apply it, both verified:

- A ~90-line C helper that calls `landlock_create_ruleset` /
  `landlock_add_rule` / `prctl(PR_SET_NO_NEW_PRIVS)` / `landlock_restrict_self`
  and then `execvp`s the target. Written and run here. **[M]**
- **`landrun`, already packaged in Debian and Ubuntu** (`apt install landrun`,
  0.1.15). `landrun --rox / --rw /dev --rw /tmp --rw ~/.claude … -- <cmd>`
  gives the same result with no code for orca to own. **[M]**

| check | result |
|---|---|
| workspace write | blocked, `EACCES` **[M]** |
| workspace read, `ls`, `git status`, `git diff` | fine **[M]** |
| child process write | blocked **[M]** |
| `setsid` detached child write | blocked **[M]** |
| nested Landlock | stacks; inner domain intersects with outer **[M]** |
| network | works **[M]** |
| **works in a stock Docker container, default seccomp and AppArmor** | **yes [M]** |
| spawn cost | ~2.7 ms (80 ms for 30 spawns) **[M]** |

**End to end.** The same write-capable `claude` turn as §2, under both the C
helper and under `landrun`: `EACCES: permission denied` from the `Write` tool,
`Permission denied` from `Bash`, turn completed, file not created. **[M]**

**The gotcha that will bite first: `/dev` must be on the write allowlist.**
Without it `> /dev/null` fails, which breaks essentially every shell command —
including `git status` and `ls` as usually written. Measured: the first probe
run failed with `cannot create /dev/null: Permission denied` on six separate
lines before `/dev` was added. **[M]**

**Landlock is strictly better than bubblewrap for this particular job:** nothing
to configure on Ubuntu, works in containers, ~4× cheaper per spawn, and it
composes with itself. Its one structural cost is that the restriction must be
applied in the child between fork and exec, and the JVM gives no hook there — so
orca must launch through a helper program. `landrun` removes the need to write
one, at the price of another operator dependency.

---

## 4. The blocker: both mechanisms break codex

codex ships its own bubblewrap-based sandbox and uses it for **every**
model-generated shell command (`-s read-only | workspace-write |
danger-full-access`). The strace in §1 shows it creating
`/tmp/codex-bwrap-synthetic-mount-targets-<uid>/` and a `/newroot` tree. **[M]**

Wrapping it in either sandbox breaks that: **[M]**

| orca wrapper | what codex's shell tool reports |
|---|---|
| bubblewrap | `bwrap: No permissions to create a new namespace…` |
| Landlock | `bwrap: Failed to make / slave: Operation not permitted` |

Every shell command fails; the turn continues and reports the error, so this is
loud rather than silent, but codex is unusable.

For Landlock the cause is documented and not host-specific: the kernel's
Landlock page says "Threads sandboxed with filesystem restrictions cannot modify
filesystem topology, whether via `mount(2)` or `pivot_root(2)`". **[V]** Traced
directly: `clone(CLONE_NEWNS|CLONE_NEWUSER)` succeeds, then
`mount(NULL, "/", NULL, MS_REC|MS_SILENT|MS_SLAVE, NULL) = -1 EPERM`. **[M]**
Reproduced inside a Docker container with AppArmor unconfined, which rules out
AppArmor as the cause. **[M]**

This is the decisive fact. codex's `ReadOnly` is the **only** backend cell where
`Hard` is currently defensible without qualification (`--sandbox read-only`,
`CodexArgs.scala:134`). A generic orca wrapper would have to exempt it — and
once there is one exemption, plus a per-backend write allowlist (§1), plus a
per-backend "does this one self-sandbox" question, the generic wrapper is no
longer generic. It is per-backend work with a native dependency attached.

opencode's binary contains `landlock` strings **[M, grep only]**, so it may
self-sandbox as well; Landlock nests cleanly (§3), so that combination should be
fine, but it was not tested. **[U]**

---

## 5. What the backends already ship

**codex** — `--sandbox read-only`, already emitted by orca for `ToolSet.ReadOnly`
(`CodexArgs.scala:134`). bubblewrap on Linux, Seatbelt on macOS. Nothing to do.
**[V]**

**claude 2.1.222** — a full sandboxed-Bash feature, configured in
`settings.json` and settable per invocation with `--settings <file>`: **[V]**

- `sandbox.enabled`, `sandbox.failIfUnavailable`
- `sandbox.filesystem.allowWrite` / `denyWrite` / `allowRead` / `denyRead`
- `sandbox.allowUnsandboxedCommands` (`false` = strict; the model's
  `dangerouslyDisableSandbox` escape hatch is ignored), `sandbox.excludedCommands`
- bubblewrap + `socat` + a seccomp filter on Linux; Seatbelt on macOS; no
  native Windows
- "These paths are enforced at the OS level, so all commands running inside the
  sandbox, including their child processes, respect them."

Three measured caveats, and they matter:

1. **It covers Bash only.** The docs are explicit: "Read, Edit, and Write use
   the permission system directly rather than running through the sandbox."
   **[V]** That is not fatal here — Bash escaping `plan` mode is precisely the
   observed hole — but it means the guarantee is "no shell writes", not "no
   writes".
2. **It cannot make its own working directory read-only.** With
   `denyWrite: ["<cwd>"]`, sandbox startup itself fails:
   `bwrap: Can't create file at <cwd>/.gitconfig: Read-only file system`, on
   every command including `ls`. Adding an `allowWrite` carve-out for that exact
   path did not help. **[M]** Since orca runs agents with `cwd` = the repo, this
   is the exact configuration orca would want and it does not work as written.
3. **It did not start at all on this host.** With `denyWrite` on a subdirectory
   instead, every command failed with `apply-seccomp: write /proc/self/setgroups
   (nested userns is capability-restricted; caller must provide CAP_SYS_ADMIN)`.
   **[M]** The likely cause is that the shipped Ubuntu profile is
   `bwrap-userns-restrict`, while the docs ask for a separate
   `/etc/apparmor.d/bwrap` with `flags=(unconfined)`; the seccomp helper is its
   own binary and is not covered by the restricted profile. **Not verified** —
   installing the documented profile needs root and loosens AppArmor, so it was
   not done. **[U]**

  One good sign: `failIfUnavailable: true` behaved correctly. With `socat`
  missing, claude refused to start rather than running unsandboxed —
  "sandbox required but unavailable … refusing to start without a working
  sandbox". **[M]** That is the honest-failure property orca wants.

Also measured: claude writes `<cwd>/.claude/.cc-writes` from its own process,
outside the sandbox — consistent with caveat 1. **[M]**

**gemini** — has `-s, --sandbox` (container-based). Not investigated. **[U]**
**opencode** — `landlock` strings in the binary, nothing documented checked. **[U]**
**pi** — no sandbox strings found. **[M, grep only]**

---

## 6. macOS — read, not run

**No macOS machine was available. Nothing in this section was executed.** It is
held to lower confidence than §§1–5 throughout.

**`sandbox-exec` / Seatbelt.** The only documented way to apply a Seatbelt
policy to an arbitrary process without an Xcode project or App Store enrolment.
Deprecated — it prints a deprecation warning to stderr on every call — but still
functional, and `sandbox_init()` still ships and is used by Apple's own apps and
by Chromium. Apple has published **no removal timeline and no replacement** for
sandboxing arbitrary non-App-Store processes; an issue asking Apple exactly this
(`apple/containerization#737`) is unanswered. **[V, third-party docs]**

Mechanically it fits: children inherit the profile, so a shell launched under it
keeps its subprocesses inside the boundary. Profiles express what is needed —
`(deny default)`, then `(allow file-read* (subpath …))` for the world and
`(allow file-read* file-write* (subpath …))` for the allowlist, with denies
emitted last because evaluation is last-match-wins. Baseline allows for
`/usr/lib`, `/System` and `/Library` are needed or dynamic linking fails. SIP
blocks writes to protected system paths regardless of the profile.
**[V, third-party docs]**

**The risk is the profile, not the mechanism.** A profile that starts
`(allow default)` is a denylist wearing a sandbox's name. Antigravity shipped
one: an attacker mounted a devfs volume, built a fake `.app` on it to dodge
provenance tagging, launched it through Launch Services, and got a bash outside
the sandbox. **[V, published writeup]** An orca-authored profile would be
orca's to get right, on a platform its CI cannot exercise.

**App Sandbox entitlements: dead on arrival.** Entitlements require code
signing, and orca does not build or sign `claude`, `codex`, `gemini`,
`opencode` or `pi`. Re-signing someone else's binary to add entitlements
invalidates its notarisation and is not something a CLI can do on a user's
machine per install. **[I]**

**Newer options.** None found that cover this case. Apple's Containerization
framework is a VM boundary, not a per-process filesystem policy; Endpoint
Security needs an Apple-granted entitlement. **[U]** — searched, not exhaustive.

**What this leaves.** The macOS story is entirely "use the backend's own
Seatbelt profile": both claude and codex already ship one. If orca wrote its
own, it would own a deprecated interface plus a profile it cannot test.

---

## 7. Candidates ruled out quickly

- **seccomp filters alone.** Filter on syscall numbers and register values, not
  on resolved paths. A path argument is a pointer the kernel may not have
  resolved yet, so `openat("…/src/x.scala", O_WRONLY)` cannot be decided safely
  in a BPF filter. Useful as a layer under a path-aware mechanism — which is how
  claude uses it — never as the mechanism. **[I]**
- **User namespaces with read-only bind mounts, by hand.** This is what
  bubblewrap is. Reimplementing it in orca buys nothing and loses the AppArmor
  profile distributions already ship for `bwrap`. **[I]**
- **overlayfs.** Would let writes "succeed" into an upper layer and vanish. That
  hides the failure from the agent instead of surfacing it, and the Landlock
  docs warn that policy on an overlay layer does not carry to the merged
  hierarchy. **[V]** Worse than a clear `EACCES`.
- **Containers.** A real boundary, but it moves the whole run into a container:
  images, credential mounts, network, IDE and git integration. Out of proportion
  to "reviewers should not write files", and §2 shows bubblewrap does not work
  inside one anyway without disabling its hardening.

---

## 8. If it is ever built

The spawn seam is single and clean: `OsProcCliRunner.spawnPiped` and `.run`
(`subprocess/OsProcCliRunner.scala:21-58`) are the only places argv reaches the
OS, so a wrapper is an argv prefix in one file. **[V]**

Per-role expression is the missing piece. `AgentConfig` (`agents/AgentConfig.scala:7-36`)
carries `tools: ToolSet` but nothing path-shaped, and `ToolSet` is a three-value
capability tier, not a policy. A sandbox needs a workspace path, a per-backend
write allowlist and an opt-out — a new field, not a new `ToolSet` case. **[V]**

Only under all of these:

1. **Landlock, not bubblewrap** (§3). Via `landrun` if the extra dependency is
   acceptable, otherwise a small helper orca builds per arch.
2. **Skipped for codex** (§4), which already has the property natively.
3. **Off by default, on per role**, and failing loudly when unavailable rather
   than silently running unsandboxed — the property claude's
   `failIfUnavailable` gets right (§5).
4. **The write allowlist measured per backend first** (§1), including
   `<workDir>/.orca/cache/` for pi, `~/.claude.json` for claude, and `/dev` for
   everything.
5. **Linux only.** macOS via each backend's own sandbox (§6). An orca-authored
   Seatbelt profile is not worth owning.

### Correction to make regardless of this proposal's fate

`EnforcementTableTest.scala:46-51` asserts `ReadOnly → Hard` for all five
backends, and `AGENTS.md` renders that table. For claude that is measurably
false (`09-diff-vs-coordinates.md:83-101`: 199 `Bash` calls, zero denials, one
file write and one `scala-cli` run). gemini, opencode and pi were not checked
here. **[U]** Whether or not a sandbox is ever built, the table should say what
is true — that is the same reasoning that is retiring `withReadOnly`.

---

## 9. What remains unknown, and the experiment for each

1. **What gemini and opencode need to write.** *Experiment:* the §1 strace
   recipe, ~10 minutes each.
2. **Does claude's own sandbox work once the documented AppArmor profile is
   installed?** (§5 caveat 3.) *Experiment:* install `/etc/apparmor.d/bwrap`
   with `flags=(unconfined)`, reload, re-run. Needs root. If it works, §5
   caveat 2 (cwd cannot be denied) is the only remaining blocker and is worth
   reporting upstream.
3. **Does bubblewrap's descendant guarantee hold on a host without AppArmor?**
   (§2.) The kernel documentation says yes; not tested. *Experiment:* a
   non-Ubuntu Linux host, repeat the nested-`unshare`/`bwrap` matrix.
4. **Does `landrun --rox /` cost anything at scale?** The 2.7 ms figure is for
   `/bin/true` with four rules. *Experiment:* time a real reviewer fan-out with
   a full allowlist.
5. **Everything in §6.** No macOS was available. *Experiment:* on macOS, write a
   `(deny default)` profile, run a write-capable claude turn under
   `sandbox-exec`, and confirm both that writes fail and that the turn still
   completes. Until that is run, macOS should be treated as unanswered rather
   than as viable.
