package me.scf37.indi

import java.util.concurrent.atomic.AtomicInteger

import cats.Parallel
import cats.effect.{Concurrent, Resource}
import me.scf37.indi.Indi.Identity
import me.scf37.indi.impl.IndiRunner

import scala.annotation.unchecked.uncheckedVariance

/**
 * Indi is not DI.
 *
 *
 * @tparam F
 * @tparam A
 */
sealed abstract class Indi[F[_], +A] {
  /**
   * Evaluate this monad
   * Returned value lives as long as ctx
   *
   * @return
   */
  def eval(ctx: Context[F])(implicit C: Concurrent[F], P: Parallel[F]): F[A @uncheckedVariance] = IndiRunner.run(this, Seq.empty, ctx.cache)

  def map[B](f: A => B): Indi[F, B] = flatMap(a => Indi.pure(f(a)))

  def flatMap[B](f: A => Indi[F, B]): Indi[F, B] = Indi.Bind(this, f, Indi.Identity[B])

  private[indi] def id: Identity[A]
}

object Indi {
  def apply[F[_], A](r: Resource[F, A]): Indi[F, A] = Suspend(r, Identity[A])

  def pure[F[_], A](a: A): Indi[F, A] = Allocate(a, Identity[A])

  def par[F[_], A, B](a: Indi[F, A], b: Indi[F, B]): Indi[F, (A, B)] = Par(a, b, Identity[(A, B)])

  implicit def initInstance[F[_]]: Init[Indi[F, *], F] = new Init[Indi[F, *], F] {
    override def liftR[A](f: Resource[F, A]): Indi[F, A] = Suspend(f, Identity[A])

    override def pure[A](x: A): Indi[F, A] = Allocate(x, Identity[A])

    override def flatMap[A, B](fa: Indi[F, A])(f: A => Indi[F, B]): Indi[F, B] = Bind(fa, f, Identity[B])

    override def product[A, B](fa: Indi[F, A], fb: Indi[F, B]): Indi[F, (A, B)] = Par(fa, fb, Identity[(A, B)])

    override def ap[A, B](ff: Indi[F, A => B])(fa: Indi[F, A]): Indi[F, B] = map(product(fa, ff))(fa => fa._2(fa._1))

    override def tailRecM[A, B](a: A)(f: A => Indi[F, Either[A, B]]): Indi[F, B] = ???
  }

  private[indi] trait Identity[+A]
  private[indi] object Identity {
    private val i = new AtomicInteger()
    def apply[A]: Identity[A] = new Identity[A] {
      val id = i.incrementAndGet().toString
      override def toString: String = id
    }
  }

  // lift value to memo
  private[indi] case class Allocate[F[_], A](a: A, id: Identity[A]) extends Indi[F, A] {
    override def toString: String = getClass.getSimpleName + "#" + id
  }

  // lift effect to memo, will be cached
  private[indi] case class Suspend[F[_], A](fa: Resource[F, A], id: Identity[A]) extends Indi[F, A] {
    override def toString: String = getClass.getSimpleName + "#" + id
  }

  // parallel evaluation
  private[indi] case class Par[F[_], A, B](a: Indi[F, A], b: Indi[F, B], id: Identity[(A, B)]) extends Indi[F, (A, B)]

  // sequential evaluation
  private[indi] case class Bind[F[_], A, B](a: Indi[F, A], f: A => Indi[F, B], id: Identity[B]) extends Indi[F, B]
}