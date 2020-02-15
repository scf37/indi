package me.scf37.indi.model

import java.util.Objects

import cats.Applicative
import cats.effect.Resource
import cats.implicits._
import me.scf37.indi.Init

trait DaoA[F[_]]
trait DaoB[F[_]]
trait ServiceA[F[_]]
trait ServiceB[F[_]]
trait ServiceAB[F[_]]
trait Web[F[_]]

object DaoA {
  // can't use different initialization effect here - simply because initialization may call already instantiated services working with F
  def apply[F[_]: Applicative](s: String): Resource[F, DaoA[F]] = Resource.make(new DaoA[F] {
    override def toString: String = s"DaoA-$s ${Objects.hashCode(this)}"
  }.pure[F])(_ => ().pure[F].map(_ => println("DaoA close")))
}

object DaoB {
  def apply[F[_]: Applicative](s: String): Resource[F, DaoB[F]] = Resource.make(new DaoB[F]{
    override def toString: String = s"DaoB-$s ${Objects.hashCode(this)}"
  }.pure[F])(_ => ().pure[F].map(_ => println("DaoB close")))
}
object ServiceA {
  def apply[F[_]: Applicative](s: String, dao: DaoA[F]): Resource[F, ServiceA[F]] = Resource.make(new ServiceA[F]{
    override def toString: String = s"ServiceA-$s $dao ${Objects.hashCode(this)}"
  }.pure[F])(_ => ().pure[F].map(_ => println("ServiceA close")))
}
object ServiceB {
  def apply[F[_]: Applicative](s: String, dao: DaoB[F]): Resource[F, ServiceB[F]] = Resource.make(new ServiceB[F]{
    override def toString: String = s"ServiceB-$s $dao ${Objects.hashCode(this)}"
  }.pure[F])(_ => ().pure[F].map(_ => println("ServiceB close")))
}
object ServiceAB {
  def apply[F[_]: Applicative](s: String, daoA: DaoA[F], daoB: DaoB[F]): Resource[F, ServiceAB[F]] = Resource.make(new ServiceAB[F]{
    override def toString: String = s"ServiceAB-$s $daoA $daoB ${Objects.hashCode(this)}"
  }.pure[F])(_ => ().pure[F].map(_ => println("ServiceAB close")))
}
object Web {
  def apply[F[_]: Applicative](s: String, serviceA: ServiceA[F], serviceB: ServiceB[F], serviceAB: ServiceAB[F]): Resource[F, Web[F]] = Resource.make(new Web[F] {
    override def toString: String = s"Web-$s $serviceA $serviceB $serviceAB ${Objects.hashCode(this)}"
  }.pure[F])(_ => ().pure[F].map(_ => println("Web close")))
}

case class DaoModule[I[_], F[_]](daoA: I[DaoA[F]], daoB: I[DaoB[F]])

object DaoModule {
  import Init.implicits._
  def apply[I[_], F[_]: Applicative](conf: String)(implicit Init: Init[I, F]): DaoModule[I, F] = {
    DaoModule[I, F](
      daoA = DaoA[F](conf).init,
      daoB = DaoB[F](conf).init
    )
  }
}

case class ServiceModule[I[_], F[_]](serviceA: I[ServiceA[F]], serviceB: I[ServiceB[F]], serviceAB: I[ServiceAB[F]])

object ServiceModule {
  import Init.implicits._
  def apply[I[_], F[_]: Applicative](conf: String, dao: DaoModule[I, F])(implicit Init: Init[I, F]): ServiceModule[I, F] = {
    ServiceModule[I, F](
      serviceA = dao.daoA.mapR(dao => ServiceA[F](conf, dao)),
      serviceB = dao.daoB.mapR(dao => ServiceB[F](conf, dao)),
      serviceAB = (dao.daoA, dao.daoB).mapR { (daoA, daoB) =>
        ServiceAB[F](conf, daoA, daoB)
      }
    )

  }
}

case class WebModule[I[_], F[_]](web: I[Web[F]])
object WebModule {
  import Init.implicits._

  def apply[I[_], F[_]: Applicative](conf: String, service: ServiceModule[I, F])(implicit Init: Init[I, F]): WebModule[I, F] = {
    WebModule[I, F](
      web = (service.serviceAB, service.serviceB, service.serviceA).mapR { (serviceAB, serviceB, serviceA) =>
        Web(conf, serviceA, serviceB, serviceAB)
      }
    )
  }
}

case class Application[I[_], F[_]](
    dao: DaoModule[I, F],
    service: ServiceModule[I, F],
    web: WebModule[I, F]
)

object Application {
  def apply[I[_], F[_]: Applicative](conf: String)(implicit Init: Init[I, F]): Application[I, F] = {
    val dao = DaoModule[I, F](conf)
    val service = ServiceModule[I, F](conf, dao)
    val web = WebModule[I, F](conf, service)

    Application(
      dao = dao,
      service = service,
      web = web,
    )
  }
}


