package com.ergodicity.cgate

import akka.util.duration._
import akka.pattern.ask
import config.ListenerOpenParams
import ru.micexrts.cgate.{Listener => CGListener}
import akka.actor.FSM.Failure
import akka.actor.{ActorRef, Actor, FSM}
import akka.dispatch.Future
import ru.micexrts.cgate
import com.ergodicity.cgate.Connection.Execute
import akka.util.{Duration, Timeout}

object Listener {

  case class Open(config: ListenerOpenParams)

  case object Close

  case object Dispose

  def apply(underlying: WithListener, updateStateDuration: Option[Duration] = Some(100.millis)) = new Listener(underlying, updateStateDuration)
}

protected[cgate] case class ListenerState(state: State)

trait WithListener {
  def apply[T](f: CGListener => T)(implicit m: Manifest[T]): Future[T]
}

object BindListener {
  implicit val timeout = Timeout(1.second)

  def apply(listener: CGListener) = new {
    def to(connection: ActorRef) = new WithListener {
      def apply[T](f: (cgate.Listener) => T)(implicit m: Manifest[T]) = (connection ? Execute(_ => listener.synchronized {
        f(listener)
      })).mapTo[T]
    }
  }
}

class Listener(withListener: WithListener, updateStateDuration: Option[Duration] = Some(1.second)) extends Actor with FSM[State, Option[ListenerOpenParams]] {

  import Listener._

  private val statusTracker = updateStateDuration.map {
    duration =>
      context.system.scheduler.schedule(0 milliseconds, duration) {
        withListener(listener => listener.getState) onSuccess {
          case state => self ! ListenerState(State(state))
        }
      }
  }

  startWith(Closed, None)

  when(Closed) {
    case Event(Open(config), None) =>
      log.info("Open listener with config = " + config)
      withListener(_.open(config()))
      stay() using Some(config)
  }

  when(Opening, stateTimeout = 3.second) {
    case Event(FSM.StateTimeout, _) => stop(Failure("Connecting timeout"))
  }

  when(Active) {
    case Event(Close, _) =>
      log.info("Close Listener")
      withListener(_.close())
      stay() using None
  }

  onTransition {
    case Closed -> Opening => log.info("Opening listener")
    case _ -> Active => log.info("Listener opened")
    case _ -> Closed => log.info("Listener closed")
  }

  whenUnhandled {
    case Event(ListenerState(Error), _) => stop(Failure("Listener in Error state"))

    case Event(ListenerState(state), _) if (state != stateName) => goto(state)

    case Event(ListenerState(state), _) if (state == stateName) => stay()

    case Event(Dispose, _) =>
      log.info("Dispose listener")
      withListener(_.dispose())
      stop(Failure("Disposed"))
  }

  onTermination {
    case StopEvent(reason, s, d) => log.error("Listener failed, reason = " + reason)
  }

  initialize

  override def postStop() {
    statusTracker.foreach(_.cancel())
    super.postStop()
  }
}