package com.ergodicity.engine.service

case object PortfolioServiceId extends ServiceId

/*
trait PortfolioService

trait Portfolio extends PortfolioService {
  this: Services =>
  def engine: Engine with UnderlyingConnection with CreateListener with PosReplication

  private[this] val portfolioManager = context.actorOf(Props(new PortfolioManager(this, engine)).withDispatcher("deque-dispatcher"), "PortfolioManager")

  register(PortfolioServiceId, portfolioManager)
}

protected[service] class PortfolioManager(services: Services, engine: Engine with UnderlyingConnection with CreateListener with PosReplication) extends Actor with ActorLogging with WhenUnhandled with Stash {

  import engine._
  import services._

  implicit val Id = PortfolioServiceId

  val PosStream = context.actorOf(Props(new DataStream), "PosDataStream")

  val Positions = context.actorOf(Props(new PositionsTracking(PosStream)), "Positions")

  val underlyingPosListener = listener(underlyingConnection, posReplication(), new DataStreamSubscriber(PosStream))
  val posListener = context.actorOf(Props(new Listener(underlyingPosListener)), "PosListener")

  protected def receive = {
    case ServiceStarted(ConnectionServiceId) =>
      log.info("ConnectionService started, unstash all messages and start PositionsService")
      unstashAll()
      context.become {
        start orElse stop orElse handlePositionsGoesOnline orElse whenUnhandled
      }

    case msg =>
      log.info("Stash message until ConnectionService is not started = " + msg)
      stash()
  }

  private def start: Receive = {
    case Start =>
      posListener ! Listener.Open(ReplicationParams(Combined))
      Positions ! SubscribeTransitionCallBack(self)
  }

  private def handlePositionsGoesOnline: Receive = {
    case CurrentState(Positions, PositionsTrackingState.Online) =>
      Positions ! UnsubscribeTransitionCallBack(self)
      serviceStarted

    case Transition(Positions, _, PositionsTrackingState.Online) =>
      Positions ! UnsubscribeTransitionCallBack(self)
      serviceStarted
  }

  private def stop: Receive = {
    case Stop =>
      posListener ! Listener.Close
      posListener ! Listener.Dispose
      context.system.scheduler.scheduleOnce(1.second) {
        serviceStopped
        context.stop(self)
      }
  }
}*/
