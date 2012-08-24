package com.ergodicity.engine

import akka.actor.{ActorRef, FSM, Actor}
import akka.util.duration._
import service.Service.{Start, Stop}
import service.{ServiceStopped, ServiceStarted, ServiceId}
import collection.mutable
import akka.actor.FSM.Normal
import com.ergodicity.engine.Services._
import com.ergodicity.engine.Services.ServicesStartupTimedOut


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

  case object StartAllServices

  case object StopAllServices

  case class ServicesStartupTimedOut(pending: Iterable[ServiceId]) extends RuntimeException

  class ServiceFailedException(service: ServiceId, message: String) extends RuntimeException

  class ServiceNotFoundException(service: ServiceId) extends RuntimeException

}

class Services extends Actor with FSM[ServicesState, ServicesData] {

  import ServicesState._
  import ServicesData._

  implicit val Self = this

  protected val services: mutable.Map[ServiceId, ActorRef] = mutable.Map()


  startWith(Idle, Blank)

  when(Idle) {
    case Event(StartAllServices, _) =>
      log.info("Start all services = " + services.keys)
      services.values.foreach(_ ! Start)
      goto(Starting) using PendingServices(services.keys)
  }

  when(Starting, stateTimeout = 30.seconds) {
    case Event(started@ServiceStarted(service), PendingServices(pending)) =>
      val remaining = pending.filterNot(_ == service)
      log.info("Service started = " + service + ", remaining = " + remaining)
      services.filter(_._1 != service).foreach(_._2 ! started)
      remaining.size match {
        case 0 => goto(Active) using Blank
        case _ => stay() using PendingServices(remaining)
      }

    case Event(FSM.StateTimeout, PendingServices(pending)) => throw new ServicesStartupTimedOut(pending)
  }

  when(Active) {
    case Event(StopAllServices, _) =>
      log.info("Stop all services = " + services.keys)
      services.values.foreach(_ ! Stop)
      goto(Stopping) using PendingServices(services.keys)
  }

  when(Stopping) {
    case Event(stopped@ServiceStopped(service), PendingServices(pending)) =>
      services.filter(_._1 != service).foreach(_._2 ! stopped)
      val remaining = pending.filterNot(_ == service)
      remaining.size match {
        case 0 => stop(Normal)
        case _ => stay() using PendingServices(remaining)
      }
  }

  override def preStart() {
    log.info("Registered services = " + services.keys)
  }

  def serviceFailed(message: String)(implicit service: ServiceId): Nothing = {
    throw new ServiceFailedException(service, "Connection unexpected terminated")
  }

  def serviceStarted(implicit service: ServiceId) {
    self ! ServiceStarted(service)
  }

  def serviceStopped(implicit service: ServiceId) {
    self ! ServiceStopped(service)
  }

  protected def register(service: ActorRef)(implicit id: ServiceId) {
    if (services contains id) throw new IllegalArgumentException("Service already registered")
    services(id) = service
  }

  def service(id: ServiceId) = services.getOrElse(id, throw new ServiceNotFoundException(id))
}
