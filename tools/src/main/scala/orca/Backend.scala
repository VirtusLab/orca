package orca

enum Backend:
  case ClaudeCode
  case Codex

opaque type SessionId[B <: Backend] = String

object SessionId:
  def apply[B <: Backend](value: String): SessionId[B] = value
  extension [B <: Backend](id: SessionId[B]) def value: String = id
