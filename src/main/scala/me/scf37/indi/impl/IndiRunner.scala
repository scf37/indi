package me.scf37.indi.impl

import cats.Parallel
import cats.effect.{Concurrent, Resource}
import cats.implicits._
import me.scf37.indi.Indi
import me.scf37.indi.Indi.{Identity, Par}
import me.scf37.indi.impl.ContextCache.LoadedValue

object IndiRunner {

  def run[F[_]: Concurrent: Parallel, A](i: Indi[F, A], dependsOn: Seq[Indi[F, Any]], ctx: ContextCache[F]): F[A] = {
    i match {

      case Indi.Allocate(a, _) =>
        ctx.get(key(i), Resource.pure(LoadedValue[Identity, A](a, dependsOn.map(key).toSet))).flatten

      case Indi.Suspend(fa, _) =>
        ctx.get(key(i), fa.map(a => LoadedValue[Identity, A](a, dependsOn.map(key).toSet))).flatten

      case v: Indi.Par[F, Any, Any] =>
        ctx.get(key(i), Resource.liftF {
          val deps = expandPars(v) // allow par composition to be closed in parallel
          (run(v.a, dependsOn, ctx), run(v.b, dependsOn, ctx)).parMapN { (a, b) =>
            LoadedValue[Identity, A]((a -> b).asInstanceOf[A], deps.map(key).toSet)
          }
        }).flatten

      case Indi.Bind(a, f, _) =>
        val load = run(a, dependsOn, ctx).flatMap { aa =>
          val m = f(aa)
          run(m, Seq(a), ctx).map(a2 => LoadedValue[Identity, A](a2, Set(key(m))))
        }
        ctx.get(key(i), Resource.liftF(load)).flatten
    }
  }

  private def expandPars[F[_]](i: Indi[F, Any]): Vector[Indi[F, Any]] = {
    i match {
      case Par(a, b, id) => expandPars(a) ++ expandPars(b)
      case i => Vector(i)
    }
  }

  private def key[F[_], A](i: Indi[F, A]): Identity[A] = i.id
}
