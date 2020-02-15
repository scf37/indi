package me.scf37.indi.impl

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

import cats.Parallel
import cats.effect.{Concurrent, IO, Resource, Sync}
import cats.implicits._
import me.scf37.indi.impl.ContextCache.LoadedValue
import org.scalatest.freespec.AnyFreeSpec

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext

class ContextCacheTest extends AnyFreeSpec {
  type Key[A] = A
  implicit val cs = IO.contextShift(ExecutionContext.global)

  "context never evaluates resource twice" in  {
    def test[F[_] : Concurrent: Parallel]: F[_] = {
      testContextCache[F].use { c =>
        val (ctx, a, b, as, bs) = (c.ctx, c.a, c.b, c.as, c.bs)
        for {
          v1 <- ctx.get[Key, Int](1, a).flatten
          _ = assert(as.get() == 1)
          _ = assert(v1 == 1)
          v2 <- ctx.get[Key, Int](1, a).flatten
          _ = assert(as.get() == 1)
          _ = assert(v2 == 1)
        } yield ()
      }
    }

    test[IO].unsafeRunSync()
  }

  "context caches by key" in  {
    def test[F[_] : Concurrent: Parallel]: F[_] = {
      testContextCache[F].use { c =>
        val (ctx, a, b, as, bs) = (c.ctx, c.a, c.b, c.as, c.bs)
        for {
          va <- ctx.get[Key, Int](1, a).flatten
          vb <- ctx.get[Key, Int](2, b).flatten
          _ = assert(as.get() == 1)
          _ = assert(bs.get() == 10)
          _ = assert(va == 1)
          _ = assert(vb == 10)

          va2 <- ctx.get[Key, Int](1, a).flatten
          vb2 <- ctx.get[Key, Int](2, b).flatten
          _ = assert(as.get() == 1)
          _ = assert(bs.get() == 10)
          _ = assert(va2 == 1)
          _ = assert(vb2 == 10)
        } yield ()
      }
    }

    test[IO].unsafeRunSync()
  }

  "context correctly closes resources" in  {
    def test[F[_] : Concurrent: Parallel]: F[_] = {

      testContextCache[F].allocated.flatMap { case (c, close) =>
        val (ctx, a, b, as, bs) = (c.ctx, c.a, c.b, c.as, c.bs)
        for {
          _ <- ctx.get[Key, Int](1, a).flatten
          _ <- ctx.get[Key, Int](2, b).flatten
          _ = assert(as.get() == 1)
          _ = assert(bs.get() == 10)
          _ <- close
          _ = assert(as.get() == 0)
          _ = assert(bs.get() == 9)
          _ <- close
          _ = assert(as.get() == 0)
          _ = assert(bs.get() == 9)
        } yield ()
      }
    }

    test[IO].unsafeRunSync()
  }

  "context use correct deinitialization order" in  {
    def test[F[_] : Concurrent: Parallel]: F[_] = {

      testContextCache[F].allocated.flatMap { case (c, close) =>
        val ctx = c.ctx
        for {
          _ <- ctx.get[Key, Int](1, c.d1).flatten
          _ <- ctx.get[Key, Int](0, c.d).flatten
          _ <- ctx.get[Key, Int](2, c.d2).flatten
          _ <- ctx.get[Key, Int](31, c.d31).flatten
          _ <- ctx.get[Key, Int](3, c.d3).flatten
          _ <- ctx.get[Key, Int](32, c.d32).flatten
          _ <- close
          co = c.closeOrder.asScala
          _ = assert(co.head == 0)
          _ = assert(co.drop(1).take(3).toSet == Set(1, 2, 3))
          _ = assert(co.drop(4).toSet == Set(31, 32))
        } yield ()
      }
    }

    test[IO].unsafeRunSync()
  }

  "context set actually sets values" in {
    def test[F[_] : Concurrent: Parallel]: F[_] = {
      val emptyResource: Resource[F, LoadedValue[Key, Int]] = Resource.liftF(Sync[F].delay(throw new RuntimeException("should not be called!")))
      testContextCache[F].allocated.flatMap { case (c, close) =>
        val ctx = c.ctx
        for {
          _ <- ctx.get[Key, Int](1, c.d1).flatten
          _ <- ctx.get[Key, Int](0, c.d).flatten
          _ <- ctx.get[Key, Int](2, c.d2).flatten
          _ <- ctx.get[Key, Int](31, c.d31).flatten
          _ <- ctx.get[Key, Int](3, c.d3).flatten
          _ <- ctx.get[Key, Int](32, c.d32).flatten

          _ <- ctx.set(1, 100)
          v1 <- ctx.get[Key, Int](1, emptyResource).flatten
          _ = assert(v1 == 100)
          _ = assert(c.closeOrder.asScala.toSeq == Seq(1))
          _ = c.closeOrder.clear()

          _ <- ctx.set(3, 300)
          v2 <- ctx.get[Key, Int](3, emptyResource).flatten
          _ = assert(v2 == 300)
          _ = assert(c.closeOrder.asScala.toSet == Set(3, 31, 32))
        } yield ()
      }
    }

    test[IO].unsafeRunSync()
  }

  case class TestContextCache[F[_]](
      as: AtomicInteger,
      bs: AtomicInteger,
      a: Resource[F, LoadedValue[Key, Int]],
      b: Resource[F, LoadedValue[Key, Int]],
      d: Resource[F, LoadedValue[Key, Int]],
      d1: Resource[F, LoadedValue[Key, Int]],
      d2: Resource[F, LoadedValue[Key, Int]],
      d3: Resource[F, LoadedValue[Key, Int]],
      d31: Resource[F, LoadedValue[Key, Int]],
      d32: Resource[F, LoadedValue[Key, Int]],
      closeOrder: ConcurrentLinkedQueue[Int],
      ctx: ContextCache[F]
  )

  def testContextCache[F[_] : Concurrent: Parallel]: Resource[F, TestContextCache[F]] =
    ContextCache[F].flatMap { ctx =>
      Resource {
        Sync[F].delay {
          val as = new AtomicInteger()
          val bs = new AtomicInteger(9)
          val a = Resource[F, LoadedValue[Key, Int]] {
            Sync[F].delay {
              LoadedValue[Key, Int](as.incrementAndGet(), Set.empty) -> Sync[F].delay(as.decrementAndGet())
            }
          }
          val b = Resource[F, LoadedValue[Key, Int]] {
            Sync[F].delay {
              LoadedValue[Key, Int](bs.incrementAndGet(), Set.empty) -> Sync[F].delay(bs.decrementAndGet())
            }
          }

          val closeOrder = new ConcurrentLinkedQueue[Int]()
          def dd(value: Int, deps: Int*): Resource[F, LoadedValue[Key, Int]] = Resource[F, LoadedValue[Key, Int]] {
            Sync[F].delay {
              LoadedValue[Key, Int](value, deps.toSet) -> Sync[F].delay(closeOrder.add(value))
            }
          }

          TestContextCache[F](
            as = as,
            bs = bs,
            a = a,
            b = b,
            d = dd(0, 1, 2, 3),
            d1 = dd(1),
            d2 = dd(2),
            d3 = dd(3, 31, 32),
            d31 = dd(31),
            d32 = dd(32),
            ctx = ctx,
            closeOrder = closeOrder
          ) -> ().pure[F]
        }
      }
    }


}
