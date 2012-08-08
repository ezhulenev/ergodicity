package com.ergodicity.engine.service

import com.ergodicity.cgate.{Connection => ErgodicityConnection, BindListener, Listener => ErgodicityListener}
import com.ergodicity.core.broker.{Broker => BrokerCore, ReplySubscriber, BindPublisher}
import com.ergodicity.engine.Engine
import com.ergodicity.engine.Components.CreateListener
import akka.actor.{Stash, Actor, Props, ActorRef}
import ru.micexrts.cgate.{Connection => CGConnection, Publisher => CGPublisher, Listener => CGListener}
import com.ergodicity.cgate.config.Replies
import com.ergodicity.core.WhenUnhandled
import akka.event.Logging

case object BrokerService extends Service

trait Broker {
  engine: Engine with Connection with CreateListener =>

  def Broker: ActorRef
}

trait ManagedBroker extends CreateListener {
  engine: Engine with Connection with CreateListener =>

  def brokerName: String
  def underlyingPublisher: CGPublisher
  def underlyingPublisherConnection: CGConnection
  def underlyingRepliesConnection: CGConnection

  private[this] val PublisherConnection = context.actorOf(Props(new ErgodicityConnection(underlyingConnection)), "PublisherConnection")
  private[this] val RepliesConnection = context.actorOf(Props(new ErgodicityConnection(underlyingConnection)), "RepliesConnection")

  val Broker = context.actorOf(Props(new BrokerCore(BindPublisher(underlyingPublisher) to PublisherConnection)), "Broker")

  private[this] val underlyingRepliesListener = listener(underlyingRepliesConnection, Replies(brokerName)(), new ReplySubscriber(Broker))
  private[this] val replyListener = context.actorOf(Props(new ErgodicityListener(BindListener(underlyingRepliesListener) to RepliesConnection)), "RepliesListener")
}

protected[service] class BrokerManager(engine: Engine with ManagedBroker) extends Actor with WhenUnhandled with Stash {
  val log = Logging(context.system, self)

  protected def receive = null
}