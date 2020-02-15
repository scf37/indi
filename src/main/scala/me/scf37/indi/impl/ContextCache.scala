package me.scf37.indi.impl

import cats.Parallel
import cats.effect.concurrent.{Deferred, MVar, Ref}
import cats.effect.{Bracket, Concurrent, ExitCase, Resource, Sync}
import cats.implicits._
import ContextCache.LoadedValue

/**
 * Represents cached objects
 *
 * Context is loading cache of Resource-s and therefore can be closed, closing all resources hold.
 *
 * When loading, dependencies can be provided so de-allocation will be done in order reverse to allocation
 *
 * @tparam F
 */
trait ContextCache[F[_]] {
  /**
   * get value by key `key`, if exists, otherwise use provided resource to load it
   *
   * returned outer effect is memoization effect, inner effect is value evaluation effect
   */
  def get[K[_], A](key: K[A], load: Resource[F, LoadedValue[K, A]]): F[F[A]]

  /**
   * Set new value for key `key`
   * If value exists, existing value and all values that depend on it will be closed.
   *
   */
  def set[K[_], A](key: K[A], value: A): F[Unit]
}

object ContextCache {

  case class LoadedValue[K[_], A](
      /** loaded value */
      value: A,
      /** set of keys this value depends on. all keys must already exist in cache */
      dependsOn: Set[K[_]]
  )

  def apply[F[_]: Concurrent: Parallel]: Resource[F, ContextCache[F]] = Resource {
    for {
      state <- Ref.of(emptyState[F])
      impl = Impl(state)
    } yield impl -> impl.close()
  }

  private case class Value[F[_]](value: Either[Throwable, Any], close: F[Unit])
  private case class State[F[_]](
      values: Map[Any, Deferred[F, Value[F]]],
      dependsOn: Map[Any, F[Set[Any]]]
  )

  private def emptyState[F[_]]: State[F] = State[F](Map.empty, Map.empty)

  private case class Impl[F[_]: Concurrent: Parallel](
      state: Ref[F, State[F]]
  ) extends ContextCache[F] {

    override def get[K[_], A](key: K[A], load: Resource[F, LoadedValue[K, A]]): F[F[A]] = {
      state.modify { st =>
        st.values.get(key) match {
          case Some(d) =>
            st -> d.get.flatMap(vv => rethrow(vv.value).map(_.asInstanceOf[A]))

          case None =>
            val d = Deferred.unsafe[F, Value[F]]
            val dd = Deferred.unsafe[F, Set[K[_]]]

            val loadF = for {
              _ <- load.allocated.flatMap { case (loadedValue, close) =>
                d.complete(Value(Right(loadedValue.value), close)) >>
                  dd.complete(loadedValue.dependsOn)
              }.handleErrorWith { ex =>
                d.complete(Value(Left(ex), ().pure[F])) >>
                  dd.complete(Set.empty)
              }
            } yield ()

            val st2 = st.copy(values = st.values + (key -> d), dependsOn = st.dependsOn + (key -> dd.get.map(set => set.map(v => v: Any))))
            st2 -> (loadF >> d.get.flatMap(vv => rethrow(vv.value).map(_.asInstanceOf[A])))

        }
      }

    }

    private def rethrow[A](e: Either[Throwable, A]): F[A] = e.fold(_.raiseError[F, A], _.pure[F])

    override def set[K[_], A](key: K[A], value: A): F[Unit] = state.modify { st =>
      val d = Deferred.unsafe[F, Value[F]]
      val v = Value(Right(value), ().pure[F])

      val f: F[Unit] =
        if (st.values.contains(key)) {
          computeParents(st.dependsOn).flatMap { parents =>
            def subtree(root: Any): Set[Any] =
              parents.get(root) match {
                case None => Set(root)
                case Some(values) => values.flatMap(subtree) + root
              }

            state.modify(st => close(subtree(key), st)).flatten
          }
        } else d.complete(v)
      st.copy(values = st.values + (key -> d)) -> f
    }.flatten

    def close(): F[Unit] = state.modify { st =>
      close(st.values.keySet, st)
    }.flatten

    private def close(items: Set[Any], st: State[F]): (State[F], F[Unit]) = {
      val st2 = st.copy(values = st.values -- items, dependsOn = st.dependsOn -- items)
      st2 -> computeParents(st.dependsOn).flatMap { dependsOn =>
        topologicalClose(items, dependsOn)(key => st.values(key).get.flatMap(_.close))
      }
    }

    private def computeParents[K](parents: Map[K, F[Set[K]]]): F[Map[K, Set[K]]] =
      parents.map { case (k, vf) =>
        vf.map(k -> _)
      }.toList.parSequence.map(_.toMap)

    // close provided items, ensuring children are always closed before parents
    private def topologicalClose[K](items: Set[K], parents: Map[K, Set[K]])(close: K => F[Unit]): F[Unit] = {
      topologicalSort(items, parents).map { batch =>
        batch.map(close).toList.parSequence
      }.sequence.void
    }

    private def topologicalSort[K](items: Set[K], parents: Map[K, Set[K]]): List[Set[K]] = {
      if (items.isEmpty) return Nil

      val bottoms: Set[K] = {
        val hasChildren = (parents.filter(items contains _._1).filter(_._2.nonEmpty)).values.flatten.toSet
        items -- hasChildren
      }

      bottoms +: topologicalSort(items -- bottoms, parents)
    }
  }

}
