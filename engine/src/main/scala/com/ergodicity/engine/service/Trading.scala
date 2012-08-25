package com.ergodicity.engine.service

case object TradingServiceId extends ServiceId

/*
trait TradingService {
  def BrokerName: String

  implicit def BrokerConfig: BrokerCore.Config
}

trait Trading extends TradingService {
  this: Services =>

  def engine: Engine with UnderlyingTradingConnections with UnderlyingPublisher with CreateListener

  private[this] val brokerManager = context.actorOf(Props(new BrokerManager(this, engine)).withDispatcher("deque-dispatcher"), "BrokerManager")

  register(TradingServiceId, brokerManager)
}

protected[service] class BrokerManager(services: Services with TradingService, engine: Engine with CreateListener with UnderlyingTradingConnections with UnderlyingPublisher) extends Actor with ActorLogging with WhenUnhandled with Stash {
  import engine._
  import services._

  implicit val Id = TradingServiceId
  val Broker = context.actorOf(Props(new BrokerCore(underlyingPublisher)), "Broker")

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
      Broker ! SubscribeTransitionCallBack(self)
      Broker ! BrokerCore.Open
  }

  private def handleBrokerActivated: Receive = {
    case CurrentState(Broker, Active) =>
      Broker ! UnsubscribeTransitionCallBack(self)
      serviceStarted

    case Transition(Broker, _, Active) =>
      Broker ! UnsubscribeTransitionCallBack(self)
      serviceStarted
  }

  private def stop: Receive = {
    case Stop =>
      Broker ! BrokerCore.Close
      replyListener ! ErgodicityListener.Close
      replyListener ! ErgodicityListener.Dispose
      context.system.scheduler.scheduleOnce(1.second) {
        serviceStopped
        context.stop(self)
      }
  }
}*/
