# Custom reviewers

A reviewer is a prompt saying what to look for, paired with a read-only agent.
Orca ships eight: code-functionality, test, readability, code-structure,
simplicity, performance, security and scala-fp. Add your own, or retune a
shipped one, with a Markdown file; no code changes.

## Where reviewers come from

Three tiers, read once per [attempt](../glossary/users.md#flows-and-runs),
before Orca changes anything in the repository:

| Tier | Location | Scope |
|---|---|---|
| project | `.orca/reviewers/*.md` | committed with the repository |
| global | `~/.config/orca/reviewers/*.md` (`$XDG_CONFIG_HOME/orca/reviewers/`) | your own, in every project |
| built-in | shipped with Orca | the eight above |

The tiers merge into one **catalog**. A reviewer's name is its filename stem,
compared case-insensitively: `.orca/reviewers/orca.md` is the reviewer `orca`.
Project beats global beats built-in. A file with the same name as a reviewer
in a lower tier replaces it, in the same position. New names are appended,
sorted by name.

Flows take reviewers from the catalog in two rosters: `allReviewers` (every
reviewer) and `minimalReviewers` (code-functionality, readability and test).
A new name joins both. A shadowing file runs only where the shipped reviewer
runs: `scala-fp` is not in `minimalReviewers`, so
`.orca/reviewers/scala-fp.md` retunes it for this project without changing
that roster. The [reviewer picker](../authoring/review.md) still chooses from
the roster per task.

The run lists what the project and global tiers contributed:

```text
discovered reviewers: orca (project); scala-fp (project, shadows built-in)
```

## File format

Frontmatter plus a body, the same shape the shipped reviewers use:

```markdown
---
description: Checks the project's own layering rules.
files: \.scala$
---

## Scope

Review only the layering of the changed files...
```

- `description:` is required and must be a single line. The reviewer picker
  uses it to choose reviewers for a task. A YAML block scalar (`>`, `|`, `>-`,
  `|-`) or a value wrapped onto the next line aborts the run.
- `files:` is optional: a regex matched against each changed path. The
  reviewer is only offered to the picker when the change touches a matching
  file, unless nothing is known about the change set. Of the shipped
  reviewers, only `scala-fp` declares one.
- The body is the reviewer's system prompt.
- A `name:` key is ignored.

## Validation

`README.md` and any `_`-prefixed file are treated as documents and skipped.
Every other `.md` file must be a valid reviewer. Any of these aborts the run
before Orca changes the repository; all bad files are reported at once:

- a missing or unterminated frontmatter block
- a missing `description:`
- an empty body
- an invalid `files:` regex
- two files claiming one name

Orca fails hard because a silently dropped reviewer would look like a clean
review. A symlink in `.orca/reviewers/` also aborts: that directory comes from
a repository Orca did not write. Symlinks in the global tier are allowed; that
is your own config.

## Using reviewers from a flow

`allReviewers(agent)`, `minimalReviewers(agent)` and `reviewerCatalog` are
described in [Review and fix loops](../authoring/review.md#rosters).
