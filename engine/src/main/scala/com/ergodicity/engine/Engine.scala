package com.ergodicity.engine

import akka.actor._
import com.ergodicity.engine.Engine.StartEngine
import service.ServiceId
import akka.actor.FSM.{Transition, UnsubscribeTransitionCallBack, CurrentState, SubscribeTransitionCallBack}
import ru.micexrts.cgate.{Listener => CGListener, Connection => CGConnection, CGateException, ISubscriber}
import com.ergodicity.cgate.config.Replication
import akka.actor.SupervisorStrategy.Stop
import strategy.Strategy


class ServiceFailedException(service: ServiceId, message: String) extends RuntimeException

object Engine {

  case object StartEngine

  case object StopEngine

  case class CareOf(service: ServiceId, msg: Any)

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

  def registerService(service: ServiceId, manager: ActorRef) {
    ServiceManager ! RegisterService(service, manager)
  }

  def StrategyManager: ActorRef

  def registerStrategy(strategy: Strategy, manager: ActorRef) {
    StrategyManager ! RegisterStrategy(strategy, manager)
  }

  override val supervisorStrategy = AllForOneStrategy() {
    case _: CGateException      ⇒ Stop
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

    case Event(Transition(_, _, ServiceManagerState.Active), _) =>
      ServiceManager ! UnsubscribeTransitionCallBack(self)
      goto(Active)
  }

  when(Active) {
    case Event(StopEvent, _) =>
      ServiceManager ! StopAllServices
      stay()
  }

  onTransition {
    case Starting -> Active => log.info("Engine started")
  }
}

object Components {

  // Replication schemes
  trait FutInfoReplication {
    def futInfoReplication: Replication
  }

  trait OptInfoReplication {
    def optInfoReplication: Replication
  }

  trait PosReplication {
    def posReplication: Replication
  }

  // Listener provider
  trait CreateListener {
    def listener(connection: CGConnection, config: String, subscriber: ISubscriber): CGListener
  }

  trait CreateListenerComponent extends CreateListener {
    def listener(connection: CGConnection, config: String, subscriber: ISubscriber) = new CGListener(connection, config, subscriber)
  }


  // Service Management
  trait ManagedServices {
    this: {def context: ActorContext} =>

    val ServiceManager = context.actorOf(Props(new com.ergodicity.engine.ServiceManager()), "ServiceManager")
  }

  // Service Management
  trait ManagedStrategies {
    this: {def context: ActorContext} =>

    val StrategyManager = context.actorOf(Props(new com.ergodicity.engine.StrategyManager()), "StrategyManager")
  }

}