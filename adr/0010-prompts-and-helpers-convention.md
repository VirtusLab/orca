# ADR 0010: Prompts and helper-function convention

## Context

Orca ships two kinds of LLM-using surface:

1. **Per-call wrappers** — the `Prompts` trait (autonomous, interactive, retry).
   These wrap *every* structured LLM call regardless of domain. Configured
   flow-wide via `flow(prompts = ...)`. Out of scope for this ADR.

2. **Domain helpers** — flow-script-friendly functions that bundle a
   domain-specific multi-step pattern with a default LLM brief: planning
   (`Plan.interactive.*`, `Plan.autonomous.*`), review (`reviewAndFixLoop`,
   `lint`, `ReviewerSelector.llmDriven`), the canonical reviewer set
   (`defaultReviewers`).

Before this ADR, prompts in domain helpers lived in three different shapes:

- A public `object ReviewerPrompts` with named `val`s — used by
  `defaultReviewers` and easy to override by composing a custom list.
- A public `val ExtendedPlan.SchemaDescription` interpolated into a private
  inline string inside `loadOrGenerate`.
- Private `val FixInstructions` / `SelectReviewersInstructions`, plus an
  inline string in `lint`'s body — no override seam at all; callers had to
  fork the helper.

Inconsistent: a flow author trying to retune one of these prompts had to grep
for it, sometimes finding a public val to reference, sometimes a private one
to reproduce. The same problem showed up in the 01-simple example, where the
planning brief was inline at the call site only because the library didn't
expose a `plan(...)` helper at all.

## Decision

For every domain helper that bundles an LLM brief, follow this pattern:

1. **Place a public `object XxxPrompts` beside the helper**, in the same
   package. Each entry is a complete instruction block as a `val`.
   - `orca.plan.PlanPrompts` — `Planning`
   - `orca.review.ReviewPrompts` — `Fix`, `SelectReviewers`, `SummarizeLint`
   - `orca.review.ReviewerPrompts` — per-reviewer system prompts
     (`Performance`, `Readability`, …)
   - For per-call wrappers, the equivalent stays the `Prompts` trait + flow-
     level injection — that's a different layer (every LLM call, not one
     domain helper).

2. **Helpers take the prompt as a default-valued parameter:**

   ```scala
   def lint(
       command: String,
       llm: LlmTool[?],
       instructions: String = ReviewPrompts.SummarizeLint
   )(using FlowContext): ReviewResult
   ```

   Three call shapes fall out for free:
   - I want defaults → `lint(cmd, claude.haiku)`
   - I want a different brief → `lint(cmd, claude.haiku, instructions = "...")`
   - I want to extend → `instructions = ReviewPrompts.SummarizeLint + "\n\n..."`

3. **For helpers that produce many configurations** (e.g.
   `defaultReviewers`), the same `XxxPrompts` object hosts the prompts; the
   override mechanism is composing your own list of reviewers using those
   vals plus your own. There's no `instructions = …` parameter because the
   helper isn't a single LLM call.

4. **Helper functions live as free functions (or companion methods) in the
   same package as the prompts** — `orca.plan.*`, `orca.review.*`. A flow
   script imports the helper and the override seam is reachable without
   chasing across modules.

## Consequences

**Positive**

- A flow author looking at any helper can see immediately *that* it has a
  default prompt and *where* the prompt lives. Override is a named arg.
- Tests that exercise prompt wording can pin the exact string by referencing
  the `XxxPrompts` val instead of duplicating it.
- The 01-simple example collapses from a multi-line inline `planningPrompt`
  + manual `claude.resultAs[Plan].interactive(...)` to a one-line
  `Plan.interactive.from(userPrompt, claude)`.

**Negative**

- A small public object per domain that has prompts. Acceptable; the
  alternative (one library-wide prompts object) would couple unrelated
  packages.
- `ReviewPrompts` and `ReviewerPrompts` sit in the same package with similar
  names but different roles (operational instructions vs reviewer system
  prompts). Doc comments on each make the distinction explicit.

## Implementation notes

Companion-object helpers (`Plan.interactive.from`, `Plan.autonomous.from`,
`Plan.interactive.loadOrGenerate`, `Plan.autonomous.loadOrGenerate`) versus
free functions (`reviewAndFixLoop`, `lint`) is a judgement call: when the
helper's return type is a domain type defined nearby, putting it on the
companion reads naturally; when it's a generic operation that *uses* multiple
types,
keep it as a free function. Both forms participate in the same prompt
convention.
