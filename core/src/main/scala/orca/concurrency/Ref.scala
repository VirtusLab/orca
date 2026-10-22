package orca.concurrency

import language.experimental.captureChecking

import caps.assumeSafe

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec

/** A value shared across threads, replaced by pure functions of it. `S` must be
  * immutable.
  *
  * For `update` and `modify`: `f` may run more than once; the compiler rejects
  * an `f` that captures a capability; don't log, emit events or touch other
  * `Ref`s inside it.
  */
@assumeSafe
final class Ref[S] private (initial: S):
  private val cell: AtomicReference[S] = AtomicReference(initial)

  def get: S = cell.get()

  def set(s: S): Unit = cell.set(s)

  def update(f: S -> S): Unit = modify(s => (f(s), ()))

  /** Replaces the value with the first of `f`'s pair, returning the second. */
  @tailrec
  def modify[R](f: S -> (S, R)): R =
    val current = cell.get()
    val (next, result) = f(current)
    if cell.compareAndSet(current, next) then result else modify(f)

@assumeSafe
object Ref:
  def apply[S](initial: S): Ref[S] = new Ref(initial)
