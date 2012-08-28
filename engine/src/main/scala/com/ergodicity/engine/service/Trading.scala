package com.ergodicity.engine.service

import com.ergodicity.engine.underlying.{ListenerFactory, UnderlyingPublisher, UnderlyingTradingConnections, UnderlyingListener}
import com.ergodicity.engine.{Services, Engine}
import com.ergodicity.core.broker.{ReplySubscriber, Broker}
import akka.actor.{Actor, ActorLogging, Props}
import akka.util.duration._
import com.ergodicity.cgate.{Active, Listener, WhenUnhandled}
import ru.micexrts.cgate.{Publisher => CGPublisher, Connection => CGConnection}
import com.ergodicity.cgate.config.Replies
import com.ergodicity.engine.service.Service.{Stop, Start}
import akka.actor.FSM.{SubscribeTransitionCallBack, CurrentState, Transition, UnsubscribeTransitionCallBack}
import com.ergodicity.cgate.config.Replies.RepliesParams

object Trading {

  implicit case object Trading extends ServiceId

}

trait Trading {
  this: Services =>

  import Trading._

  implicit def BrokerConfig: Broker.Config

  def engine: Engine with UnderlyingListener with UnderlyingTradingConnections with UnderlyingPublisher

  register(context.actorOf(Props(new TradingService(engine.listenerFactory, engine.underlyingPublisher, engine.underlyingRepliesConnection)), "Trading"))
}

protected[service] class TradingService(listener: ListenerFactory, underlyingPublisher: CGPublisher, underlyingRepliesConnection: CGConnection)
                                       (implicit val services: Services, id: ServiceId, config: Broker.Config) extends Actor with ActorLogging with WhenUnhandled {

  import services._

  val TradingBroker = context.actorOf(Props(new Broker(underlyingPublisher)), "Broker")

  private[this] val underlyingRepliesListener = listener(underlyingRepliesConnection, Replies(self.path.name)(), new ReplySubscriber(TradingBroker))
  private[this] val replyListener = context.actorOf(Props(new Listener(underlyingRepliesListener)), "RepliesListener")

  protected def receive = start orElse stop orElse handleBrokerActivated orElse whenUnhandled

  private def start: Receive = {
    case Start =>
      replyListener ! Listener.Open(RepliesParams)
      TradingBroker ! SubscribeTransitionCallBack(self)
      TradingBroker ! Broker.Open
  }

  private def handleBrokerActivated: Receive = {
    case CurrentState(TradingBroker, Active) =>
      TradingBroker ! UnsubscribeTransitionCallBack(self)
      serviceStarted

    case Transition(TradingBroker, _, Active) =>
      TradingBroker ! UnsubscribeTransitionCallBack(self)
      serviceStarted
  }

  private def stop: Receive = {
    case Stop =>
      TradingBroker ! Broker.Close
      replyListener ! Listener.Close
      replyListener ! Listener.Dispose

      context.system.scheduler.scheduleOnce(1.second) {
        serviceStopped
        context.stop(self)
      }
  }
}
