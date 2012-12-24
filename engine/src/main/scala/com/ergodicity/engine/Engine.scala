package com.ergodicity.engine

import akka.actor.AllForOneStrategy
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.actor.SupervisorStrategy.Stop
import akka.actor.Terminated
import akka.actor._
import akka.util
import akka.util.duration._
import com.ergodicity.cgate.ListenerBinding
import com.ergodicity.cgate.config.Replication
import com.ergodicity.engine.Engine._
import com.ergodicity.engine.Services.{StopServices, StartServices}
import com.ergodicity.engine.StrategyEngine.{StopStrategies, LoadStrategies, StartStrategies}
import ru.micexrts.cgate.CGateException
import scala.Some


object Engine {
  val ReplicationDispatcher = "engine.dispatchers.replicationDispatcher"

  val TradingDispatcher = "engine.dispatchers.publisherDispatcher"

  case object StartEngine

  case object StartTrading

  case object StopEngine

  case object NotifyOnReady

  case object EngineReady

  trait ReadyNotifier {
    def apply(ref: ActorRef): ReadyNotifier

    def reset(): ReadyNotifier
  }

  object ReadyNotifier {

    case object DirectNotify extends ReadyNotifier {
      def apply(ref: ActorRef) = {
        ref ! EngineReady
        DirectNotify
      }

      def reset() = DirectNotify
    }

    case class PendingNotify(pending: Seq[ActorRef]) extends ReadyNotifier {
      def apply(ref: ActorRef) = copy(ref +: pending)

      def reset() = {
        pending.foreach(_ ! EngineReady)
        DirectNotify
      }
    }

    def empty: ReadyNotifier = PendingNotify(Nil)
  }
}

sealed trait EngineState

object EngineState {

  case object Idle extends EngineState

  case object StartingServices extends EngineState

  case object LoadingStrategies extends EngineState

  case object Ready extends EngineState

  case object StartingStrategies extends EngineState

  case object Active extends EngineState

  case object StoppingStrategies extends EngineState

  case object StoppingServices extends EngineState

}

trait Engine extends Actor with FSM[EngineState, Option[(ActorRef, ActorRef)]] {

  implicit val timeout = util.Timeout(1.second)

  override val supervisorStrategy = AllForOneStrategy() {
    case _: CGateException ⇒ Stop
  }

  protected var notifier: ReadyNotifier = ReadyNotifier.empty

  import EngineState._

  def ServicesActor: ActorRef

  def StrategiesActor: ActorRef

  startWith(Idle, None)

  when(Idle) {
    case Event(StartEngine, None) =>
      log.info("Start engine")
      val services = ServicesActor
      val strategies = StrategiesActor
      services ! SubscribeTransitionCallBack(self)
      strategies ! SubscribeTransitionCallBack(self)
      services ! StartServices
      goto(StartingServices) using Some(services, strategies)
  }

  when(StartingServices) {
    case Event(Transition(_, _, ServicesState.Active), Some((_, strategies))) =>
      strategies ! LoadStrategies
      goto(LoadingStrategies)

  }

  when(LoadingStrategies) {
    case Event(Transition(_, _, StrategyEngineState.StrategiesReady), Some((_, _))) =>
      goto(Ready)
  }

  when(Ready) {
    case Event(StartTrading, Some((_, strategies))) =>
      strategies ! StartStrategies
      goto(StartingStrategies)
  }

  when(StartingStrategies) {
    case Event(Transition(_, _, StrategyEngineState.Active), _) =>
      goto(Active)
  }

  when(Active) {
    case Event(StopEngine, Some((_, strategies))) =>
      log.info("Stop Engine")
      context.watch(strategies)
      strategies ! StopStrategies
      goto(StoppingStrategies)
  }

  when(StoppingStrategies) {
    case Event(Terminated(ref), Some((services, strategies))) if (ref == strategies) =>
      log.info("Strategies stopped")
      context.watch(services)
      services ! StopServices
      goto(StoppingServices)
  }

  when(StoppingServices) {
    case Event(Terminated(ref), Some((services, _))) if (ref == services) =>
      log.info("Services stopped")
      stop(FSM.Shutdown)
  }

  onTransition {
    case _ -> Ready =>
      log.info("Engine ready")
      notifier = notifier.reset()
  }

  whenUnhandled {
    case Event(NotifyOnReady, _) =>
      notifier = notifier(sender)
      stay()
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