package orca.plan

import orca.agents.{BackendTag, SessionId}

/** A planning-phase result paired with the agent session that produced it.
  *
  * Every `Plan.autonomous.*` / `Plan.interactive.*` operation returns one of
  * these so the caller can choose to continue the same conversation for the
  * downstream implementation phase (the agent keeps the context it built up
  * while planning / assessing / triaging) or discard the session and mint a
  * fresh one via `llm.newSession`.
  *
  * Autonomous planning runs read-only, but the returned `sessionId` is still
  * resumable: a later writable `llm.autonomous.run(task, sessionId)` reuses the
  * same conversation thread with write access restored — read-only applied only
  * to the planning turn, not to the thread.
  *
  * Destructure at the call site:
  *
  * {{{
  * val Sessioned(session, plan) = Plan.autonomous.from(userPrompt, claude)
  * }}}
  */
case class Sessioned[B <: BackendTag, +A](sessionId: SessionId[B], value: A)
