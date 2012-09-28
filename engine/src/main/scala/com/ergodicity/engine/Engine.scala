package com.ergodicity.engine

import akka.actor._
import akka.util.duration._
import com.ergodicity.engine.Engine.StartEngine
import akka.actor.FSM.{Transition, CurrentState}
import ru.micexrts.cgate.{Listener => CGListener, Connection => CGConnection, CGateException, ISubscriber}
import akka.actor.SupervisorStrategy.Stop
import akka.util.Timeout
import com.ergodicity.cgate.config.Replication


object Engine {
  val ReplicationDispatcher = "engine.dispatchers.replicationDispatcher"

  val ReplyDispatcher = "engine.dispatchers.replyDispatcher"

  val PublisherDispatcher = "engine.dispatchers.publisherDispatcher"

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

  implicit val timeout = Timeout(1.second)

  override val supervisorStrategy = AllForOneStrategy() {
    case _: CGateException ⇒ Stop
  }

  import EngineState._
  import EngineData._

  startWith(Idle, Blank)

  when(Idle) {
    case Event(StartEngine, _) =>
      //Services ! StartServices$
      //Services ! SubscribeTransitionCallBack(self)
      goto(Starting)
  }

  when(Starting) {
    case Event(CurrentState(_, ServicesState.Active), _) =>
      //Services ! UnsubscribeTransitionCallBack(self)
      goto(Active)

    case Event(Transition(_, _, ServicesState.Active), _) =>
      //Services ! UnsubscribeTransitionCallBack(self)
      goto(Active)
  }

  when(Active) {
    case Event(StopEvent, _) =>
      //Services ! StopServices$
      stay()
  }

  onTransition {
    case Starting -> Active => log.info("Engine started")
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