package com.ergodicity.engine

import akka.actor._
import akka.util.duration._
import service.Service.Stop
import service.{Service, ServiceStopped, ServiceStarted, ServiceId}
import akka.actor.FSM.Normal
import collection.mutable
import scalaz._
import Scalaz._

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

  private[Services] case object ServiceManager extends ServiceId

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
    def unlock(id: ServiceId): ServiceLock[A]
  }

  private[this] def Unlocked[A <: Service.Action] = new ServiceLock[A] {
    def unlock(id: ServiceId) = this
  }

  case class Locked[A <: Service.Action](required: NonEmptyList[ServiceId])(implicit val onUnlock: OnUnlock[A]) extends ServiceLock[A] {
    def unlock(id: ServiceId) = (required.list.filterNot(_ == id)) match {
      case x :: xs => copy(required = NonEmptyList(x, xs: _*))
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
      started(ServiceManager)
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
      services.values.foreach(_.ref ! Stop)
      goto(Stopping) using PendingServices(services.keys)
  }

  when(Stopping) {
    case Event(stopped@ServiceStopped(service), PendingServices(pending)) =>
      val remaining = pending.filterNot(_ == service)
      log.info("Service stopped = " + service + ", remaining = " + remaining)

      remaining.size match {
        case 0 => stop(Normal)
        case _ => stay() using PendingServices(remaining)
      }
  }

  override def preStart() {
    log.info("Registered services = " + services.keys)
    // Check that all required locks possible could be resolved
    services.foreach {
      case pair@(id, service) =>
        log.info(" - " + id + "; startLock = " + service.startLock + ", stopLock = " + service.stopLock)

        service.startLock match {
          case Locked(required) => required.foreach {
            required =>
              if (required != ServiceManager && !services.contains(required)) {
                log.error("Missing required service = " + required + ", for " + id)
                throw new IllegalStateException("Missing required service = " + required + ", for " + id)
              }
          }
          case lock => throw new IllegalStateException("Illegal lock state = " + lock)
        }
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
      throw new IllegalArgumentException("Service with id = " + id + " has been already registered")

    implicit val start = onStart(ref)
    implicit val stop = onStop(ref)

    services(id) = dependOn match {
      // Lock on Root service
      case Nil => ManagedService(ref, Locked[OnStart](NonEmptyList(ServiceManager)), Locked[OnStop](NonEmptyList(ServiceManager)))
      // Lock on given services
      case x :: xs => ManagedService(ref, Locked[OnStart](NonEmptyList(x, xs: _*)), Locked[OnStop](NonEmptyList(ServiceManager)))
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
