package com.ergodicity.engine

import akka.actor._
import akka.pattern.ask
import akka.util.duration._
import com.ergodicity.engine.Engine.StartEngine
import service.ServiceId
import akka.actor.FSM.{Transition, UnsubscribeTransitionCallBack, CurrentState, SubscribeTransitionCallBack}
import ru.micexrts.cgate.{Listener => CGListener, Connection => CGConnection, CGateException, ISubscriber}
import akka.actor.SupervisorStrategy.Stop
import strategy.Strategy
import akka.dispatch.Await
import com.ergodicity.engine.ServiceManager.{ServiceRef, GetServiceRef}
import akka.util.Timeout
import com.ergodicity.cgate.config.Replication


object Engine {

  case object StartEngine

  case object StopEngine

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
  engine: Actor with FSM[EngineState, EngineData] with Services =>

  implicit val timeout = Timeout(1.second)

  override val supervisorStrategy = AllForOneStrategy() {
    case _: CGateException ⇒ Stop
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

object Replication {
  trait FutInfoReplication {
    def futInfoReplication: Replication
  }

  trait OptInfoReplication {
    def optInfoReplication: Replication
  }

  trait PosReplication {
    def posReplication: Replication
  }
}

object Components {
  trait CreateListener {
    def listener(connection: CGConnection, config: String, subscriber: ISubscriber): CGListener
  }

  trait CreateListenerComponent extends CreateListener {
    def listener(connection: CGConnection, config: String, subscriber: ISubscriber) = new CGListener(connection, config, subscriber)
  }

}

class ServiceFailedException(service: ServiceId, message: String) extends RuntimeException

class ServiceNotFoundException(service: ServiceId) extends RuntimeException

trait Services {
  engine: Engine =>

  def ServiceManager: ActorRef

  def registerService(service: ServiceId, manager: ActorRef) {
    ServiceManager ! RegisterService(service, manager)
  }

  def service(service: ServiceId) = Await.result((ServiceManager ? GetServiceRef(service)).mapTo[ServiceRef], 1.second)
}

trait Strategies {
  def StrategyEngine: ActorRef

  def registerStrategy(strategy: Strategy, manager: ActorRef) {
    StrategyEngine ! RegisterStrategy(strategy, manager)
  }
}

trait ManagedServices extends Services {
  this: Engine =>

  val ServiceManager = context.actorOf(Props(new com.ergodicity.engine.ServiceManager()), "ServiceManager")
}

trait ManagedStrategies extends Strategies {
  this: {def context: ActorContext} =>

  val StrategyEngine = context.actorOf(Props(new com.ergodicity.engine.StrategyEngine()), "StrategyEngine")
}