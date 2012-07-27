package com.ergodicity.engine

import akka.actor._
import akka.util.Duration
import com.ergodicity.engine.Engine.{CareOf, StartEngine}
import service.{ServiceFailed, ServicePassivated, ServiceActivated, Service}
import scala.collection.mutable


object Engine {

  case object StartEngine

  case object StopEngine

  case class CareOf(service: Service, msg: Any)

}

sealed trait EngineState

object EngineState {

  case object Idle extends EngineState

  case object Starting extends EngineState

  case object Active extends EngineState

  case object Stopping extends EngineState

}

class Engine extends Actor with FSM[EngineState, Unit] {
  engine =>

  type ServiceFunction = scala.PartialFunction[Event, Unit]

  import EngineState._

  private val services: mutable.Map[Service, ActorRef] = mutable.Map()

  def registerService(service: Service, serviceRef: ActorRef = context.system.deadLetters) {
    if (services contains service) {
      throw new IllegalStateException("Service " + service + " already registered")
    } else {
      log.info("Register service " + service + "; Service Ref = " + serviceRef)
      services(service) = serviceRef
    }
  }

  startWith(Idle, ())

  when(Idle) {
    case Event(StartEngine, _) =>
      services.values.foreach(_ ! Service.Start)
      stay()
  }

    whenUnhandled {
      case Event(activated@ServiceActivated(service), _) =>
        services.values.filter(_ != service).foreach(_ ! activated)
        stay()

      case Event(passivated@ServicePassivated(service), _) =>
        services.values.filter(_ != service).foreach(_ ! passivated)
        stay()

      case Event(failed@ServiceFailed(service), _) =>
        services.values.filter(_ != service).foreach(_ ! failed)
        stay()
    }
}