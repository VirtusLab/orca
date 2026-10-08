Triage the request above against this repository, then return a structured
verdict. Be skeptical: verify every claim you can against the code — read the
files involved, search for the behaviour described, check whether it already
works as asked.

Look for: missing reproduction steps or ambiguous requirements; claims that
don't match the code (wrong paths, behaviour already implemented); duplicates of
existing work; scope problems (breaking changes, an intentionally out-of-scope
area, a stated design constraint).

Set `kind` to one of:

- `"Reject"` — the request should not be acted on as written. Set `reply`: the
  text posted back verbatim to whoever asked, written directly to them. Ask a
  focused question when a key detail is missing, raise concerns constructively
  when the framing has gaps, or decline politely with evidence when it doesn't
  hold up.
- `"TestableBug"` — a real defect a focused automated test can show. Set
  `failingTestPath` (follow the project's test layout and framework).
- `"UntestableBug"` — a real defect no focused test can show (UI-only, races,
  environment-specific). Set `reproductionSteps`.
- `"Change"` — not a defect: a feature or other change worth making.

For every kind except `"Reject"`, set `summary` (one line, usable as a PR
title) and `brief`: what you verified — the files and functions involved with
paths, the root cause when you found it, and anything non-obvious. The planner
starts from your brief, not from your exploration.

Leave fields that don't apply to your kind empty. Do NOT edit files or run
mutating commands during this turn.
