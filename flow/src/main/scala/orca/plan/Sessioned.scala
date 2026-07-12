package orca.plan

import orca.agents.{BackendTag, Chat}

/** A planning-phase result paired with the (ephemeral) [[Chat]] that produced
  * it.
  *
  * Every `Plan.autonomous.*` / `Plan.interactive.*` operation returns one of
  * these so the caller can choose to continue the same conversation for the
  * downstream implementation phase (the agent keeps the context it built up
  * while planning / assessing / triaging) or discard it — `.value` — and mint a
  * durable session via `agent.session(name, seed)`. The chat is in-run only, so
  * a continuation this way does not survive a crash/resume; every shipped
  * example takes `.value`.
  *
  * Autonomous planning runs the planning TURN restricted (`NetworkOnly`), but
  * the returned chat is bound to the base agent, so a later `chat.run(task)`
  * continues the same conversation with write access restored — the restriction
  * applied only to the planning turn, not to the thread.
  *
  * Destructure at the call site:
  *
  * {{{
  * val Sessioned(chat, plan) = Plan.autonomous.from(userPrompt, claude)
  * }}}
  */
case class Sessioned[B <: BackendTag, +A](chat: Chat[B], value: A)
