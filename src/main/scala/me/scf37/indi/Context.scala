package me.scf37.indi

import cats.Parallel
import cats.effect.{Concurrent, Resource}
import me.scf37.indi.impl.ContextCache

sealed trait Context[F[_]] {
  def set[A](key: Indi[F, A], value: A): F[Unit]

  private[indi] def cache: ContextCache[F]
}

object Context {
  def apply[F[_]: Concurrent: Parallel]: Resource[F, Context[F]] =
    ContextCache[F].map(Impl.apply)

  private case class Impl[F[_]](cache: ContextCache[F]) extends Context[F] {
    override def set[A](key: Indi[F, A], value: A): F[Unit] = cache.set(key.id, value)
  }
}