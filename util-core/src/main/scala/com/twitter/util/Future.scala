package com.twitter.util

import java.util.concurrent.atomic.{AtomicInteger, AtomicReferenceArray}
import scala.collection.mutable
import scala.annotation.tailrec

import com.twitter.concurrent.{Offer, IVar}
import com.twitter.conversions.time._
import java.util.concurrent.{CancellationException, TimeUnit, Future => JavaFuture}

object Future {
  val DEFAULT_TIMEOUT = Duration.MaxValue
  val Unit = apply(())
  val Done = Unit

  /**
   * Make a Future with a constant value. E.g., Future.value(1) is a Future[Int].
   */
  def value[A](a: A): Future[A] = new Promise[A](Return(a))

  /**
   * Make a Future with an error. E.g., Future.exception(new Exception("boo"))
   */
  def exception[A](e: Throwable): Future[A] = new Promise[A](Throw(e))

  def void() = Future[Void] { null }

  /**
   * A factory function to "lift" computations into the Future monad. It will catch
   * exceptions and wrap them in the Throw[_] type. Non-exceptional values are wrapped
   * in the Return[_] type.
   */
  def apply[A](a: => A): Future[A] = new Promise[A](Try(a))

  /**
   * Flattens a nested future.  Same as ffa.flatten, but easier to call from Java.
   */
  def flatten[A](ffa: Future[Future[A]]): Future[A] = ffa.flatten

  /**
   * Take a sequence of Futures, wait till they all complete
   * successfully.  The future fails immediately if any of the joined
   * Futures do, mimicking the semantics of exceptions.
   *
   * @param fs a sequence of Futures
   * @return a Future[Unit] whose value is populated when all of the fs return.
   */
  def join[A](fs: Seq[Future[A]]): Future[Unit] = {
    if (fs.isEmpty) {
      Unit
    } else {
      val count = new AtomicInteger(fs.size)
      makePromise[Unit]() { promise =>
        fs foreach { f =>
          promise.linkTo(f)
          f onSuccess { _ =>
            if (count.decrementAndGet() == 0)
              promise() = Return(())
          } onFailure { cause =>
            promise.updateIfEmpty(Throw(cause))
          }
        }
      }
    }
  }

  /**
   * Collect the results from the given futures into a new future of
   * Seq[A].
   *
   * @param fs a sequence of Futures
   * @return a Future[Seq[A]] containing the collected values from fs.
   */
  def collect[A](fs: Seq[Future[A]]): Future[Seq[A]] = {
    if (fs.isEmpty) {
      Future(Seq[A]())
    } else {
      val results = new AtomicReferenceArray[A](fs.size)
      val count = new AtomicInteger(fs.size)
      makePromise[Seq[A]]() { promise =>
        for (i <- 0 until fs.size) {
          val f = fs(i)
          promise.linkTo(f)
          f onSuccess { x =>
            results.set(i, x)
            if (count.decrementAndGet() == 0) {
              val resultsArray = new mutable.ArrayBuffer[A](fs.size)
              for (j <- 0 until fs.size) resultsArray += results.get(j)
              promise.setValue(resultsArray)
            }
          } onFailure { cause =>
            promise.updateIfEmpty(Throw(cause))
          }
        }
      }
    }
  }

  /*
   * original version:
   *
   * often, this version will not trigger even after all of the collected
   * futures have triggered. some debugging is required.
   *
  def collect[A](fs: Seq[Future[A]]): Future[Seq[A]] = {
    val collected = fs.foldLeft(Future.value(Nil: List[A])) { case (a, e) =>
      a flatMap { aa => e map { _ :: aa } }
    } map { _.reverse }

    // Cancellations don't get propagated in flatMap because the
    // computation is short circuited.  Thus we link manually to get
    // the expected behavior from collect().
    fs foreach { f => collected.linkTo(f) }

    collected
  }
  */

