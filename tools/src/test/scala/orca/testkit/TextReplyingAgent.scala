package orca.testkit

import orca.agents.{BackendTag, ClaudeAgent}

import java.util.concurrent.ConcurrentLinkedQueue

/** Agent whose text turns record the prompt they were given and return a fixed
  * reply — the cheap model the runtime drafts commit messages with. Its backend
  * has no cheaper tier, so `cheap` runs on the same backend.
  *
  * Any stage that changes a file drafts a message, so a `TestFlowControl`
  * driving such a stage needs one of these as its lead.
  */
object TextReplyingAgent:
  def apply(
      reply: String,
      prompts: ConcurrentLinkedQueue[String] = ConcurrentLinkedQueue[String]()
  ): ClaudeAgent =
    TestAgent(
      ScriptedBackend.replying(BackendTag.ClaudeCode): turn =>
        prompts.add(turn.prompt): Unit
        reply
      ,
      name = "stubbed"
    )
