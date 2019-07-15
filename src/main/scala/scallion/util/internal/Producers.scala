/* Copyright 2019 EPFL, Lausanne
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package scallion.util.internal

import scala.collection.mutable.ArrayBuffer

/** Result of a `peek`. */
private[internal] sealed trait Peek[+A] {

  /** Indicates if the result can not change. */
  def isStable: Boolean = this != Unavailable

  /** Returtns `true` if the next value is not available, `false` otherwise. */
  def isEmpty: Boolean = this match {
    case Available(_) => false
    case _ => true
  }

  /** Returtns `true` if the next value is available, `false` otherwise. */
  def nonEmpty: Boolean = !isEmpty

  /** Applies a function on the peeked value. */
  def map[B](f: A => B): Peek[B] = this match {
    case Available(value) => Available(f(value))
    case Terminated => Terminated
    case Unavailable => Unavailable
  }

  /** Applies a unit function on the peeked value, if any. */
  def foreach(f: A => Unit): Unit = this match {
    case Available(value) => f(value)
    case _ => ()
  }
}

/** Indicates that the next value is available. */
private[internal] case class Available[+A](value: A) extends Peek[A]

/** Indicates that the producer is terminated. */
private[internal] case object Terminated extends Peek[Nothing]

/** Indicates that the next value, if any, is not yet available. */
private[internal] case object Unavailable extends Peek[Nothing]

/** Stream of values. */
trait Producer[+A] { self =>

  /** Returns the next value to be produced, if any.
    *
    * If the result is *stable* (checked using `.stable`),
    * the result of invoking this method will not change until
    * `.skip()` is called.
    */
  private[internal] def peek(): Peek[A]

  /** Skips the produced value.
    *
    * This method should only be called after
    * a successful call to `.peek()`
    * (i.e. a call returning an `Available` value),
    * and so only once per such such call.
    */
  private[internal] def skip(): Unit

  /** Apply a function on the produced values. */
  def map[B](f: A => B): Producer[B] = new Producer[B] {
    private var cache: Option[Peek[B]] = None

    override private[internal] def peek(): Peek[B] = cache match {
      case Some(peeked) => peeked
      case None => {
        val peeked = self.peek().map(f)
        if (peeked.isStable) {
          cache = Some(peeked)
        }
        peeked
      }
    }
    override private[internal] def skip(): Unit = {
      cache = None
      self.skip()
    }
  }

  /** Converts this producer to an iterator.
    *
    * `this` producer should no longer be used after
    * calling this method.
    */
  def toIterator: Iterator[A] = new Iterator[A] {
    private var cache: Option[Peek[A]] = None

    private def getCache(): Peek[A] = cache match {
      case Some(value) => value
      case None => {
        val value = peek()
        cache = Some(value)
        value
      }
    }

    override def hasNext: Boolean = getCache().nonEmpty

    override def next(): A = getCache() match {
      case Available(value) => {
        cache = None
        skip()
        value
      }
      case _ => throw new NoSuchElementException("Empty iterator.")
    }
  }
}

/** Contains utilities to build producers. */
object Producer {

  /** The empty producer. */
  val empty: Producer[Nothing] = new Producer[Nothing] {
    override private[internal] def peek(): Peek[Nothing] = Terminated
    override private[internal] def skip(): Unit = ()
  }

  /** Returns a new producer that produces the given `value` a single time. */
  def single[A](value: A): Producer[A] = new Producer[A] {
    private var result: Peek[A] = Available(value)
    override private[internal] def peek(): Peek[A] = result
    override private[internal] def skip(): Unit = result = Terminated
  }

  /** Returns a producer and a function that returns fresh views over the producer.
    *
    * @param producer The producer to duplicate. Should not be used again after this call.
    */
  def duplicate[A](producer: Producer[A]): (Producer[A], () => Producer[A]) = {
    val memorized = new MemoryProducer(producer)

    (memorized, () => memorized.createView())
  }

  /** Returns a lazy wrapper around a producer. */
  def lazily[A](producer: => Producer[A]): Producer[A] = new Producer[A] {
    private lazy val inner: Producer[A] = producer

    override private[internal] def peek(): Peek[A] = inner.peek()
    override private[internal] def skip(): Unit = inner.skip()
  }
}

/** Extra operations for producers with an ordering and a join operation. */
trait ProducerOps[A] {

  /** Combines two values into a single value.
    *
    * Both arguments should always be `lessEquals` to the result.
    */
  def join(x: A, y: A): A

  /** Compares two values. */
  def lessEquals(x: A, y: A): Boolean

