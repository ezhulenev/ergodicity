package com.ergodicity.engine.service

import com.ergodicity.engine.Components.CreateListener
import com.ergodicity.engine.{Services, Engine}
import akka.actor._
import akka.util.duration._
import com.ergodicity.core.broker.{Broker => BrokerCore, ReplySubscriber}
import com.ergodicity.cgate.{Listener => ErgodicityListener, WhenUnhandled, Active}
import ru.micexrts.cgate.{Publisher => CGPublisher}
import com.ergodicity.cgate.config.Replies
import com.ergodicity.engine.service.Service.{Stop, Start}
import com.ergodicity.cgate.config.Replies.RepliesParams
import akka.actor.FSM.Transition
import akka.actor.FSM.CurrentState
import akka.actor.FSM.UnsubscribeTransitionCallBack
import akka.actor.FSM.SubscribeTransitionCallBack
import com.ergodicity.engine.underlying.UnderlyingTradingConnections


case object TradingServiceId extends ServiceId

trait Trading {
  engine: Engine with UnderlyingTradingConnections with CreateListener =>

  def BrokerName: String

  implicit def BrokerConfig: BrokerCore.Config

  def underlyingPublisher: CGPublisher

  def Broker: ActorRef
}

trait ManagedTrading extends Trading {
  engine: Engine with Services with CreateListener with UnderlyingTradingConnections =>

  val Broker = context.actorOf(Props(new BrokerCore(underlyingPublisher)), "Broker")

  private[this] val brokerManager = context.actorOf(Props(new BrokerManager(this)).withDispatcher("deque-dispatcher"), "BrokerManager")

  registerService(TradingServiceId, brokerManager)
}

protected[service] class BrokerManager(engine: Engine with Services with CreateListener with UnderlyingTradingConnections with Trading) extends Actor with ActorLogging with WhenUnhandled with Stash {
  import engine._

  val ManagedBroker = Broker

  private[this] val underlyingRepliesListener = listener(underlyingRepliesConnection, Replies(BrokerName)(), new ReplySubscriber(Broker))
  private[this] val replyListener = context.actorOf(Props(new ErgodicityListener(underlyingRepliesListener)), "RepliesListener")

  protected def receive = {
    case ServiceStarted(TradingConnectionsServiceId) =>
      log.info("BrokerConnectionsService started, unstash all messages and start Trading and replies listeners")
      unstashAll()
      context.become {
        start orElse stop orElse handleBrokerActivated orElse whenUnhandled
      }

    case msg =>
      log.info("Stash message until BrokerConnectionsService is not started = " + msg)
      stash()
  }

  private def start: Receive = {
    case Start =>
      replyListener ! ErgodicityListener.Open(RepliesParams)
      ManagedBroker ! SubscribeTransitionCallBack(self)
      ManagedBroker ! BrokerCore.Open
  }

  private def handleBrokerActivated: Receive = {
    case CurrentState(ManagedBroker, Active) =>
      ManagedBroker ! UnsubscribeTransitionCallBack(self)
      ServiceManager ! ServiceStarted(TradingServiceId)

    case Transition(ManagedBroker, _, Active) =>
      ManagedBroker ! UnsubscribeTransitionCallBack(self)
      ServiceManager ! ServiceStarted(TradingServiceId)
  }

  private def stop: Receive = {
    case Stop =>
      ManagedBroker ! BrokerCore.Close
      replyListener ! ErgodicityListener.Close
      replyListener ! ErgodicityListener.Dispose
      context.system.scheduler.scheduleOnce(1.second) {
        ServiceManager ! ServiceStopped(TradingServiceId)
        context.stop(self)
      }
  }
}