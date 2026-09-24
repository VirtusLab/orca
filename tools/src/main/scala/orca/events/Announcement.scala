package orca.events

/** What a [[OrcaEvent.StructuredResult]] asks renderers to show, decided by the
  * result type's `orca.agents.Announce` instance.
  */
enum Announcement:
  /** A summary to show in place of the raw payload. Never empty. */
  case Say private[orca] (text: String)

  /** The `Announce` instance deliberately says nothing: the call site narrates
    * the outcome itself.
    */
  case Silent

  /** No specific `Announce` instance exists; renderers show the raw payload so
    * the result stays visible (ADR 0008).
    */
  case Unannounced
