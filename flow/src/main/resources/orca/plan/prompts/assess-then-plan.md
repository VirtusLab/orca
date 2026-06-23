Your job in this turn is to **assess** the report above and then either reject
it or produce a development plan. Be skeptical: do not trust the report's
framing on its own. Verify any claim you can against the actual repository —
read the relevant files, run targeted searches, check whether the behaviour the
reporter describes matches what the code does.

Things to look for:

  - Missing reproduction steps or ambiguous requirements.
  - Claims about the code that don't match what the repo actually contains
    (wrong file path, function already removed, behaviour the code already
    implements).
  - Duplicates of an existing fix, feature, or open work.
  - Contradictions between the report and the repository state or other open
    issues.
  - Scope problems: the request would require breaking changes, touches an
    intentionally out-of-scope area, or violates a stated design constraint.

Then return one of:

  - `verdict: "proceed"` with a `plan` (the same plan shape used by the
    autonomous planner — epic id, description, ordered list of tasks, and a
    `brief`). Each task should be atomic (impl + tests together), independent of
    later tasks, shippable on its own, and small enough for one focused
    implementer turn. Fill the plan's `brief` field with a concise codebase
    briefing the implementing agents (who start from a fresh context) will rely
    on: the modules/files involved with paths, the key types and APIs to build
    on, the conventions to follow, and anything non-obvious you learned while
    verifying the report. Do NOT edit files or run code during this assessment
    turn; planning is the output.

  - `verdict: "reject"` with `rejectKind` and `rejectBody`. `rejectKind` is one
    of:

      * `"question"` — the report likely points at a real problem but a key
        detail is missing; `rejectBody` should be a focused follow-up question
        the reporter can answer.
      * `"critique"` — the report holds up but the proposed framing has gaps,
        wrong assumptions, or risks that should be raised before any fix lands;
        `rejectBody` should explain the concerns constructively.
      * `"rebuff"` — the report does not hold up against the repository (no
        such file, duplicate of a closed issue, contradicted by code, out of
        scope); `rejectBody` should be a polite, evidence-cited decline.

    `rejectBody` is the text that will be posted verbatim back to the reporter,
    so write it directly to them.

Fill in only the fields appropriate to your verdict: `plan` on proceed,
`rejectKind`+`rejectBody` on reject.
