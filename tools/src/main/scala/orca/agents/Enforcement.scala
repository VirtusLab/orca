package orca.agents

/** How strongly a backend enforces the restriction a `(ToolSet, AutoApprove)`
  * combination requests. For the read-only tiers the restriction is "no
  * edits/shell"; for `Full` it is the approval policy itself.
  *
  *   - Hard — mechanically blocked (permission mode, sandbox, tool allowlist).
  *     The gated boundary may sit at a documented SUPERSET of the request; the
  *     cell's `rationale` says so where it does.
  *   - SandboxApprox — approximated by a coarser sandbox; semantics widened.
  *     The line from a `Hard` cell with a documented superset is whether orca
  *     can name the boundary the agent is held to: claude's additive allowlist
  *     can (defaults ∪ the list), codex's swap to a whole-workspace sandbox
  *     cannot.
  *   - PromptOnly — only the prompt forbids it; the tools can physically do it.
  *     [[orca.backend.SystemPromptComposer.ReadOnlyTurn]] is what puts that
  *     prose on every read-only turn.
  *   - Ignored — not encoded at all; behaviour depends on backend/server
  *     configuration outside orca's control.
  *
  * This is the canonical statement of what the levels mean; the per-backend
  * mapping is machine-checked in `runner/.../EnforcementTableTest.scala`, and
  * per-cell rationale lives in each backend's `*Args.enforcementCell`.
  */
enum Enforcement:
  case Hard, SandboxApprox, PromptOnly, Ignored

/** A backend's answer for one cell of the enforcement matrix: the level, and
  * why it is that level. One value rather than two, so a backend whose flags
  * change cannot have its level updated while a stale reason stays beside it.
  *
  * `rationale` is a single unwrapped sentence or two naming the mechanism (the
  * flag, sandbox, or absence of one) that produces `level`. It is the only home
  * for that text: AGENTS.md's table is rendered from the levels alone, and
  * [[EnforcementNotice]] puts the rationale in the log rather than in the
  * user-facing line.
  */
case class EnforcementCell(level: Enforcement, rationale: String)

/** Whether a turn starts a backend session or continues one — the second axis
  * of the enforcement matrix, alongside `(ToolSet, AutoApprove)`. It matters
  * because a backend may put its restriction flags on the spawn only: codex
  * `exec resume` rejects `--sandbox`/`--full-auto`, so a resumed turn runs in
  * the sandbox its session was created with, whatever tier the caller asked
  * for.
  *
  * Derived from [[orca.backend.Dispatch]] by dropping the wire ids this
  * classification has no use for.
  */
enum TurnDispatch:
  case Fresh, Resumed
