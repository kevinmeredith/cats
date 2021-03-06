package cats
package data

import java.io.Serializable
import cats.arrow.{ArrowChoice, CommutativeArrow}
import scala.annotation.tailrec

/**
 * A function type of a single input that can do function composition
 * (via `andThen` and `compose`) in constant stack space with amortized
 * linear time application (in the number of constituent functions).
 *
 * Example:
 *
 * {{{
 *   val seed = AndThen((x: Int) => x + 1)
 *   val f = (0 until 10000).foldLeft(seed)((acc, _) => acc.andThen(_ + 1))
 *
 *   // This should not trigger stack overflow ;-)
 *   f(0)
 * }}}
 *
 * This can be used to build stack safe data structures that make
 * use of lambdas. The perfect candidates for usage with `AndThen`
 * are the data structures using a signature like this (where
 * `F[_]` is a monadic type):
 *
 * {{{
 *   A => F[B]
 * }}}
 *
 * As an example, if we described this data structure, the naive
 * solution for that `map` is stack unsafe:
 *
 * {{{
 *   case class Resource[F[_], A, B](
 *     acquire: F[A],
 *     use: A => F[B],
 *     release: A => F[Unit]) {
 *
 *     def flatMap[C](f: B => C)(implicit F: Functor[F]): Resource[F, A, C] = {
 *       Resource(
 *         ra.acquire,
 *         // Stack Unsafe!
 *         a => ra.use(a).map(f),
 *         ra.release)
 *     }
 *   }
 * }}}
 *
 * To describe a `flatMap` operation for this data type, `AndThen`
 * can save the day:
 *
 * {{{
 *   def flatMap[C](f: B => C)(implicit F: Functor[F]): Resource[F, A, C] = {
 *     Resource(
 *       ra.acquire,
 *       AndThen(ra.use).andThen(_.map(f)),
 *       ra.release)
 *   }
 * }}}
 */
sealed abstract class AndThen[-T, +R] extends (T => R) with Product with Serializable {

  import AndThen._

  final def apply(a: T): R =
    runLoop(a)

  override def andThen[A](g: R => A): AndThen[T, A] =
    // Fusing calls up to a certain threshold, using the fusion
    // technique implemented for `cats.effect.IO#map`
    this match {
      case Single(f, index) if index != fusionMaxStackDepth =>
        Single(f.andThen(g), index + 1)
      case _ =>
        andThenF(AndThen(g))
    }

  override def compose[A](g: A => T): AndThen[A, R] =
    // Fusing calls up to a certain threshold, using the fusion
    // technique implemented for `cats.effect.IO#map`
    this match {
      case Single(f, index) if index != fusionMaxStackDepth =>
        Single(f.compose(g), index + 1)
      case _ =>
        composeF(AndThen(g))
    }

  private def runLoop(start: T): R = {
    @tailrec
    def loop[A](self: AndThen[A, R], current: A): R =
      self match {
        case Single(f, _) => f(current)

        case Concat(Single(f, _), right) =>
          loop(right, f(current))

        case Concat(left @ Concat(_, _), right) =>
          loop(left.rotateAccum(right), current)
      }

    loop(this, start)
  }

  final private def andThenF[X](right: AndThen[R, X]): AndThen[T, X] =
    Concat(this, right)
  final private def composeF[X](right: AndThen[X, T]): AndThen[X, R] =
    Concat(right, this)

  // converts left-leaning to right-leaning
  final protected def rotateAccum[E](_right: AndThen[R, E]): AndThen[T, E] = {
    @tailrec
    def loop[A](left: AndThen[T, A], right: AndThen[A, E]): AndThen[T, E] =
      left match {
        case Concat(left1, right1) =>
          loop(left1, Concat(right1, right))
        case notConcat => Concat(notConcat, right)
      }

    loop(this, _right)
  }

  override def toString: String =
    "AndThen$" + System.identityHashCode(this)
}

object AndThen extends AndThenInstances0 {

  /**
   * Builds an [[AndThen]] reference by wrapping a plain function.
   */
  def apply[A, B](f: A => B): AndThen[A, B] =
    f match {
      case ref: AndThen[A, B] @unchecked => ref
      case _                             => Single(f, 0)
    }

  final private case class Single[-A, +B](f: A => B, index: Int) extends AndThen[A, B]
  final private case class Concat[-A, E, +B](left: AndThen[A, E], right: AndThen[E, B]) extends AndThen[A, B]

  /**
   * Establishes the maximum stack depth when fusing `andThen` or
   * `compose` calls.
   *
   * The default is `128`, from which we subtract one as an optimization,
   * a "!=" comparison being slightly more efficient than a "<".
   *
   * This value was reached by taking into account the default stack
   * size as set on 32 bits or 64 bits, Linux or Windows systems,
   * being enough to notice performance gains, but not big enough
   * to be in danger of triggering a stack-overflow error.
   */
  final private val fusionMaxStackDepth = 127

