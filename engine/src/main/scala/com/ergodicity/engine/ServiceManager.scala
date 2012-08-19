package com.ergodicity.engine

import akka.actor.{ActorRef, FSM, Actor}
import akka.util.duration._
import service.Service.{Start, Stop}
import service.{ServiceStopped, ServiceStarted, Service}
import collection.mutable
import akka.actor.FSM.{Failure, Normal}
import com.ergodicity.engine.ServiceManager.ServicesStartupTimedOut


sealed trait ServiceManagerState

object ServiceManagerState {

  case object Initializing extends ServiceManagerState

  case object Starting extends ServiceManagerState

  case object Active extends ServiceManagerState

  case object Stopping extends ServiceManagerState

}

sealed trait ServiceManagerData

object ServiceManagerData {

  case object Blank extends ServiceManagerData

  case class PendingServices(pending: Iterable[Service]) extends ServiceManagerData

}

case class RegisterService(service: Service, manager: ActorRef)

case object StartAllServices

case object StopAllServices

object ServiceManager {
  case class ServicesStartupTimedOut(pending: Iterable[Service]) extends RuntimeException
}

class ServiceManager extends Actor with FSM[ServiceManagerState, ServiceManagerData] {

  import ServiceManagerState._
  import ServiceManagerData._

  protected val services: mutable.Map[Service, ActorRef] = mutable.Map()

  startWith(Initializing, Blank)

  when(Initializing) {
    case Event(RegisterService(service, manager), _) if (!services.contains(service)) =>
      log.info("Register service " + service + "; Manager = " + manager)
      services(service) = manager
      stay()

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
}
