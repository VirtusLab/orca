---
description: Enforces this repository's own conventions, as recorded in AGENTS.md — forge neutrality, mutable-state discipline, capability tokens, comment content, run/attempt, backend and review vocabulary, and the modelling rules its reviews keep rediscovering. Include when: any Scala, flow-script, prompt, doc, or `.orca/settings.properties` change in this repository.
files: \.(scala|sc|md|properties)$
---

## Scope

This repository's own rules only. Everything a reviewer would say about any
codebase — correctness, structure, naming, functional-programming style,
performance, tests — belongs to the shipped reviewers running beside you.

## Aspects

- **Forge neutrality**: interfaces and persisted documents name git concepts,
  never a forge; a forge-specific type stays in the package that talks to it.
- **New mutable state is a design question**: a new `var` field, mutable
  collection or `AtomicReference` needs the task description or a comment at
  the declaration to name the alternatives (actor, method-local state, an
  `AtomicReference` over an immutable value) and why each was rejected —
  report the missing rationale, not the state itself. Actor-held state and an
  `AtomicReference` over an immutable value updated by a pure function are the
  sanctioned forms.
- **No back-compat machinery**, and no default values on domain or persisted
  fields.
- **Failure types**: a recoverable `Either[E, T]` has `E <: OrcaFlowException`;
  system failures throw.
- **Review vocabulary**: a reviewer, the lint gate or a `ReviewCheck` reports
  a `finding` (`ReviewFinding`); `DeclinedFinding` is the fixer refusing one,
  with a reason it wrote; `OpenFinding(s)` is what the run leaves unresolved,
  each with an `OpenReason`. `issue` means a GitHub issue. Don't name the open
  set after one of its reasons, and don't rename inside a dated record.
- **Run and backend vocabulary**: a run is one prompt across every process
  that resumes it, an attempt is one process — never call a process a run. The
  user's input is the prompt, a stack command is a gate, and "task" names only
  a plan task. Call, turn, message, settle, conversation and dispatch mean what
  AGENTS.md's "Backend vocabulary" says — in identifiers, file names, output and
  prose.
- **Comment content**: no plan or epic labels, no teaching Scala mechanics; in
  `flows/*.sc`, only facts about that file.
- **Capability discipline**: `InStage.unsafe`/`WorkspaceWrite.unsafe` only in
  `RuntimeInStage` and tests; never drop a `(using InStage)` or `(using
  WorkspaceWrite)` to make code compile; `WorkspaceWrite` never crosses a fork;
  a new gated write calls `WorkspaceWrite.check` first.
- **Writes under `.orca/`**: a whole-file write replaces an `OrcaDir.OrcaFile`;
  any other write goes inside an `OrcaDir.ensure*` directory, with `os.write`
  over `os.write.over`. A new file gets a row in AGENTS.md's "What a run writes
  to disk".
- **Subprocesses capture stderr** — `QuietProc.call` or a `CliRunner`; a
  `spawnPiped` caller drains `stderrLines` as well as `stdoutLines`. Raw
  `os.proc` with `os.Inherit` only in the `shell/` terminal handoffs and
  `TtyProbe`.
- **Listeners**: a listener backed by an Ox actor uses `ask`, never `tell`.
- **Protocol strings become enums**: parsed at the boundary into an enum
  (`Unknown(raw)` for unrecognised values) and matched exhaustively downstream,
  never compared to literals.
- **Modelling**: two or more adjacent parameters of one type whose order
  carries meaning (from/to, old/new, base/head) take named arguments at every
  call site, or a case class.
- **Invisible in a diff**: terminal escapes are written as explicit unicode
  escapes (`\u001b`), never raw bytes; code that generates code — prompts,
  skeletons, templates — is tested by compiling or running the artifact, not by
  substring assertions.
- **User-facing text**: a refusal names the next action, and a destructive
  automatic operation states its purpose.
- **Threat model**: agents are trusted but fallible — never report a finding
  whose only justification is what a malicious agent could do.
- **`.orca/settings.properties` is committed on purpose** — never flag it as an
  accidental commit.
