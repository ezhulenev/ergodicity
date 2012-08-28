package com.ergodicity.engine.service

import com.ergodicity.engine.{Engine, Services}
import com.ergodicity.engine.underlying.{ListenerFactory, UnderlyingListener, UnderlyingConnection}
import com.ergodicity.engine.ReplicationScheme.{OptInfoReplication, FutInfoReplication}
import akka.actor.{ActorLogging, Actor, Props}
import akka.util.duration._
import com.ergodicity.cgate.{Listener, DataStreamSubscriber, DataStream, WhenUnhandled}
import com.ergodicity.core.{SessionsTrackingState, SessionsTracking}
import ru.micexrts.cgate.{Connection => CGConnection}
import com.ergodicity.cgate.config.Replication
import com.ergodicity.engine.service.Service.{Stop, Start}
import com.ergodicity.cgate.config.Replication.ReplicationParams
import com.ergodicity.cgate.config.Replication.ReplicationMode.Combined
import akka.actor.FSM.{Transition, UnsubscribeTransitionCallBack, CurrentState, SubscribeTransitionCallBack}

object InstrumentData {

  implicit case object InstrumentData extends ServiceId

}

trait InstrumentData {
  this: Services =>

  import InstrumentData._

  def engine: Engine with UnderlyingConnection with UnderlyingListener with FutInfoReplication with OptInfoReplication

  register(context.actorOf(Props(new InstrumentDataService(engine.listenerFactory, engine.underlyingConnection, engine.futInfoReplication, engine.optInfoReplication))))
}

protected[service] class InstrumentDataService(listener: ListenerFactory, underlyingConnection: CGConnection, futInfoReplication: Replication, optInfoReplication: Replication)
                                              (implicit val services: Services, id: ServiceId) extends Actor with ActorLogging with WhenUnhandled {

  import services._

  val FutInfoStream = context.actorOf(Props(new DataStream), "FutInfoDataStream")
  val OptInfoStream = context.actorOf(Props(new DataStream), "OptInfoDataStream")

  val Sessions = context.actorOf(Props(new SessionsTracking(FutInfoStream, OptInfoStream)), "SessionsTracking")

  log.info("Underlying conn = " + underlyingConnection + ", repl = " + futInfoReplication)

  // Listeners
  val underlyingFutInfoListener = listener(underlyingConnection, futInfoReplication(), new DataStreamSubscriber(FutInfoStream))
  val futInfoListener = context.actorOf(Props(new Listener(underlyingFutInfoListener)), "FutInfoListener")

  val underlyingOptInfoListener = listener(underlyingConnection, optInfoReplication(), new DataStreamSubscriber(OptInfoStream))
  val optInfoListener = context.actorOf(Props(new Listener(underlyingOptInfoListener)), "OptInfoListener")

  protected def receive = start orElse stop orElse handleSessionsGoesOnline orElse whenUnhandled

  private def start: Receive = {
    case Start =>
      futInfoListener ! Listener.Open(ReplicationParams(Combined))
      optInfoListener ! Listener.Open(ReplicationParams(Combined))
      Sessions ! SubscribeTransitionCallBack(self)
  }

  private def handleSessionsGoesOnline: Receive = {
    case CurrentState(Sessions, SessionsTrackingState.Online) =>
      Sessions ! UnsubscribeTransitionCallBack(self)
      serviceStarted

    case Transition(Sessions, _, SessionsTrackingState.Online) =>
      Sessions ! UnsubscribeTransitionCallBack(self)
      serviceStarted
  }

  private def stop: Receive = {
    case Stop =>
      futInfoListener ! Listener.Close
      optInfoListener ! Listener.Close
      futInfoListener ! Listener.Dispose
      optInfoListener ! Listener.Dispose
      context.system.scheduler.scheduleOnce(1.second) {
        serviceStopped
        context.stop(self)
      }
  }
}
