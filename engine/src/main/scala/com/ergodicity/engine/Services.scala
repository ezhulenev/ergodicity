package com.ergodicity.engine

import akka.actor._
import akka.util.duration._
import service.{Service, ServiceId}
import akka.actor.FSM.Normal
import collection.mutable
import scalaz._
import com.ergodicity.engine.ServicesState.PendingServices

case class ServiceStarted(service: ServiceId)

case class ServiceStopped(service: ServiceId)

sealed trait ServicesState

object ServicesState {

  case object Idle extends ServicesState

  case object Starting extends ServicesState

  case object Active extends ServicesState

  case object Stopping extends ServicesState

  case class PendingServices(pending: Iterable[ServiceId])

}

object Services {

  // Barriers for starting & stopping services
  private[Services] case object StartUp extends ServiceId

  private[Services] case object ShutDown extends ServiceId

  // Commands
  case object StartAllServices

  case object StopAllServices

  case class ResolveService(id: ServiceId)

  case class ServiceRef(id: ServiceId, ref: ActorRef)

  // Possible failures

  case class ServicesStartupTimedOut(pending: Iterable[ServiceId]) extends RuntimeException

  class ServiceFailedException(service: ServiceId, message: String) extends RuntimeException

  class ServiceNotFoundException(service: ServiceId) extends RuntimeException

  // Managing Services dependencies
  object ServiceBarrier {
    def apply(pending: NonEmptyList[ServiceId])(onReady: => Unit): ServiceBarrier =
      new Waiting(pending)(onReady)
  }

  sealed trait ServiceBarrier {
    def waitFor(id: ServiceId): ServiceBarrier

    def ready(id: ServiceId): ServiceBarrier

    def toSeq: Seq[ServiceId]
  }

  private[this] case object Stale extends ServiceBarrier {
    val toSeq = Seq.empty[ServiceId]

    def waitFor(id: ServiceId): ServiceBarrier = throw new UnsupportedOperationException

    def ready(id: ServiceId) = this
  }

  private[this] class Waiting(pending: NonEmptyList[ServiceId])(onReady: => Unit) extends ServiceBarrier {
    def toSeq = pending.list.toSeq

    def waitFor(id: ServiceId) = new Waiting(pending :::> (id :: Nil))(onReady)

    def ready(id: ServiceId) = (pending.list.filterNot(_ == id)) match {
      case x :: xs => new Waiting(NonEmptyList(x, xs: _*))(onReady)
      case Nil => onReady; Stale
    }
  }

  case class ManagedService(ref: ActorRef, startBarrier: ServiceBarrier, stopBarrier: ServiceBarrier) {
    def start(id: ServiceId) = copy(startBarrier = startBarrier.ready(id))

    def stop(id: ServiceId) = copy(stopBarrier = stopBarrier.ready(id))

    def waitFor(id: ServiceId) = copy(stopBarrier = stopBarrier.waitFor(id))
  }

  trait Reporter {
    def serviceStarted(implicit id: ServiceId)

    def serviceStopped(implicit id: ServiceId)
  }

}

class Services extends Actor with LoggingFSM[ServicesState, PendingServices] {

  import Services._
  import ServicesState._

  implicit val Self = this

  protected[engine] val services = mutable.Map.empty[ServiceId, ManagedService]

  // Stop all services on any failed
  override def supervisorStrategy() = AllForOneStrategy() {
    case _: ServiceFailedException => SupervisorStrategy.Stop
  }

  implicit val reporter = new Reporter {
    def serviceStopped(implicit id: ServiceId) {
      self ! ServiceStopped(id)
    }

    def serviceStarted(implicit id: ServiceId) {
      self ! ServiceStarted(id)
    }
  }

  startWith(Idle, PendingServices(Nil))

  when(Idle) {
    case Event(StartAllServices, _) =>
      log.info("Start all services = " + services.keys)
      started(StartUp)
      goto(Starting) using PendingServices(services.keys)
  }

  when(Starting, stateTimeout = 30.seconds) {
    case Event(ServiceStarted(service), PendingServices(pending)) =>
      val remaining = pending.filterNot(_ == service)
      log.info("Service started = " + service + ", remaining = " + remaining)
      started(service)

      remaining.size match {
        case 0 => goto(Active) using PendingServices(Nil)
        case _ => stay() using PendingServices(remaining)
      }

    case Event(FSM.StateTimeout, PendingServices(remaining)) =>
      throw new ServicesStartupTimedOut(remaining)
  }

  when(Active) {
    case Event(StopAllServices, _) =>
      log.info("Stop all services = " + services.keys)
      stopped(ShutDown)
      goto(Stopping) using PendingServices(services.keys)
  }

  when(Stopping, stateTimeout = 30.seconds) {
    case Event(ServiceStopped(service), PendingServices(pending)) =>
      val remaining = pending.filterNot(_ == service)
      log.info("Service stopped = " + service + ", remaining = " + remaining)
      stopped(service)
      remaining.size match {
        case 0 => stop(Normal)
        case _ => stay() using PendingServices(remaining)
      }

    case Event(FSM.StateTimeout, PendingServices(pending)) =>
      stop(FSM.Shutdown)
  }

  whenUnhandled {
    case Event(ResolveService(id), _) =>
      sender ! ServiceRef(id, service(id))
      stay()
  }

  override def preStart() {
    log.info("Registered services = " + services.keys)
    services.foreach {
      case (id, service) =>
        log.info(" - " + id + "; startBarrier = " + service.startBarrier.toSeq + ", stopBarrier = " + service.stopBarrier.toSeq)
    }
  }

  protected[engine] def register(props: Props, dependOn: Seq[ServiceId] = Seq())(implicit id: ServiceId) {
    log.info("Register service, Id = " + id + ", depends on = " + dependOn)

    if (services.contains(id))
      throw new IllegalStateException("Service with id = " + id + " has been already registered")

    dependOn.foreach {
      required =>
        if (!services.contains(required)) {
          log.error("Missing required service = " + required + ", for " + id)
          throw new IllegalStateException("Missing required service = " + required + ", for " + id)
        }
    }

    val ref = context.actorOf(props, id.toString)

    val startBarrier = ServiceBarrier(NonEmptyList(StartUp, dependOn: _*)) {
      ref ! Service.Start
    }
    val stopBarrier = ServiceBarrier(NonEmptyList(ShutDown)) {
      ref ! Service.Stop
    }

    services(id) = ManagedService(ref, startBarrier, stopBarrier)

    // Update stop barrier on existing services
    services.transform {
      case (i, service) if (dependOn contains i) => service.waitFor(id)
      case (_, service) => service
    }
  }

  def service(id: ServiceId): ActorRef = services.get(id).map(_.ref).getOrElse(throw new ServiceNotFoundException(id))

  private def started(started: ServiceId) {
    services.transform {
      case (_, service) => service.start(started)
    }
  }

  private def stopped(stopped: ServiceId) {
    services.transform {
      case (_, service) => service.stop(stopped)
    }
  }

}
