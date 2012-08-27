package com.ergodicity.engine

import akka.actor._
import akka.util.duration._
import service.{Service, ServiceStopped, ServiceStarted, ServiceId}
import akka.actor.FSM.Normal
import collection.mutable
import scalaz._

sealed trait ServicesState

object ServicesState {

  case object Idle extends ServicesState

  case object Starting extends ServicesState

  case object Active extends ServicesState

  case object Stopping extends ServicesState

}

sealed trait ServicesData

object ServicesData {

  case object Blank extends ServicesData

  case class PendingServices(pending: Iterable[ServiceId]) extends ServicesData

}

object Services {

  // Barriers for starting & stopping services
  private[Services] case object StartUp extends ServiceId

  private[Services] case object ShutDown extends ServiceId

  // Commands
  case object StartAllServices

  case object StopAllServices

  // Possible failures

  case class ServicesStartupTimedOut(pending: Iterable[ServiceId]) extends RuntimeException

  class ServiceFailedException(service: ServiceId, message: String) extends RuntimeException

  class ServiceNotFoundException(service: ServiceId) extends RuntimeException

  // Managing Services dependencies
  type OnStart = Service.Start.type
  type OnStop = Service.Stop.type

  sealed trait OnUnlock[A <: Service.Action] {
    def apply()
  }

  sealed trait ServiceLock[A <: Service.Action] {
    def lock(id: ServiceId): ServiceLock[A]

    def unlock(id: ServiceId): ServiceLock[A]

    def toSeq: Seq[ServiceId]
  }

  private[this] def Unlocked[A <: Service.Action] = new ServiceLock[A] {
    val toSeq = Seq.empty[ServiceId]

    def lock(id: ServiceId): ServiceLock[A] = throw new UnsupportedOperationException

    def unlock(id: ServiceId) = this
  }

  case class Locked[A <: Service.Action](locks: NonEmptyList[ServiceId])(implicit val onUnlock: OnUnlock[A]) extends ServiceLock[A] {
    def toSeq = locks.list.toSeq

    def lock(id: ServiceId) = copy(id <:: locks)

    def unlock(id: ServiceId) = (locks.list.filterNot(_ == id)) match {
      case x :: xs => copy(locks = NonEmptyList(x, xs: _*))
      case Nil => onUnlock(); Unlocked[A]
    }
  }

  case class ManagedService(ref: ActorRef, startLock: ServiceLock[Service.Start.type], stopLock: ServiceLock[Service.Stop.type])

  def onStart(ref: ActorRef) = new OnUnlock[Service.Start.type] {
    def apply() {
      ref ! Service.Start
    }
  }

  def onStop(ref: ActorRef) = new OnUnlock[Service.Stop.type] {
    def apply() {
      ref ! Service.Stop
    }
  }
}

class Services extends Actor with LoggingFSM[ServicesState, ServicesData] {

  import Services._
  import ServicesState._
  import ServicesData._

  implicit val Self = this

  protected[engine] val services = mutable.Map.empty[ServiceId, ManagedService]

  // Stop all services on any failed
  override def supervisorStrategy() = AllForOneStrategy() {
    case _: ServiceFailedException => SupervisorStrategy.Stop
  }

  startWith(Idle, Blank)

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
        case 0 => goto(Active) using Blank
        case _ => stay() using PendingServices(remaining)
      }

    case Event(FSM.StateTimeout, PendingServices(pending)) => throw new ServicesStartupTimedOut(pending)
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

  override def preStart() {
    log.info("Registered services = " + services.keys)
    services.foreach {
      case (id, service) =>
        log.info(" - " + id + "; startLock = " + service.startLock.toSeq + ", stopLock = " + service.stopLock.toSeq)
    }
  }

  def serviceStarted(implicit service: ServiceId) {
    self ! ServiceStarted(service)
  }

  def serviceStopped(implicit service: ServiceId) {
    self ! ServiceStopped(service)
  }

  protected[engine] def register(ref: ActorRef, dependOn: Seq[ServiceId] = Seq())(implicit id: ServiceId) {
    log.info("Register service, Id = " + id + ", ref = " + ref + ", depends on = " + dependOn)

    if (services.contains(id))
      throw new IllegalStateException("Service with id = " + id + " has been already registered")

    dependOn.foreach {
      required =>
        if (!services.contains(required)) {
          log.error("Missing required service = " + required + ", for " + id)
          throw new IllegalStateException("Missing required service = " + required + ", for " + id)
        }
    }

    implicit val start = onStart(ref)
    implicit val stop = onStop(ref)

    val startLock = Locked[OnStart](NonEmptyList(StartUp, dependOn: _*))
    val stopLock = Locked[OnStop](NonEmptyList(ShutDown))

    services(id) = ManagedService(ref, startLock, stopLock)

    // Update stop locks on existing services
    services.transform {
      case (i, service) if (dependOn contains i) =>
        service.copy(stopLock = service.stopLock.lock(id))
      case (i, service) => service
    }
  }

  def service(id: ServiceId): ActorRef = services.get(id).map(_.ref).getOrElse(throw new ServiceNotFoundException(id))

  private def started(unlocked: ServiceId) {
    services.transform {
      case (_, service) =>
        service.copy(startLock = service.startLock.unlock(unlocked))
    }
  }

  private def stopped(unlocked: ServiceId) {
    services.transform {
      case (_, service) =>
        service.copy(stopLock = service.stopLock.unlock(unlocked))
    }
  }

}
