package com.ergodicity.engine

import akka.actor._
import com.ergodicity.engine.Engine.StartEngine
import service.Service
import akka.event.LoggingAdapter
import akka.actor.FSM.{UnsubscribeTransitionCallBack, CurrentState, SubscribeTransitionCallBack}


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
}


trait Engine {
  def log: LoggingAdapter

  def ServiceManager: ActorRef

  def registerService(service: Service, manager: ActorRef) {
    ServiceManager ! RegisterService(service, manager)
  }
}

class EngineImpl extends Actor with FSM[EngineState, EngineData] with Engine {

  import EngineState._
  import EngineData._

  val ServiceManager = context.actorOf(Props(new ServiceManager), "ServiceManager")

  startWith(Idle, Blank)

  when(Idle) {
    case Event(StartEngine, _) =>
      ServiceManager ! StartAllServices
      ServiceManager ! SubscribeTransitionCallBack(self)
      goto(Starting)
  }

  when(Starting) {
    case Event(CurrentState(ServiceManager, ServiceManagerState.Active), _) =>
      ServiceManager ! UnsubscribeTransitionCallBack(self)
      goto(Active)
  }

  when(Active) {
    case Event(StopEvent, _) =>
      ServiceManager ! StopAllServices
      stay()
  }
}