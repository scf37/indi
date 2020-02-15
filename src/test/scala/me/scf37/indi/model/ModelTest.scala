package me.scf37.indi.model

import java.util.concurrent.atomic.AtomicInteger

import cats.{FlatMap, Monad}
import cats.effect.{IO, Resource, Sync}
import cats.syntax.FlatMapOps
import me.scf37.indi.{Context, Indi, Init}
import org.scalatest.freespec.AnyFreeSpec

import scala.concurrent.ExecutionContext

class ModelTest extends AnyFreeSpec {

  "monad execution works" in {
    implicit val cs = IO.contextShift(ExecutionContext.global)
    val i1: Indi[IO, Int] = Indi(Resource.make(IO(1))(_ => IO()))
    val i2: Indi[IO, Int] = Indi.pure(2)

    val prog = Context[IO].use { ctx =>
      for {
        r1 <- i1.eval(ctx)
        _ = assert(r1 == 1)
        r2 <- i2.eval(ctx)
        _ = assert(r2 == 2)
        i3 = for {
          v1 <- i1
          v2 <- i2
        } yield (v1, v2)
        r3 <- i3.eval(ctx)
        _ = assert(r3 == (1, 2))

        i4 = Indi.par(i1, i2)
        r4 <- i3.eval(ctx)
        _ = assert(r4 == (1, 2))
      } yield ()
    }

    prog.unsafeRunSync()
  }

  "caching actually works" in {
    implicit val cs = IO.contextShift(ExecutionContext.global)
    val app = Application[Indi[IO, *], IO]("1")

    val prog = Context[IO].use { ctx =>
      for {
        // cache works
        web1 <- app.web.web.eval(ctx)
        web2 <- app.web.web.eval(ctx)
        _ = assert(web1 eq web2)
        // same context returns cached objects, used to construct web
        sa <- app.service.serviceA.eval(ctx)
        _ = assert(web1.toString contains sa.toString)
      } yield ()
    }

    prog.unsafeRunSync()
  }

  "all 3 parts of flatmap are cached" in {
    import Init.implicits._
    implicit val cs = IO.contextShift(ExecutionContext.global)
    implicit val m: Monad[Indi[IO, *]] = implicitly[Init[Indi[IO, *], IO]]

    val c1 = new AtomicInteger()
    val c2 = new AtomicInteger()
    val c3 = new AtomicInteger()

    InitOpsF[IO, Int](IO(c1.incrementAndGet())).init[Indi[IO, *]]

    val m1: Indi[IO, Int] = IO(c1.incrementAndGet()).init[Indi[IO, *]]
    val m2: Indi[IO, Int] = IO(c2.incrementAndGet()).init[Indi[IO, *]]
    val m3: Indi[IO, Int] = m.flatMap(m1) { _ =>
      c3.incrementAndGet()
      m2
    }

    val prog = Context[IO].use { ctx =>
      for {
        // cache works
        _ <- m3.eval(ctx)
        _ <- m3.eval(ctx)
        _ = assert(c1.get() == 1)
        _ = assert(c2.get() == 1)
        _ = assert(c3.get() == 1)
      } yield ()
    }

    prog.unsafeRunSync()
  }

  "deinitialization runs in correct order" in {
    implicit val cs = IO.contextShift(ExecutionContext.global)
    import Init.implicits._
    import cats.implicits._

    def app[I[_], F[_]: Sync](implicit i: Init[I, F]): I[Unit] = {
      val i1 = Resource.make(().pure[F])(_ => Sync[F].delay(println("i1"))).init
      val i2 = Resource.make(().pure[F])(_ => Sync[F].delay(println("i2"))).init
      val i3 = Resource.make(().pure[F])(_ => Sync[F].delay(println("i3")))
      val r = (i1, i2).mapR((a, b) => i3) // i3, (i1, i2)
      println("------")
      println(r)
      r
    }
    val prog = Context[IO].use { ctx =>
      app[Indi[IO, *], IO].eval(ctx)
    }

    prog.unsafeRunSync()

  }

  "deinitialization runs in correct order 2" in {
    implicit val cs = IO.contextShift(ExecutionContext.global)
    import cats.implicits._

    def app[F[_]: Sync]: Indi[F, Unit] = {
      val i1 = Indi(Resource.make(().pure[F])(_ => Sync[F].delay(println("i1"))))
      val i2 = Indi(Resource.make(().pure[F])(_ => Sync[F].delay(println("i2"))))
      val i3 = Indi(Resource.make(().pure[F])(_ => Sync[F].delay(println("i3"))))
      val i4 = Indi(Resource.make(().pure[F])(_ => Sync[F].delay(println("i4"))))
      val i5 = Indi(Resource.make(().pure[F])(_ => Sync[F].delay(println("i5"))))

      val r = Indi.par(i1, i2).flatMap(_ => i3)

      val r1 = for {
        _ <- i5
        _ <- Indi.par(i1.flatMap(_ => i2), i3.flatMap(_ => i4))
      } yield () // (i2, i4), (i1, i3), i5

      val r2 = for {
        _ <- Indi.par(i1, i2)
        _ <- Indi.par(i3, i4)
        _ <- i5
      } yield () // i5, (i3, i4), (i1, i2)

      val r3 = for {
        _ <- i1
        _ <- Indi.par(i2, Indi.par(i3, i4))
        _ <- i5
      } yield () // i5, (i2, i3, i4), i1

      println("------")
      r3
    }

    val app1 = Application[Indi[IO, *], IO]("1")

    println(app1.service.serviceA)

    val prog = Context[IO].use { ctx =>
      app[IO].eval(ctx)
    }

    prog.unsafeRunSync()
  }

  "mocks" in {
    implicit val cs = IO.contextShift(ExecutionContext.global)
    import cats.implicits._

    val app = Application[Indi[IO, *], IO]("1")

    val prog = Context[IO].use { ctx =>
      ctx.set(app.dao.daoA, new DaoA[IO] {
        override def toString: String = "DAOAMOCK"
      }) >>
        app.web.web.eval(ctx).map(println)
    }

    prog.unsafeRunSync()
  }

}
