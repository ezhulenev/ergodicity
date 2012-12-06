package com.ergodicity.engine

import akka.actor.FSM.{UnsubscribeTransitionCallBack, SubscribeTransitionCallBack, Transition}
import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.util.Timeout
import akka.util.duration._
import com.ergodicity.cgate.ListenerBinding
import com.ergodicity.cgate.config.Replication
import com.ergodicity.engine.Engine.{StartTrading, StartEngine}
import com.ergodicity.engine.Services.StartServices
import com.ergodicity.engine.StrategyEngine.StartStrategies
import ru.micexrts.cgate.{Listener => CGListener, Connection => CGConnection, CGateException}


object Engine {
  val ReplicationDispatcher = "engine.dispatchers.replicationDispatcher"

  val TradingDispatcher = "engine.dispatchers.publisherDispatcher"

  case object StartEngine

  case object StartTrading

  case object StopEngine
}

sealed trait EngineState

object EngineState {

  case object Idle extends EngineState

  case object StartingServices extends EngineState

  case object StartingStrategies extends EngineState

  case object Ready extends EngineState

  case object Active extends EngineState

  case object StoppingStrategies extends EngineState

  case object StoppingServices extends EngineState

}

sealed trait EngineData

object EngineData {

  case object Blank extends EngineData

}

trait Engine extends Actor with FSM[EngineState, EngineData] {

  implicit val timeout = Timeout(1.second)

  override val supervisorStrategy = AllForOneStrategy() {
    case _: CGateException ⇒ Stop
  }

  import EngineData._
  import EngineState._

  def Services: ActorRef
  def Strategies: ActorRef

  startWith(Idle, Blank)

  when(Idle) {
    case Event(StartEngine, _) =>
      log.info("Staring engine")
      Services ! StartServices
      Services ! SubscribeTransitionCallBack(self)
      goto(StartingServices)
  }

  when(StartingServices) {
    case Event(Transition(_, _, ServicesState.Active), _) =>
      Services ! UnsubscribeTransitionCallBack(self)
      Strategies ! StartStrategies
      goto(StartingStrategies)

  }

  when(StartingStrategies) {
    case Event(Transition(_, _, StrategyEngineState.StrategiesReady), _) =>
      Strategies ! UnsubscribeTransitionCallBack(self)
      goto(Ready)
  }

  when(Ready) {
    case Event(StartTrading, _) =>
      goto(Active)
  }

  when(Active) {
    case Event(StopEvent, _) => stay()
  }

  onTransition {
    case StartingServices -> Ready => log.info("Engine ready")
  }
}

object ReplicationScheme {
  trait FutInfoReplication {
    def futInfoReplication: Replication
  }

  trait OptInfoReplication {
    def optInfoReplication: Replication
  }

  trait PosReplication {
    def posReplication: Replication
  }

  trait FutOrdersReplication {
    def futOrdersReplication: Replication
  }

  trait OptOrdersReplication {
    def optOrdersReplication: Replication
  }

  trait FutTradesReplication {
    def futTradesReplication: Replication
  }

  trait OptTradesReplication {
    def optTradesReplication: Replication
  }

  trait FutOrderBookReplication {
    def futOrderbookReplication: Replication
  }

  trait OptOrderBookReplication {
    def optOrderbookReplication: Replication
  }

  trait OrdLogReplication {
    def ordLogReplication: Replication
  }
}

object Listener {
  trait FutInfoListener {
    def futInfoListener: ListenerBinding
  }

  trait OptInfoListener {
    def optInfoListener: ListenerBinding
  }

  trait FutOrderBookListener {
    def futOrderbookListener: ListenerBinding
  }

  trait OptOrderBookListener {
    def optOrderbookListener: ListenerBinding
  }

  trait OrdLogListener {
    def ordLogListener: ListenerBinding
  }

  trait PosListener {
    def posListener: ListenerBinding
  }

  trait FutTradesListener {
    def futTradesListener: ListenerBinding
  }

  trait OptTradesListener {
    def optTradesListener: ListenerBinding
  }

  trait FutOrdersListener {
    def futOrdersListener: ListenerBinding
  }

  trait OptOrdersListener {
    def optOrdersListener: ListenerBinding
  }

  trait RepliesListener {
    def repliesListener: ListenerBinding
  }
}