  /**
   * If you are going to call this function many times, right associating it
   * once can be a significant performance improvement for VERY long chains.
   */
  def toRightAssociated[A, B](fn: AndThen[A, B]): AndThen[A, B] = {
    @tailrec
    def loop[X, Y](beg: AndThen[A, X], middle: AndThen[X, Y], end: AndThen[Y, B], endDone: Boolean): AndThen[A, B] =
      if (endDone) {
        // end is right associated
        middle match {
          case sm @ Single(_, _) =>
            val newEnd = Concat(sm, end)
            beg match {
              case sb @ Single(_, _)  => Concat(sb, newEnd)
              case Concat(begA, begB) => loop(begA, begB, newEnd, true)
            }
          case Concat(mA, mB) =>
            // rotate mA onto beg:
            loop(Concat(beg, mA), mB, end, true)
        }
      } else {
        // we are still right-associating the end
        end match {
          case se @ Single(_, _)  => loop(beg, middle, se, true)
          case Concat(endA, endB) => loop(beg, Concat(middle, endA), endB, false)
        }
      }

    fn match {
      case Concat(Concat(a, b), c)                           => loop(a, b, c, false)
      case Concat(a, Concat(b, c))                           => loop(a, b, c, false)
      case Concat(Single(_, _), Single(_, _)) | Single(_, _) => fn
    }
  }
}

abstract private[data] class AndThenInstances0 extends AndThenInstances1 {

  /**
   * [[cats.Monad]] instance for [[AndThen]].
   */
  implicit def catsDataMonadForAndThen[T]: Monad[AndThen[T, *]] =
    new Monad[AndThen[T, *]] {
      // Piggybacking on the instance for Function1
      private[this] val fn1 = instances.all.catsStdMonadForFunction1[T]

      def pure[A](x: A): AndThen[T, A] =
        AndThen(fn1.pure[A](x))

      def flatMap[A, B](fa: AndThen[T, A])(f: A => AndThen[T, B]): AndThen[T, B] =
        AndThen(fn1.flatMap(fa)(f))

      override def map[A, B](fa: AndThen[T, A])(f: A => B): AndThen[T, B] =
        AndThen(f).compose(fa)

      def tailRecM[A, B](a: A)(f: A => AndThen[T, Either[A, B]]): AndThen[T, B] =
        AndThen(fn1.tailRecM(a)(f))
    }

  /**
   * [[cats.ContravariantMonoidal]] instance for [[AndThen]].
   */
  implicit def catsDataContravariantMonoidalForAndThen[R: Monoid]: ContravariantMonoidal[AndThen[*, R]] =
    new ContravariantMonoidal[AndThen[*, R]] {
      // Piggybacking on the instance for Function1
      private[this] val fn1 = instances.all.catsStdContravariantMonoidalForFunction1[R]

      def unit: AndThen[Unit, R] =
        AndThen(fn1.unit)

      def contramap[A, B](fa: AndThen[A, R])(f: B => A): AndThen[B, R] =
        fa.compose(f)

      def product[A, B](fa: AndThen[A, R], fb: AndThen[B, R]): AndThen[(A, B), R] =
        AndThen(fn1.product(fa, fb))
    }

  /**
   * [[cats.arrow.ArrowChoice ArrowChoice]] and
   * [[cats.arrow.CommutativeArrow CommutativeArrow]] instances
   * for [[AndThen]].
   */
  implicit val catsDataArrowForAndThen: ArrowChoice[AndThen] with CommutativeArrow[AndThen] =
    new ArrowChoice[AndThen] with CommutativeArrow[AndThen] {
      // Piggybacking on the instance for Function1
      private[this] val fn1 = instances.all.catsStdInstancesForFunction1

      def choose[A, B, C, D](f: AndThen[A, C])(g: AndThen[B, D]): AndThen[Either[A, B], Either[C, D]] =
        AndThen(fn1.choose(f)(g))

      def lift[A, B](f: A => B): AndThen[A, B] =
        AndThen(f)

      def first[A, B, C](fa: AndThen[A, B]): AndThen[(A, C), (B, C)] =
        AndThen(fn1.first(fa))

      override def split[A, B, C, D](f: AndThen[A, B], g: AndThen[C, D]): AndThen[(A, C), (B, D)] =
        AndThen(fn1.split(f, g))

      def compose[A, B, C](f: AndThen[B, C], g: AndThen[A, B]): AndThen[A, C] =
        f.compose(g)
    }
}

abstract private[data] class AndThenInstances1 {

  /**
   * [[cats.Contravariant]] instance for [[AndThen]].
   */
  implicit def catsDataContravariantForAndThen[R]: Contravariant[AndThen[*, R]] =
    new Contravariant[AndThen[*, R]] {
      def contramap[T1, T0](fa: AndThen[T1, R])(f: T0 => T1): AndThen[T0, R] =
        fa.compose(f)
    }
}
