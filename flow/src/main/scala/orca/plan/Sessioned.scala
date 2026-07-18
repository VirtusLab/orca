package orca.plan

import orca.agents.{BackendTag, Chat}

/** A planning-phase result paired with the (ephemeral) [[Chat]] that produced
  * it.
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
  * Destructure at the call site:
  *
  * {{{
  * val Sessioned(chat, plan) = Plan.autonomous.from(userPrompt, claude)
  * }}}
  */
case class Sessioned[B <: BackendTag, +A](chat: Chat[B], value: A)