  /**
   * "Select" off the first future to be satisfied.  Return this as a
   * result, with the remainder of the Futures as a sequence.
   */
  def select[A](fs: Seq[Future[A]]): Future[(Try[A], Seq[Future[A]])] = {
    if (fs.isEmpty) {
      Future.exception(new IllegalArgumentException("empty future list!"))
    } else {
      makePromise[(Try[A], Seq[Future[A]])](fs: _*) { promise =>
        @tailrec
        def stripe(heads: Seq[Future[A]], elem: Future[A], tail: Seq[Future[A]]) {
          elem respond { res =>
            if (!promise.isDefined) {
              promise.updateIfEmpty(Return((res, heads ++ tail)))
            }
          }

          if (!tail.isEmpty)
            stripe(heads ++ Seq(elem), tail.head, tail.tail)
        }

        stripe(Seq(), fs.head, fs.tail)
      }
    }
  }

  /**
   * Repeat a computation that returns a Future some number of times, after each
   * computation completes.
   */
  def times[A](n: Int)(f: => Future[A]): Future[Unit] = {
    val count = new AtomicInteger(0)
    whileDo(count.getAndIncrement() < n)(f)
  }

  /**
   * Repeat a computation that returns a Future while some predicate obtains,
   * after each computation completes.
   */
  def whileDo[A](p: => Boolean)(f: => Future[A]): Future[Unit] = {
    def loop(): Future[Unit] = {
      if (p) f flatMap { _ => loop() }
      else Future.Unit
    }

    loop()
  }

  def parallel[A](n: Int)(f: => Future[A]): Seq[Future[A]] = {
    (0 until n) map { i => f }
  }

  private[util] def makePromise[A](links: Cancellable*)(f: Promise[A] => Unit): Promise[A] = {
    val promise = new Promise[A]
    links foreach { promise.linkTo(_) }
    f(promise)
    promise
  }
}

/**
 * An alternative interface for handling Future Events. This interface is designed
 * to be friendly to Java users since it does not require closures.
 */
trait FutureEventListener[T] {
  /**
   * Invoked if the computation completes successfully
   */
  def onSuccess(value: T): Unit

  /**
   * Invoked if the computation completes unsuccessfully
   */
  def onFailure(cause: Throwable): Unit
}

/**
 * A computation evaluated asynchronously. This implementation of Future does not
 * assume any concrete implementation; in particular, it does not couple the user
 * to a specific executor or event loop.
 *
 * Note that this class extends Try[_] indicating that the results of the computation
 * may succeed or fail.
 */
abstract class Future[+A] extends TryLike[A, Future] with Cancellable {
  import Future.{DEFAULT_TIMEOUT, makePromise}

  /**
   * When the computation completes, invoke the given callback function. Respond()
   * yields a Try (either a Return or a Throw). This method is most useful for
   * very generic code (like libraries). Otherwise, it is a best practice to use
   * one of the alternatives (onSuccess(), onFailure(), etc.). Note that almost
   * all methods on Future[_] are written in terms of respond(), so this is
   * the essential template method for use in concrete subclasses.
   */
  def respond(k: Try[A] => Unit): Future[A]

  /**
   * Block indefinitely, wait for the result of the Future to be available.
   */
  override def apply(): A = apply(DEFAULT_TIMEOUT)

  /**
   * Block, but only as long as the given Timeout.
   */
  def apply(timeout: Duration): A = get(timeout)()

  def isReturn = get(DEFAULT_TIMEOUT) isReturn
  def isThrow = get(DEFAULT_TIMEOUT) isThrow

  /**
   * Is the result of the Future available yet?
   */
  def isDefined: Boolean

  /**
   * Trigger a callback if this future is cancelled.
   */
  def onCancellation(f: => Unit)

  /**
   * Demands that the result of the future be available within `timeout`. The result
   * is a Return[_] or Throw[_] depending upon whether the computation finished in
   * time.
   */
  def get(timeout: Duration): Try[A]

  /**
   * Polls for an available result.  If the Future has been satisfied,
   * returns Some(result), otherwise None.
   */
  def poll: Option[Try[A]]

  /**
   * Same as the other within, but with an implicit timer. Sometimes this is more convenient.
   */
  def within(timeout: Duration)(implicit timer: Timer): Future[A] =
    within(timer, timeout)

