package com.ergodicity.engine

import akka.actor._
import com.ergodicity.engine.Engine.StartEngine
import service.{ServicePassivated, ServiceActivated, Service}
import scala.collection.mutable
import akka.event.LoggingAdapter


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

sealed trait EngineData

object EngineData {

  case object Blank extends EngineData

  case class Activating(pending: Iterable[Service]) extends EngineData

}


trait Engine {
  def log: LoggingAdapter

  def ServiceTracker: ActorRef

  protected val services: mutable.Map[Service, ActorRef] = mutable.Map()

  def registerService(service: Service, serviceRef: ActorRef) {
    if (services contains service) {
      throw new IllegalStateException("Service " + service + " already registered")
    } else {
      log.info("Register service " + service + "; Service Ref = " + serviceRef)
      services(service) = serviceRef
    }
  }
}

class AkkaEngine extends Actor with FSM[EngineState, EngineData] with Engine {
  import EngineState._
  import EngineData._

  val ServiceTracker = self

  startWith(Idle, Blank)

  when(Idle) {
    case Event(StartEngine, _) =>
      services.values.foreach(_ ! Service.Start)
      goto(Starting) using Activating(services.keys)
  }

  when(Starting) {
    case Event(activated@ServiceActivated(service), Activating(pending)) =>
      services.filter(_._1 != service).foreach(_._2 ! activated)
      pending.filterNot(_ == service) match {
        case Nil => goto(Active) using Blank
        case remaining => stay() using Activating(remaining)
      }
  }

  when(Active) {
    case Event("Ebaka", _) => stay()
  }

  whenUnhandled {
    case Event(passivated@ServicePassivated(service), _) =>
      services.values.filter(_ != service).foreach(_ ! passivated)
      stay()
  }
}