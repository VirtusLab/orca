package orca.cli

import java.io.PrintStream
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

/** 4-line animated "thinking" indicator: a label on line 1 and an orca
  * breaching a wave below it. Uses ANSI cursor-up to redraw the four body lines
  * in place. `start` is safe to call repeatedly — a new label replaces the
  * previous one; `stop` clears the frame so subsequent terminal output doesn't
  * see the partially-drawn animation.
  *
  * With `useColor = true`, waves render cyan and the orca's dorsal and body
  * render bold white so the animation reads on a dark terminal without stealing
  * too much visual weight from the label.
  */
class OrcaSpinner(
    out: PrintStream,
    framePeriodMs: Long = 180L,
    useColor: Boolean = true
):

  import OrcaSpinner.{Frames, colorize, paintLabel}

  private val running = new AtomicBoolean(false)
  private val animator: AtomicReference[Option[Thread]] =
    AtomicReference(None)

  def start(label: String): Unit =
    // If already running, clear the previous frame so the new label lands
    // at a clean spot.
    if running.get() then stop()
    running.set(true)
    out.println(paintLabel(label, useColor))
    Frames.head.foreach(line => out.println(colorize(line, useColor)))
    out.flush()
    val t = new Thread(() => animate(), "orca-spinner")
    t.setDaemon(true)
    t.start()
    animator.set(Some(t))

  def stop(): Unit =
    if !running.getAndSet(false) then ()
    else
      animator.getAndSet(None).foreach(_.join(500))
      // Move cursor up 5 lines (label + 4 animation) and erase below.
      out.print("[5A[0J")
      out.flush()

  private def animate(): Unit =
    var idx = 0
    while running.get() do
      Thread.sleep(framePeriodMs)
      if running.get() then
        idx += 1
        val frame = Frames(idx % Frames.size)
        // Cursor up 4 lines (over the body), redraw each with clear-to-EOL.
        out.print("[4A")
        frame.foreach(line => out.println(s"[2K${colorize(line, useColor)}"))
        out.flush()

object OrcaSpinner:

  /** Each frame is exactly 4 lines of the same width so ANSI in-place redraw
    * leaves no stray characters. Frames depict a single orca breaching
    * left-to-right across the waves.
    */
  val Frames: Vector[Vector[String]] = Vector(
    Vector(
      "                                            ",
      "                                            ",
      "      __                                    ",
      "   ~~/_o>~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"
    ),
    Vector(
      "                                            ",
      "           __                               ",
      "          /_o>                              ",
      "   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"
    ),
    Vector(
      "                 __                         ",
      "                /_o>                        ",
      "                                            ",
      "   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"
    ),
    Vector(
      "                       __                   ",
      "                      /_o>                  ",
      "                                            ",
      "   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"
    ),
    Vector(
      "                                            ",
      "                             __             ",
      "                            /_o>            ",
      "   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"
    ),
    Vector(
      "                                            ",
      "                                            ",
      "                                  __        ",
      "   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~/_o>~~~~~~~"
    ),
    Vector(
      "                                            ",
      "                                            ",
      "                                            ",
      "   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"
    )
  )

  private val OrcaPattern = "(/_o>|__)".r
  private val WavePattern = "~+".r

  /** Apply fansi colors to a frame line: runs of `~` become cyan, the orca body
    * (`__` dorsal or `/_o>`) becomes bold white. Leading spaces and the
    * trailing padding pass through untouched.
    */
  def colorize(line: String, useColor: Boolean): String =
    if !useColor then line
    else
      val withOrca =
        OrcaPattern.replaceAllIn(line, m => fansi.Bold.On(m.matched).render)
      WavePattern.replaceAllIn(
        withOrca,
        m => fansi.Color.Cyan(m.matched).render
      )

  /** The status line above the animation. Yellow so it stands out against the
    * blue waves and white orca.
    */
  def paintLabel(label: String, useColor: Boolean): String =
    val text = s"⌛ $label"
    if useColor then fansi.Color.Yellow(text).render else text
