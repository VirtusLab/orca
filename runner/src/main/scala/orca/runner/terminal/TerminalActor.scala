package orca.runner.terminal

import orca.events.{OrcaEvent, OrcaListener}
import ox.{Ox, forever, forkDiscard, sleep}
import ox.channels.{Actor, ActorRef, BufferCapacity}

import java.io.PrintStream
import java.util.concurrent.Semaphore
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.control.NonFatal

/** The production terminal: one Ox actor owns the [[TerminalOutputState]] and
  * the [[TerminalEventRenderer]] writing to it, so an event's line is formatted
  * and written in one step, on one thread.
  *
  * Every call is an `ask`, per the `OrcaListener` contract. The scope must
  * outlive every caller.
  *
  * `promptGate` (fair) is held from before the header-and-suspend ask until
  * after the resume-ask, so a second `prompt` blocks until the first
  * transaction — drain and redraw included — has fully closed.
  */
private[terminal] final class TerminalActor private (
    actor: ActorRef[TerminalActor.Owned]
) extends TerminalOutput:
  private val promptGate = new Semaphore(1, true)

  /** Renders every event it receives. A `val`: the dispatcher quarantines a
    * listener by identity, and names its class when it does.
    */
  val listener: OrcaListener = new RenderingListener

  private final class RenderingListener extends OrcaListener:
    def onEvent(event: OrcaEvent): Unit =
      actor.ask(_.renderer.render(event))

  /** The indent of the innermost open stage, for prompt text. */
  def currentIndent: String = actor.ask(_.renderer.currentIndent)

  def log(text: String): Unit = actor.ask(_.output.log(text))
  def setStatus(label: Option[String]): Unit =
    actor.ask(_.output.setStatus(label))
  def prompt[A](header: String)(readUser: () => A): A =
    promptGate.acquire()
    try
      // An interrupted ask still leaves the suspend queued on the actor, so the
      // ask sits under the `finally`; resume is a no-op when not suspended.
      // Header and suspend are one ask, so no event lands between them.
      try
        actor.ask: owned =>
          owned.output.log(header)
          owned.output.suspend()
        readUser()
      finally actor.ask(_.output.resume())
    finally promptGate.release()

  /** Close-time throws are swallowed so they don't mask an upstream failure. */
  def close(): Unit =
    try actor.ask(_.output.close())
    catch case NonFatal(_) => ()

private[terminal] object TerminalActor:

  /** What the actor owns; `renderer` writes to `output`. */
  final class Owned(
      val output: TerminalOutputState,
      val renderer: TerminalEventRenderer
  )

  /** Starts the actor, and the spinner's animator fork when `animated`, in the
    * given scope.
    */
  def start(
      out: PrintStream,
      useColor: Boolean,
      animated: Boolean,
      workDir: Option[os.Path],
      framePeriod: FiniteDuration = 100.millis
  )(using Ox, BufferCapacity): TerminalActor =
    val output = new TerminalOutputState(out, useColor, animated)
    val actor = Actor.create(
      Owned(output, new TerminalEventRenderer(output, useColor, workDir))
    )
    if animated then
      forkDiscard:
        forever:
          sleep(framePeriod)
          // A `tell`, so the animator never waits on a busy actor.
          actor.tell(_.output.tick())
    new TerminalActor(actor)
