package orca

// TODO: add short comment on what this is
trait Interaction:
  def listeners: List[OrcaListener]
  def runInteractive(handle: InteractiveHandle[?]): Unit
