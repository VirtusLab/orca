# Task C2 Report: OS-backed ProgressStore

## Implementation

### Files Created

**`flow/src/main/scala/orca/progress/ProgressStore.scala`**

- `trait ProgressStore` with `load(): Option[ProgressLog]`, `writeHeader(h)(using InStage): Unit`, `appendEntry(e)(using InStage): Unit`.
- `object ProgressStore` with `def default(workDir, userPrompt): ProgressStore` factory and private `hashPrompt` (mirrors `Plan.hashUserPrompt` — first 6 bytes of SHA-256 as 12 hex chars, no cross-call).
- `private class OsProgressStore(path: os.Path)` — the implementation:
  - `load`: returns `None` if file absent; wraps parse in `try/catch NonFatal` to return `None` on corrupt JSON.
  - `writeHeader`: writes fresh `ProgressLog(header, Nil)` via `os.write.over(..., createFolders = true)`.
  - `appendEntry`: loads existing log, upserts by `id` using `indexWhere` + `.updated` (in-place replace) or `:+` (append), writes back.
  - `hashPrompt`: `java.security.MessageDigest.getInstance("SHA-256")`, take first 6 bytes, `f"${b & 0xff}%02x"` per byte.

**`flow/src/test/scala/orca/progress/ProgressStoreTest.scala`**

Six targeted munit tests using `os.temp.dir()` and `given InStage = InStage.unsafe`:
1. `writeHeader` → `load` returns header with empty entries.
2. `appendEntry` same id (upsert, last-wins) + different id (appends) → two entries in correct order.
3. `load` on absent file → `None`.
4. `load` on a corrupt file (`"not json {{{"`) → `None`, no throw.
5. Path shape: `progress-[0-9a-f]{12}.json` filename validated with regex.
6. Path is deterministic: same prompt yields same filename in two different `workDir`s.

## TDD Evidence

### RED
```
sbt --client "flow/testOnly orca.progress.ProgressStoreTest"
[error] Not found: ProgressStore   (10 errors)
```

### GREEN (after implementation)
```
sbt --client "flow/testOnly orca.progress.ProgressStoreTest"
orca.progress.ProgressStoreTest:
  + writeHeader then load returns the header with empty entries 0.055s
  + appendEntry with same id upserts (last write wins), different id appends 0.002s
  + load returns None when no file exists 0.001s
  + load returns None for a corrupt file (no throw) 0.003s
  + default path is <workDir>/.orca/progress-<12hexchars>.json 0.001s
  + default path is deterministic for a given prompt 0.0s
[info] Passed: Total 6, Failed 0, Errors 0, Passed 6
```

Fixed one warning (unused `given` import in `JsonData` import line) before GREEN.

### Full suite
```
sbt --client "flow/test"
[info] Passed: Total 129, Failed 0, Errors 0, Passed 129
```

Zero warnings. Scalafmt applied (`scalafmtAll`).

## Self-review

- Trait and default impl match spec signatures exactly.
- Upsert semantics: `indexWhere` + `.updated` preserves position (task brief asks for "replace in place"); new id goes to end.
- Corrupt file: `NonFatal` catch is scoped only around `readFromString`, not around `os.read` (IO failures are unrecoverable).
- `(using InStage)` clauses are on both mutators; body does not use the token (per spec).
- `OsProgressStore` is `private` (not exported); only `ProgressStore` trait and `default` factory are public.
- `hashPrompt` is `private` in the companion; not exposed.
- `appendEntry` throws `IllegalStateException` if called before `writeHeader` (no log on disk). This is consistent with the task brief's "never two entries with one id" invariant and guards a programmer error; it is not a recoverable failure.

## Concerns

None. The implementation is minimal (no recovery/stash/git as specified). The `IllegalStateException` in `appendEntry` when called before `writeHeader` is intentional programmer-error signalling; if the stage runtime always calls `writeHeader` first, this branch is unreachable in production. This is acceptable for C2 scope.