  /** Union of two producers.
    *
    * If the two producers produce values in increasing order,
    * then the resulting producer will also produce values in order.
    */
  def union(left: Producer[A], right: Producer[A]): Producer[A] = new Producer[A] {
    private var cache: Option[Peek[Either[A, A]]] = None

    private def getCache(): Peek[Either[A, A]] = cache match {
      case Some(peeked) => peeked
      case None => {
        val peeked = (left.peek(), right.peek()) match {
          case (Available(x), Available(y)) => {
            if (lessEquals(x, y)) {
              Available(Left(x))
            }
            else {
              Available(Right(y))
            }
          }
          case (Available(x), _) => Available(Left(x))
          case (_, Available(y)) => Available(Right(y))
          case (Terminated, Terminated) => Terminated
          case _ => Unavailable
        }

        if (peeked.isStable) {
          cache = Some(peeked)
        }

        peeked
      }
    }

    override private[internal] def peek(): Peek[A] = getCache() match {
      case Available(Left(value)) => Available(value)
      case Available(Right(value)) => Available(value)
      case Terminated => Terminated
      case Unavailable => Unavailable
    }

    override private[internal] def skip(): Unit = {
      cache.foreach { peeked =>
        peeked.foreach {
          case Left(_) => left.skip()
          case Right(_) => right.skip()
        }
      }

      cache = None
    }
  }

  /** Product of two producers.
    *
    * If the two producers produce values in increasing order,
    * then the resulting producer will produce their `join` in increasing order.
    */
  def product(left: Producer[A], right: Producer[A]): Producer[A] = new Producer[A] {
    private val (mainRight, createRightView) = Producer.duplicate(right)

    private val joinProducers: ArrayBuffer[Producer[A]] = new ArrayBuffer()

    private var included: Boolean = false
    private var leftTerminated: Boolean = false

    private var cache: Option[Peek[(Int, A)]] = None

    private def includeMore(): Unit = {
      left.peek() match {
        case Available(leftValue) => {
          val rightProducer = if (joinProducers.isEmpty) mainRight else createRightView()
          joinProducers += rightProducer.map(rightValue => join(leftValue, rightValue))
          included = true
          left.skip()
        }
        case Terminated => {
          included = true
          leftTerminated = true
        }
        case Unavailable => ()
      }
    }

    private def getCache(): Peek[(Int, A)] = cache match {
      case Some(peeked) => peeked
      case None => {
        if (!included) {
          includeMore()
        }

        var best: Peek[(Int, A)] = if (leftTerminated) Terminated else Unavailable

        for (i <- 0 until joinProducers.size) {
          joinProducers(i).peek() match {
            case Available(joinValue) => {
              best match {
                case Available((_, bestValue)) => {
                  if (!lessEquals(bestValue, joinValue)) {
                    best = Available((i, joinValue))
                  }
                }
                case _ => best = Available((i, joinValue))
              }
            }
            case Unavailable => {
              if (best == Terminated) {
                best = Unavailable
              }
            }
            case Terminated => ()
          }
        }

        if (best.isStable) {
          cache = Some(best)
        }

        best
      }
    }

    override private[internal] def peek(): Peek[A] = getCache() match {
      case Available((_, value)) => Available(value)
      case Terminated => Terminated
      case Unavailable => Unavailable
    }
    override private[internal] def skip(): Unit = cache.foreach { peeked =>
      peeked.foreach {
        case (i, _) => {
          joinProducers(i).skip()
          if (i == joinProducers.size - 1 && !leftTerminated) {
            included = false
          }
          cache = None
        }
      }
    }
  }
}

/** Producer with an internal memory of the value produced. */
private class MemoryProducer[A](producer: Producer[A]) extends Producer[A] {
  private val buffer: ArrayBuffer[A] = new ArrayBuffer[A]()

  private var ended: Boolean = false

  private var stored: Boolean = false

  override private[internal] def peek(): Peek[A] = producer.peek() match {
    case res@Available(value) => {
      if (!stored) {
        buffer += value
        stored = true
      }
      res
    }
    case res@Terminated => {
      ended = true
      res
    }
    case res@Unavailable => res
  }

  override private[internal] def skip(): Unit = {
    producer.skip()
    stored = false
  }

  /** Creates a producer that can only produce the values
    * produced so far by `this` producer.
    */
  def createView(): Producer[A] = new Producer[A] {
    private var index = 0
    override private[internal] def peek(): Peek[A] = {
      if (index < buffer.size) {
        Available(buffer(index))
      }
      else if (ended) {
        Terminated
      }
      else {
        Unavailable
      }
    }
    override private[internal] def skip(): Unit = index += 1
  }
}