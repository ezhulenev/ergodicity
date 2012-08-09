package com.ergodicity.engine.service

import com.ergodicity.engine.Components.CreateListener
import com.ergodicity.engine.Engine
import akka.actor.{Stash, Actor, Props, ActorRef}
import akka.util.duration._
import com.ergodicity.core.broker.{Broker => BrokerCore, ReplySubscriber, BindPublisher}
import com.ergodicity.cgate.{Listener => ErgodicityListener, Active, BindListener}
import ru.micexrts.cgate.{Publisher => CGPublisher}
import com.ergodicity.cgate.config.Replies
import com.ergodicity.core.WhenUnhandled
import akka.event.Logging
import com.ergodicity.engine.service.Service.{Stop, Start}
import akka.actor.FSM.{Transition, UnsubscribeTransitionCallBack, CurrentState, SubscribeTransitionCallBack}
import com.ergodicity.cgate.config.Replies.RepliesParams


case object BrokerService extends Service

trait Broker {
  engine: Engine with BrokerConnections with CreateListener =>

  def BrokerName: String

  implicit def BrokerConfig: BrokerCore.Config

  def underlyingPublisher: CGPublisher

  def Broker: ActorRef
}

trait ManagedBroker extends Broker {
  engine: Engine with CreateListener with BrokerConnections =>

  val Broker = context.actorOf(Props(new BrokerCore(BindPublisher(underlyingPublisher) to PublisherConnection)), "Broker")

  private[this] val brokerManager = context.actorOf(Props(new BrokerManager(this)).withDispatcher("deque-dispatcher"), "BrokerManager")

  registerService(BrokerService, brokerManager)
}

protected[service] class BrokerManager(engine: Engine with CreateListener with BrokerConnections with Broker) extends Actor with WhenUnhandled with Stash {
  val log = Logging(context.system, self)

  import engine._

  val ManagedBroker = Broker

  private[this] val underlyingRepliesListener = listener(underlyingRepliesConnection, Replies(BrokerName)(), new ReplySubscriber(Broker))
  private[this] val replyListener = context.actorOf(Props(new ErgodicityListener(BindListener(underlyingRepliesListener) to RepliesConnection)), "RepliesListener")

  protected def receive = {
    case ServiceStarted(BrokerConnectionsService) =>
      log.info("BrokerConnectionsService started, unstash all messages and start Broker and replies listeners")
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
      ServiceManager ! ServiceStarted(BrokerService)

    case Transition(ManagedBroker, _, Active) =>
      ManagedBroker ! UnsubscribeTransitionCallBack(self)
      ServiceManager ! ServiceStarted(BrokerService)
  }

  private def stop: Receive = {
    case Stop =>
      ManagedBroker ! BrokerCore.Close
      replyListener ! ErgodicityListener.Close
      replyListener ! ErgodicityListener.Dispose
      context.system.scheduler.scheduleOnce(1.second) {
        ServiceManager ! ServiceStopped(BrokerService)
        context.stop(self)
      }
  }
}