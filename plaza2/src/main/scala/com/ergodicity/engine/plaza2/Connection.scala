package com.ergodicity.engine.plaza2

import akka.actor.{FSM, Actor}
import akka.util.duration._
import plaza2.RouterStatus.RouterConnected
import akka.actor.FSM.Failure
import ConnectionState._
import plaza2.{SafeRelease, ConnectionStatusChanged, Connection => P2Connection}
import plaza2.ConnectionStatus.{ConnectionInvalid, ConnectionDisconnected, ConnectionConnected}


object Connection {
  case class Connect(host: String, port: Int, appName: String)
  case class ProcessMessages(timeout: Long)
  case object Disconnect

  def apply(underlying: P2Connection) = new Connection(underlying)
}

sealed trait ConnectionState
object ConnectionState {
  case object Idle extends ConnectionState
  case object Connecting extends ConnectionState
  case object Connected extends ConnectionState
}

class Connection(protected[plaza2] val underlying: P2Connection) extends Actor with FSM[ConnectionState, Option[SafeRelease]] {
  import Connection._
  
  startWith(Idle, None)

  when(Idle) { case Event(Connect(host, port, appName), _) =>
    goto(Connecting) using Some(connect(host, port, appName))
  }

  when(Connecting, stateTimeout = 10.second) {
    case Event(ConnectionStatusChanged(ConnectionConnected, Some(RouterConnected)), _) =>
      goto(Connected)

    case Event(ConnectionStatusChanged(status, routerStatus), _) =>
      log.debug("Connecting.... (Status = " + status + "; Router status = " + routerStatus + ")");
      stay()

    case Event(FSM.StateTimeout, _) => stop(Failure("Connecting timeout"))
  }

  when(Connected) {
    case Event(Disconnect, _) => stop(Failure(Disconnect))
    case Event(ConnectionStatusChanged(status@(ConnectionDisconnected | ConnectionInvalid), _), _) => stop(Failure(status))
    case Event(ConnectionStatusChanged(_, Some(routerStatus)), _) if (routerStatus != RouterConnected) => stop(Failure(routerStatus))

    case Event(ProcessMessages(timeout), _) => underlying.processMessage(timeout); self ! ProcessMessages(timeout); stay()
  }

  onTransition {
    case Idle -> Connecting        => log.info("Trying to establish connection to Plaza2")
    case Connecting -> Connected   => log.info("Successfully connected to Plaza2");
  }

  onTermination { case StopEvent(reason, s, d) =>
    log.error("Connection failed, reason = " + reason)
    d foreach {release => if (release != null) release()}
  }

  initialize

  private def connect(host: String,  port: Int, appName: String) = {
    log.info("Connect; Host = " + host + "; Port = " + port + "; AppName = " + appName)
    underlying.host = host
    underlying.port = port
    underlying.appName = appName
    underlying.connect()

    val releaseEventListener = underlying.dispatchEvents {self ! _}

    // Send current status immediately
    self ! ConnectionStatusChanged(underlying.status, underlying.routerStatus)

    new SafeRelease {
      def apply() {
        if (releaseEventListener != null) releaseEventListener()
        underlying.disconnect()
      }
    }
  }
}