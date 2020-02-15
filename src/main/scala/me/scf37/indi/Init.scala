package me.scf37.indi

import cats.effect.Resource
import cats.implicits._
import cats.{Applicative, Monad}

/**
 * Initialization effect typeclass
 * MonadError can be implemented but it is Monad for now
 *
 * @tparam I
 * @tparam F
 */
trait Init[I[_], F[_]] extends Monad[I] {
  /** lift resource to initialization effect */
  def liftR[A](f: Resource[F, A]): I[A]

  /** lift effect to initialization effect */
  def liftF[A](f: F[A])(implicit A: Applicative[F]): I[A] = liftR(Resource.liftF(f))

  /** merge effect into initialization effect */
  def flattenF[A](i: I[F[A]])(implicit A: Applicative[F]): I[A] = flatMap(i)(f => liftF(f))

  /** merge resource into initialization effect */
  def flattenR[A](i: I[Resource[F, A]])(implicit A: Applicative[F]): I[A] = flatMap(i)(f => liftR(f))
}

object Init {
  def apply[I[_], F[_]](implicit i: Init[I, F]): Init[I, F] = i

  object implicits {
    implicit class TupleOps1[I[_], F[_], A](v: I[A])(implicit Init: Init[I, F], ap: Applicative[F]) {
      def mapF[Z](f: A => F[Z]): I[Z] = Init.flattenF(v.map(f))

      def mapR[Z](f: A => Resource[F, Z]): I[Z] = Init.flattenR(v.map(f))
    }
    implicit class TupleOps2[I[_], F[_], A, B](v: (I[A], I[B]))(implicit Init: Init[I, F], ap: Applicative[F]) {
      def mapF[Z](f: (A, B) => F[Z]): I[Z] = Init.flattenF(v.mapN(f))

      def mapR[Z](f: (A, B) => Resource[F, Z]): I[Z] = Init.flattenR(v.mapN(f))
    }
    implicit class TupleOps3[I[_], F[_], A, B, C](v: (I[A], I[B], I[C]))(implicit Init: Init[I, F], ap: Applicative[F]) {
      def mapF[Z](f: (A, B, C) => F[Z]): I[Z] = Init.flattenF(v.mapN(f))

      def mapR[Z](f: (A, B, C) => Resource[F, Z]): I[Z] = Init.flattenR(v.mapN(f))
    }
    implicit class TupleOps4[I[_], F[_], A, B, C, D](v: (I[A], I[B], I[C], I[D]))(implicit Init: Init[I, F], ap: Applicative[F]) {
      def mapF[Z](f: (A, B, C, D) => F[Z]): I[Z] = Init.flattenF(v.mapN(f))

      def mapR[Z](f: (A, B, C, D) => Resource[F, Z]): I[Z] = Init.flattenR(v.mapN(f))
    }
    implicit class TupleOps5[I[_], F[_], A, B, C, D, E](v: (I[A], I[B], I[C], I[D], I[E]))(implicit Init: Init[I, F], ap: Applicative[F]) {
      def mapF[Z](f: (A, B, C, D, E) => F[Z]): I[Z] = Init.flattenF(v.mapN(f))

      def mapR[Z](f: (A, B, C, D, E) => Resource[F, Z]): I[Z] = Init.flattenR(v.mapN(f))
    }

    implicit class InitOpsF[F[_], A](v: F[A]) {
      def init[I[_]](implicit ap: Applicative[F], i: Init[I, F]): I[A] = i.liftF(v)
    }

    implicit class InitOpsIF[I[_], F[_], A](v: I[F[A]]) {
      def init(implicit ap: Applicative[F], i: Init[I, F]): I[A] = i.flattenF(v)
    }

    implicit class InitOpsR[F[_], A](v: Resource[F, A]) {
      def init[I[_]](implicit ap: Applicative[F], i: Init[I, F]): I[A] = i.liftR(v)
    }

    implicit class InitOpsIR[I[_], F[_], A](v: I[Resource[F, A]]) {
      def init(implicit ap: Applicative[F], i: Init[I, F]): I[A] = i.flattenR(v)
    }
  }
}