  /**
   * Returns a new Future that will error if this Future does not return in time.
   *
   * @param timeout indicates how long you are willing to wait for the result to be available.
   */
  def within(timer: Timer, timeout: Duration): Future[A] = {
    makePromise[A](this) { promise =>
      val task = timer.schedule(timeout.fromNow) {
        promise.updateIfEmpty(Throw(new TimeoutException(timeout.toString)))
      }
      respond { r =>
        task.cancel()
        promise.updateIfEmpty(r)
      }
    }
  }

  /**
   * Invoke the callback only if the Future returns successfully. Useful for Scala for comprehensions.
   * Use onSuccess instead of this method for more readable code.
   */
  override def foreach(k: A => Unit) =
    respond(_ foreach k)

  def map[B](f: A => B): Future[B] = flatMap { a => Future { f(a) } }

  /**
   * Invoke the function on the result, if the computation was
   * successful.  Returns a chained Future as in `respond`.
   *
   * @return chained Future
   */
  def onSuccess(f: A => Unit): Future[A] =
    respond {
      case Return(value) => f(value)
      case _ =>
    }

  /**
   * Invoke the function on the error, if the computation was
   * unsuccessful.  Returns a chained Future as in `respond`.
   *
   * @return chained Future
   */
  def onFailure(rescueException: Throwable => Unit): Future[A] =
    respond {
      case Throw(throwable) => rescueException(throwable)
      case _ =>
    }

  def addEventListener(listener: FutureEventListener[_ >: A]) = respond {
    case Throw(cause)  => listener.onFailure(cause)
    case Return(value) => listener.onSuccess(value)
  }

  /**
   * Choose the first Future to succeed.
   *
   * @param other another Future
   * @return a new Future whose result is that of the first of this and other to return
   */
  def select[U >: A](other: Future[U]): Future[U] = {
    makePromise[U](other, this) { promise =>
      other respond { promise.updateIfEmpty(_) }
      this  respond { promise.updateIfEmpty(_) }
    }
  }

  /**
   * A synonym for select(): Choose the first Future to succeed.
   */
  def or[U >: A](other: Future[U]): Future[U] = select(other)

  /**
   * Combines two Futures into one Future of the Tuple of the two results.
   */
  def join[B](other: Future[B]): Future[(A, B)] = {
    makePromise[(A, B)](this, other) { promise =>
      respond {
        case Throw(t) => promise() = Throw(t)
        case Return(a) => other respond {
          case Throw(t) => promise() = Throw(t)
          case Return(b) => promise() = Return((a, b))
        }
      }
    }
  }

  /**
   * Convert this Future[A] to a Future[Unit] by discarding the result.
   */
  def unit: Future[Unit] = map(_ => ())

  /**
   * Send updates from this Future to the other.
   * ``other'' must not yet be satisfied.
   */
  def proxyTo[B >: A](other: Promise[B]) {
    respond { other() = _ }
  }

  /**
   * An offer for this future.  The offer is activated when the future
   * is satisfied.
   */
  def toOffer: Offer[Try[A]] = new Offer[Try[A]] {
    def poll() = if (isDefined) Some(() => get(0.seconds)) else None
    def enqueue(setter: Setter) = {
      respond { v =>
        setter() foreach { _(v) }
      }
      // we can't dequeue futures
      () => ()
    }
    def objects = Seq()
  }

  /**
   * Convert a Twitter Future to a Java native Future. This should match the semantics
   * of a Java Future as closely as possible to avoid issues with the way another API might
   * use them. See:
   *
   * http://download.oracle.com/javase/6/docs/api/java/util/concurrent/Future.html#cancel(boolean)
   */
  def toJavaFuture: JavaFuture[_ <: A] = {
    val f = this
    new JavaFuture[A] {
      override def cancel(cancel: Boolean): Boolean = {
        if (isDone || isCancelled) {
          false
        } else {
          f.cancel()
          true
        }
      }

      override def isCancelled: Boolean = {
        f.isCancelled
      }

      override def isDone: Boolean = {
        f.isCancelled || f.isDefined
      }

      override def get(): A = {
        if (isCancelled) {
          throw new CancellationException()
        }
        f()
      }

      override def get(time: Long, timeUnit: TimeUnit): A = {
        if (isCancelled) {
          throw new CancellationException()
        }
        f.get(Duration.fromTimeUnit(time, timeUnit)) match {
          case Return(r) => r
          case Throw(e) => throw e
        }
      }
    }
  }

