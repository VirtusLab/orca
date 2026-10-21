package orca.safe

import language.experimental.captureChecking

import caps.assumeSafe
import ox.{Fork, Ox, OxUnsupervised, ResourceScope, UnsupervisedFork}
import ox.channels.{BufferCapacity, Channel, ChannelClosed, Source}
import ox.flow.Flow
import ox.resilience.{ResultPolicy, RetryConfig}
import ox.scheduling.Schedule

import scala.concurrent.duration.FiniteDuration
import scala.util.NotGiven

/** The Ox operations, and the few JVM concurrency primitives, that safe code
  * uses. Ox's types (`Ox`, `Fork`, `Channel`, …) and their members need no
  * forwarder.
  */
@assumeSafe
object concurrency:
  inline def supervised[T](inline body: Ox ?=> T): T = ox.supervised(body)
  inline def fork[T](inline body: => T)(using Ox): Fork[T] = ox.fork(body)
  inline def forkDiscard[T](inline body: => T)(using Ox): Unit =
    ox.forkDiscard(body)
  inline def forkUnsupervised[T](inline body: => T)(using
      OxUnsupervised
  ): UnsupervisedFork[T] = ox.forkUnsupervised(body)

  def useInScope[T](acquire: => T)(release: T => Unit)(using
      ResourceScope
  ): T = ox.useInScope(acquire)(release)

  /** A scope for cleanup only, for code without a concurrency scope. */
  def resourceScope[T](body: ResourceScope ?=> T)(using
      NotGiven[OxUnsupervised]
  ): T = ox.resourceScope(body)
  def releaseAfterScope(release: => Unit)(using ResourceScope): Unit =
    ox.releaseAfterScope(release)

  def sleep(howLong: FiniteDuration): Unit = ox.sleep(howLong)
  inline def forever(inline f: Unit): Nothing = ox.forever(f)

  /** `System.nanoTime`: monotonic, for measuring elapsed time. */
  def nanoTime(): Long = System.nanoTime()

  /** A thread's identity: equal only to the identity of the same thread. */
  opaque type ThreadIdentity = Thread

  def currentThread(): ThreadIdentity = Thread.currentThread()

  /** Runs `f` over `items`, at most `parallelism` at a time, calling `onResult`
    * on each result as it completes; results are in completion order. The first
    * failure interrupts the rest and is rethrown.
    */
  def mapParUnordered[A, R](parallelism: Int)(items: Seq[A])(f: A => R)(
      onResult: R => Unit
  ): List[R] =
    Flow
      .fromIterable(items)
      .mapParUnordered(parallelism)(f)
      .tap(onResult)
      .runToList()

  def bufferedChannel[T](capacity: Int): Channel[T] =
    Channel.buffered[T](capacity)
  def bufferedDefaultChannel[T](using BufferCapacity): Channel[T] =
    Channel.bufferedDefault[T]

  /** ox's implicit-scope default, which safe code can't reference. */
  given defaultBufferCapacity: BufferCapacity = BufferCapacity.default

  def rendezvousChannel[T]: Channel[T] = Channel.rendezvous[T]

  /** An actor running `logic` in a fork of the enclosing scope, one call at a
    * time; its mailbox holds `BufferCapacity` calls. Call it with `ask`: a
    * `tell` that throws ends the enclosing scope.
    */
  def actor[T](logic: T)(using Ox, BufferCapacity): ox.channels.ActorRef[T] =
    ox.channels.Actor.create(logic)

  /** What `source` delivers, read lazily until it is done or errored. Each
    * `next` blocks like `receive`.
    */
  def receiveUntilClosed[T](source: Source[T]): Iterator[T] =
    Iterator
      .continually(source.receiveOrClosed())
      .takeWhile(!_.isInstanceOf[ChannelClosed])
      .map(_.asInstanceOf[T])

  def retry[T](config: RetryConfig[Throwable, T])(operation: => T): T =
    ox.resilience.retry(config)(operation)
  def retryConfig[E, T](
      schedule: Schedule,
      policy: ResultPolicy[E, T]
  ): RetryConfig[E, T] = RetryConfig(schedule, policy)
  def retryWhen[E, T](isWorthRetrying: E => Boolean): ResultPolicy[E, T] =
    ResultPolicy.retryWhen[E, T](isWorthRetrying)
  def resultPolicy[E, T](
      isSuccess: T => Boolean,
      isWorthRetrying: E => Boolean
  ): ResultPolicy[E, T] = ResultPolicy(isSuccess, isWorthRetrying)
  def exponentialBackoff(first: FiniteDuration): Schedule =
    Schedule.exponentialBackoff(first)
