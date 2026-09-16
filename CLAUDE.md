# Orca — Claude Code instructions

Read [AGENTS.md](AGENTS.md) for internals, architecture, and coding
conventions, and [CONTRIBUTING.md](CONTRIBUTING.md) for build/test commands
and the local-testing recipes.

Before writing, modifying, or reviewing any Scala code in this repository,
invoke the `direct-style-scala` skill (via the Skill tool). This applies to
subagents dispatched to implement or review a task: load the skill first,
then start the work.

When a change would add mutable state — a `var`, a mutable collection or an
`AtomicReference` field — research the alternatives in a separate agent and
have a second agent critique the recommendation before implementing either.
AGENTS.md's review-derived rules say what the result must record.