  /**
   * Converts a Future[Future[B]] into a Future[B]
   */
  def flatten[B](implicit ev: A <:< Future[B]): Future[B]
}

object Promise {
  case class ImmutableResult(message: String) extends Exception(message)
}

/**
 * A concrete Future implementation that is
 * updatable by some executor or event loop.  A
 * typical use of Promise is for a client to
 * submit a request to some service.  The client
 * is given an object that inherits from
 * Future[_].  The server stores a reference to
 * this object as a Promise[_] and updates the
 * value when the computation completes.
 */
class Promise[A] private[Promise] (
  private[Promise] final val ivar: IVar[Try[A]],
  private[Promise] final val cancelled: IVar[Unit])
  extends Future[A]
{
  import Future.makePromise
  import Promise._

  @volatile private[this] var chained: Future[A] = null
  def this() = this(new IVar[Try[A]], new IVar[Unit])

  override def toString = "Promise@%s(ivar=%s, cancelled=%s)".format(hashCode, ivar, cancelled)

  /**
   * Secondary constructor where result can be provided immediately.
   */
  def this(result: Try[A]) {
    this()
    this.ivar.set(result)
  }

  def isCancelled = cancelled.isDefined
  def cancel() = cancelled.set(())
  def linkTo(other: Cancellable) {
    cancelled.get {
      case () => other.cancel()
    }
  }

  def get(timeout: Duration): Try[A] =
    ivar(timeout) getOrElse {
      Throw(new TimeoutException(timeout.toString))
    }

  def poll = ivar.poll

  /**
   * Merge `other` into this promise.  See
   * {{com.twitter.concurrent.IVar.merge}} for
   * details.  This is necessary in bind
   * operations (flatMap, rescue) in order to
   * prevent space leaks under iteration.
   *
   * Cancellation state is merged along with
   * values, but the semantics are slightly
   * different.  Because the receiver of a promise
   * may affect its cancellation status, we must
   * allow for divergence here: if `this` has been
   * cancelled, but `other` is already complete,
   * `other` will not change its cancellation
   * state (which is fixed at false).
   */
  private[Promise] def merge(other: Future[A]) {
    if (other.isInstanceOf[Promise[_]]) {
      val p = other.asInstanceOf[Promise[A]]
      this.ivar.merge(p.ivar)
      this.cancelled.merge(p.cancelled)
    } else {
      other.proxyTo(this)
      this.linkTo(other)
    }
  }

  def isDefined = ivar.isDefined

  /**
   * Populate the Promise with the given result.
   *
   * @throws ImmutableResult if the Promise is already populated
   */
  def setValue(result: A) = update(Return(result))

  /**
   * Populate the Promise with the given exception.
   *
   * @throws ImmutableResult if the Promise is already populated
   */
  def setException(throwable: Throwable) = update(Throw(throwable))

  /**
   * Populate the Promise with the given Try. The try can either be a value
   * or an exception. setValue and setException are generally more readable
   * methods to use.
   *
   * @throws ImmutableResult if the Promise is already populated
   */
  def update(result: Try[A]) {
    updateIfEmpty(result) || {
      throw new ImmutableResult("Result set multiple times: " + result)
    }
  }

  /**
   * Populate the Promise with the given Try. The try can either be a value
   * or an exception. setValue and setException are generally more readable
   * methods to use.
   *
   * @return true only if the result is updated, false if it was already set.
   */
  def updateIfEmpty(newResult: Try[A]) =
    ivar.set(newResult)

  private[this] def respond0(k: Try[A] => Unit) {
    val saved = Locals.save()
    ivar.get { result =>
      val current = Locals.save()
      saved.restore()
      try
        k(result)
      finally
        current.restore()
    }
  }

  override def respond(k: Try[A] => Unit): Future[A] = {
    // Note that there's a race here, but that's
    // okay.  The resulting Futures are
    // equivalent, and it only makes the
    // optimization less effective.
    //
    // todo: given that it's likely most responds
    // don't actually result in the chained ivar
    // being used, we could create a shell
    // promise.  this would get rid of one object
    // allocation (the chained ivar).
    if (chained eq null)
      chained = new Promise(ivar.chained, cancelled)
    val next = chained
    respond0(k)
    next
  }

  /**
   * Invoke 'f' if this Future is cancelled.
   */
  def onCancellation(f: => Unit) {
    linkTo(new CancellableSink(f))
  }

  /**
   * flatMaps propagate cancellation to the parent
   * promise while it remains waiting.  This means
   * that in a chain of flatMaps, cancellation
   * only affects the current parent promise that
   * is being waited on.
   */
  def flatMap[B, AlsoFuture[B] >: Future[B] <: Future[B]](
    f: A => AlsoFuture[B]
  ): Future[B] = {
    val promise = new Promise[B]
    val k = { _: Unit => this.cancel() }
    promise.cancelled.get(k)
    respond0 {
      case Return(r) =>
        try {
          promise.cancelled.unget(k)
          promise.merge(f(r))
        } catch {
          case e =>
            promise() = Throw(e)
        }

      case Throw(e) =>
        promise() = Throw(e)
    }
    promise
  }

  def flatten[B](implicit ev: A <:< Future[B]): Future[B] =
    flatMap[B, Future] { x => x }

  def rescue[B >: A, AlsoFuture[B] >: Future[B] <: Future[B]](
    rescueException: PartialFunction[Throwable, AlsoFuture[B]]
  ): Future[B] = {
    val promise = new Promise[B]
    val k = { _: Unit => this.cancel() }
    promise.cancelled.get(k)
    respond0 {
      case r: Return[_] =>
        promise() = r

      case Throw(e) if rescueException.isDefinedAt(e) =>
        try {
          promise.cancelled.unget(k)
          promise.merge(rescueException(e))
        } catch {
          case e => promise() = Throw(e)
        }
      case Throw(e) =>
        promise() = Throw(e)
    }
    promise
  }

  override def filter(p: A => Boolean): Future[A] = {
    makePromise[A](this) { promise =>
      respond0 { x => promise() = x.filter(p) }
    }
  }

  def handle[B >: A](rescueException: PartialFunction[Throwable, B]) = rescue {
    case e: Throwable if rescueException.isDefinedAt(e) => Future(rescueException(e))
    case e: Throwable                                   => this
  }

  private[util] def depth = ivar.depth
}

