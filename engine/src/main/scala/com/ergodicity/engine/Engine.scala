package com.ergodicity.engine

import akka.actor._
import com.ergodicity.engine.Engine.StartEngine
import service.Service
import akka.actor.FSM.{UnsubscribeTransitionCallBack, CurrentState, SubscribeTransitionCallBack}
import ru.micexrts.cgate.{Listener => CGListener, ISubscriber, Connection => CGConnection}
import com.ergodicity.cgate.config.Replication


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


trait Engine extends Actor with FSM[EngineState, EngineData] {
  def ServiceManager: ActorRef

  def registerService(service: Service, manager: ActorRef) {
    ServiceManager ! RegisterService(service, manager)
  }

  import EngineState._
  import EngineData._

  startWith(Idle, Blank)

  when(Idle) {
    case Event(StartEngine, _) =>
      ServiceManager ! StartAllServices
      ServiceManager ! SubscribeTransitionCallBack(self)
      goto(Starting)
  }

  when(Starting) {
    case Event(CurrentState(_, ServiceManagerState.Active), _) =>
      ServiceManager ! UnsubscribeTransitionCallBack(self)
      goto(Active)
  }

  when(Active) {
    case Event(StopEvent, _) =>
      ServiceManager ! StopAllServices
      stay()
  }
}

object Components {

  trait CreateListener {
    def listener(connection: CGConnection, config: String, subscriber: ISubscriber): CGListener
  }

  trait FutInfoReplication {
    def futInfoReplication: Replication
  }

  trait OptInfoReplication {
    def optInfoReplication: Replication
  }

  trait Manager {
    this: {def context: ActorContext} =>

    val ServiceManager = context.actorOf(Props(new com.ergodicity.engine.ServiceManager()), "ServiceManager")
  }

}