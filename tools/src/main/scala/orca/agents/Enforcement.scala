package orca.agents

/** How strongly a backend enforces the restriction a `(ToolSet, AutoApprove)`
  * combination requests. For the read-only tiers the restriction is "no
  * edits/shell"; for `Full` it is the approval policy itself.
  *
  *   - Hard — mechanically blocked (permission mode, sandbox, tool allowlist);
  *     the gated boundary may be a documented SUPERSET of the request, which
  *     the cell's `rationale` then names.
  *   - SandboxApprox — approximated by a coarser sandbox; semantics widened.
  *     Unlike a `Hard` superset, orca cannot name the boundary the agent ends
  *     up held to.
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

  /** Whether `this` promises less than `other`. The cases are declared
    * strongest first — a mechanical block, a mechanical block with widened
    * semantics, prose, nothing — so the declaration order IS the ranking, and a
    * new case has to be declared at its strength rather than appended.
    */
  def weakerThan(other: Enforcement): Boolean = ordinal > other.ordinal

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
  * because a resumed session keeps whatever sandbox it was created with unless
  * the backend re-applies one, and not every restriction has a knob to re-apply
  * on the narrower resume argv.
  *
  * Derived from [[orca.backend.Dispatch]] by dropping the wire ids this
  * classification has no use for.
  */
enum TurnDispatch:
  case Fresh, Resumed