class FutureTask[A](fn: => A) extends Promise[A] with Runnable {
  def run() {
    update(Try(fn))
  }
}

object FutureTask {
  def apply[A](fn: => A) = new FutureTask[A](fn)
}

private[util] object FutureBenchmark {
  /**
   * Admittedly, this is not very good microbenchmarking technique.
   */

  import com.twitter.conversions.storage._
  private[this] val NumIters = 100.million

  private[this] def bench[A](numIters: Long)(f: => A): Long = {
    val begin = System.currentTimeMillis()
    (0L until numIters) foreach { _ => f }
    System.currentTimeMillis() - begin
  }

  private[this] def run[A](name: String)(work: => A) {
    printf("Warming up %s.. ", name)
    val warmupTime = bench(NumIters)(work)
    printf("%d ms\n", warmupTime)

    printf("Running .. ")
    val runTime = bench(NumIters)(work)

    printf(
      "%d ms, %d %s/sec\n",
      runTime, 1000 * NumIters / runTime, name)
  }

  def main(args: Array[String]) {
    run("respond") {
      val promise = new Promise[Unit]
      promise respond { res => () }
      promise() = Return(())
    }

    run("flatMaps") {
      val promise = new Promise[Unit]
      promise flatMap { _ => Future.value(()) }
      promise() = Return(())
    }
  }
}

private[util] object Leaky {
  def main(args: Array[String]) {
    def loop(i: Int): Future[Int] = Future.value(i) flatMap { count =>
      if (count % 1000000 == 0) {
        System.gc()
        println("iter %d %dMB".format(
          count, Runtime.getRuntime().totalMemory()>>20))
      }
      loop(count + 1)
    }

    loop(1)
  }
}
