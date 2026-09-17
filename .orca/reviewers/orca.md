---
description: Enforces this repository's own conventions, as recorded in AGENTS.md — forge neutrality, mutable-state discipline, capability tokens, comment style, and the modelling rules its reviews keep rediscovering. Include when: any Scala or flow-script change in this repository.
files: \.(scala|sc)$
---

## Scope

This repository's own rules only. Everything a reviewer would say about any
codebase — correctness, structure, naming, performance, tests — belongs to the
shipped reviewers running beside you.

## Aspects

- **Forge neutrality**: interfaces and persisted documents name git concepts,
  never a forge; a forge-specific type stays in the package that talks to it.
- **New mutable state is a design question**: a new `var` field, mutable
  collection or `AtomicReference` needs the PR to say which alternatives it
  rejected — report the missing rationale, not the state itself, which
  `scala-fp` already covers. Actor-held state and test helpers are the
  sanctioned exceptions.
- **No back-compat machinery**, and no default values on domain or persisted
  fields; the two exceptions are ProgressLog/SessionRecord tolerant decoding
  and RunManifest additive changes.
- **Comments are present-tense facts**: no history ("no longer", "renamed
  from"), no plan or epic labels, no teaching Scala mechanics; in `flows/*.sc`,
  only facts about that file.
- **Capability discipline**: `InStage.unsafe`/`WorkspaceWrite.unsafe` only in
  `RuntimeInStage` and tests; never drop a `(using InStage)` or `(using
  WorkspaceWrite)` to make code compile; `WorkspaceWrite` never crosses a fork.
- **Writes under `.orca/`** go through `OrcaDir.ensureRoot`/`ensureCache`, and
  prefer `os.write` over `os.write.over`.
- **Subprocesses capture stderr** — `QuietProc.call` or a `CliRunner`.
- **Enums, not flags**: a domain mode is an enum, never a `Boolean` or a raw
  string compared to literals; two flags or `Option`s whose combinations
  include impossible states are one ADT. Protocol strings are parsed into an
  enum at the boundary (`Unknown(raw)` for unrecognised values) and matched
  exhaustively downstream.
- **Modelling**: three or more same-typed adjacent parameters — or two whose
  swap compiles — take named arguments or a case class; a wire field's absence
  is decided once, at decode, never re-defaulted per call site; one decision,
  one home, so display and summary code consumes the production resolver
  instead of mirroring the rule.
- **Invisible in a diff**: terminal escapes are written as explicit unicode
  escapes (`\u001b`), never raw bytes; code that generates code — prompts,
  skeletons, templates — is tested by compiling or running the artifact, not by
  substring assertions.
- **User-facing text**: a refusal names the next action, and a destructive
  automatic operation states its purpose.
- **Threat model**: agents are trusted but fallible — never report a finding
  whose only justification is what a malicious agent could do.
- **`.orca/settings.properties` is committed on purpose** — never flag it as an
  accidental commit; its `format`/`lint`/`test` values are shell commands orca
  runs, and those stay reviewable.

Do not re-report generic functional-programming style, structure, naming,
performance or test quality; those belong to the shipped reviewers.
