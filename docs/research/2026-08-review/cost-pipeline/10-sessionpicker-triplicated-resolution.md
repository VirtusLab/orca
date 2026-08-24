# `SessionPicker`'s three selector paths each re-implement the same Resume/disabled resolution

**Aspect**: conciseness  **Severity**: low

## Problem

`shell/src/main/scala/orca/shell/sessions/SessionPicker.scala:185-240` — `newestDurableSelection`,
`selectByIndex`, and `selectByName` each hand-roll the same three-step resolution: unwrap
`PickerRow.Resume`, check `disabledReason`, produce a contextualized `Left`. Compare:

```scala
// newestDurableSelection (:190-197)          // selectByIndex (:213-221)
choice.value match                            choice.value match
  case PickerRow.Resume(selection) =>          case PickerRow.Resume(selection) =>
    choice.disabledReason match                  choice.disabledReason match
      case Some(reason) =>                         case Some(reason) =>
        Left(s"can't resume the newest...")          Left(s"session $index isn't resumable...")
      case None => Right(selection)                case None => Right(selection)
```

`selectByName` repeats the shape a third time via a `collect` + tuple match (:227-240). Three copies
of one decision (~25 of the file's ~55 resolution lines), each with its own unreachable-`ShowMore`
handling.

## Proposed solution

One helper owning the Resume/disabled resolution; each selector keeps its lookup, its message
context, and its own `ShowMore` outcome — `newestDurableSelection`'s `ShowMore` branch is reachable
(with no durable lineages, the collapsed listing's head is an expander row) and carries a distinct
user-facing message, so it cannot be folded into an "unreachable" default:

```scala
/** Resolve a picker row: a Resume row yields its selection or the
  * `notResumable`-wrapped disabled reason; a ShowMore row yields `onShowMore`.
  */
private def resolveRow(
    choice: Choice[PickerRow],
    notResumable: String => String,
    onShowMore: => Either[String, SessionSelection]
): Either[String, SessionSelection] =
  choice.value match
    case PickerRow.Resume(selection) =>
      choice.disabledReason.map(notResumable).toLeft(selection)
    case PickerRow.ShowMore => onShowMore
```

- `newestDurableSelection`: `headOption.fold(Left("no sessions recorded yet"))(resolveRow(_, r => s"can't resume the newest session — $r", Left("no durable session to continue yet — see `orca continue --list`")))`.
- `selectByIndex`: `rows.lift(index - 1)` + `resolveRow(_, r => s"session $index isn't resumable — $r", Left(s"no session at index $index"))` — `ShowMore` stays unreachable there (`withoutExpanders`), fed the same fallback message it has today.
- `selectByName`: collect the matching Resume `Choice`s (whole choices, not `(selection, reason)` tuples), keep the Nil and ambiguous arms as they are, and apply `resolveRow(_, r => s"session '$name' isn't resumable — $r", <unreachable Left>)` to the single match.

Tests: pinned by existing shell tests (`ResumeCommandTest`, picker tests) — no assertions change;
pure refactor. In particular, "no durable session to continue yet" must still surface when only
one-shot sessions exist.

Must NOT change: the four user-facing messages (the three not-resumable messages AND
`newestDurableSelection`'s `ShowMore` message), the expanded/collapsed listing semantics
(`selectByName` still matches the collapsed listing), and `withoutExpanders`' filtering.

## Verification

**Verdict: CONFIRMED-REVISED.**

Checked SessionPicker.scala:185-240 — the three selectors and the quoted parallel shapes are exact.
The problem is real, but the original helper was wrong for `newestDurableSelection`: its `ShowMore`
branch is REACHABLE (with zero durable lineages and some one-shots, `sessionRows(runs, expanded =
false)`'s head is the one-shot expander row — `newestDurableSelection` does NOT go through
`withoutExpanders`) and carries a distinct required message ("no durable session to continue yet —
see `orca continue --list`"). The original `Left(notResumable("expander row"))` would have replaced
that with nonsense, violating the finding's own Must-NOT-change list. The ## Proposed solution above
is the corrected version (helper takes an explicit `onShowMore`).

Ordering: SessionPicker.scala is also rewritten by 05/06 — sequence after them.
