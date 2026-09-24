package orca.plan

import orca.agents.Chat

/** A planning-phase result paired with the (ephemeral) [[orca.agents.Chat]]
  * that produced it. "Sessioned" names this ephemeral pairing specifically — it
  * is not a durable session; see `agent.session(name, seed)` for that.
  *
  * Every `Plan.{autonomous,interactive}.*` operation returns one of these, so
  * the caller can continue the same conversation into the implementation phase
  * (the agent keeps the context it built while planning) or discard it —
  * `.value` — and mint a durable session via `agent.session(name, seed)`. The
  * chat is in-run only, so a continuation does not survive a crash/resume.
  *
  * Autonomous planning restricts the planning TURN (`NetworkOnly`), but the
  * returned chat is bound to the base agent, so a later `chat.run(task)`
  * continues with write access restored.
  *
  * Only the library pairs a value with a chat, so `.reviewed()` always
  * continues the conversation that produced the plan. Destructure at the call
  * site:
  *
  * {{{
  * val Sessioned(chat, plan) = Plan.autonomous.from(userPrompt, claude)
  * }}}
  */
final case class Sessioned[+A] private[orca] (chat: Chat[?], value: A)
