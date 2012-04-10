package com.ergodicity.engine.plaza2

import org.slf4j.LoggerFactory
import org.mockito.Mockito._
import org.mockito.Matchers._
import akka.testkit.{TestFSMRef, TestKit}
import com.ergodicity.engine.plaza2.ConnectionState._
import org.hamcrest.{Description, BaseMatcher}
import plaza2.{SafeRelease, ConnectionStatusChanged}
import plaza2.ConnectionStatus.{ConnectionDisconnected, ConnectionConnected}
import com.ergodicity.engine.plaza2.Connection._
import plaza2.RouterStatus.{RouterReconnecting, RouterDisconnected, RouterConnected}
import akka.actor.{FSM, Terminated, ActorSystem}
import plaza2.{Connection => P2Connection}
import com.jacob.com.ComFailException
import org.scalatest.{Spec, WordSpec}


class ConnectionSpec extends TestKit(ActorSystem()) with WordSpec {
  val log = LoggerFactory.getLogger(classOf[ConnectionSpec])

  type StatusHandler = ConnectionStatusChanged => Unit

  val Host = "host"
  val Port = 4001
  val AppName = "ConnectionSpec"

  val ReleaseNothing = new SafeRelease {
    def apply() {}
  }

  "Connection" must {
    "be initialized in Idle state" in {
      val p2 = mock(classOf[P2Connection])
      val connection = TestFSMRef(new Connection(p2), "Connection")
      log.info("State: "+connection.stateName)
      assert(connection.stateName == Idle)
    }

    "propagate exception on connect" in {
      val p2 = mock(classOf[P2Connection])
      val err = mock(classOf[ComFailException])
      when(p2.connect()).thenThrow(err)

      val connection = TestFSMRef(new Connection(p2), "Connection")
      intercept[ComFailException] {
        connection.receive(Connect(Host, Port, AppName))
      }
    }

    "go to Connecting status" in {
      val p2 = mock(classOf[P2Connection])
      when(p2.status).thenReturn(ConnectionDisconnected)
      when(p2.routerStatus).thenReturn(None)

      val connection = TestFSMRef(new Connection(p2), "Connection")
      connection ! Connect(Host, Port, AppName)

      assert(connection.stateName == Connecting)
    }

    "go to Connected state immediately" in {
      val p2 = mock(classOf[P2Connection])
      when(p2.status).thenReturn(ConnectionConnected)
      when(p2.routerStatus).thenReturn(Some(RouterConnected))

      val connection = TestFSMRef(new Connection(p2), "Connection")
      connection ! Connect(Host, Port, AppName)

      assert(connection.stateName == Connected)
    }

    "go to Connected status after Connection connected" in {
      val p2 = mock(classOf[P2Connection])
      when(p2.status).thenReturn(ConnectionDisconnected)
      when(p2.routerStatus).thenReturn(None)

      val statusUpdates = captureEventDispatcher(p2)

      val connection = TestFSMRef(new Connection(p2), "Connection")
      connection ! Connect(Host, Port, AppName)

      statusUpdates ! ConnectionStatusChanged(ConnectionConnected, Some(RouterDisconnected))
      assert(connection.stateName == Connecting)
      statusUpdates ! ConnectionStatusChanged(ConnectionConnected, Some(RouterConnected))
      assert(connection.stateName == Connected)
    }

    "terminate after Connection disconnected" in {
      val p2 = mock(classOf[P2Connection])
      when(p2.status).thenReturn(ConnectionConnected)
      when(p2.routerStatus).thenReturn(Some(RouterConnected))

      val statusUpdates = captureEventDispatcher(p2)

      val connection = TestFSMRef(new Connection(p2), "Connection")
      watch(connection)
      connection ! Connect(Host, Port, AppName)

      assert(connection.stateName == Connected)

      statusUpdates ! ConnectionStatusChanged(ConnectionDisconnected, Some(RouterConnected))
      expectMsg(Terminated(connection))
    }

    "terminate after Router disconnected" in {
      val p2 = mock(classOf[P2Connection])
      when(p2.status).thenReturn(ConnectionConnected)
      when(p2.routerStatus).thenReturn(Some(RouterConnected))
      captureEventDispatcher(p2)

      val statusUpdates = captureEventDispatcher(p2)

      val connection = TestFSMRef(new Connection(p2), "Connection")
      watch(connection)
      connection ! Connect(Host, Port, AppName)

      assert(connection.stateName == Connected)

      statusUpdates ! ConnectionStatusChanged(ConnectionConnected, Some(RouterDisconnected))
      expectMsg(Terminated(connection))
    }

    "terminate after Disconnected sent" in {
      val p2 = mock(classOf[P2Connection])
      when(p2.status).thenReturn(ConnectionConnected)
      when(p2.routerStatus).thenReturn(Some(RouterConnected))
      captureEventDispatcher(p2)

      val connection = TestFSMRef(new Connection(p2), "Connection")
      watch(connection)
      connection ! Connect(Host, Port, AppName)

      assert(connection.stateName == Connected)

      connection ! Disconnect
      expectMsg(Terminated(connection))
    }

    "terminate on FSM.StateTimeout" in {
      val p2 = mock(classOf[P2Connection])
      when(p2.status).thenReturn(ConnectionConnected)
      when(p2.routerStatus).thenReturn(Some(RouterReconnecting))
      captureEventDispatcher(p2)

      val connection = TestFSMRef(new Connection(p2), "Connection")
      watch(connection)
      connection ! Connect(Host, Port, AppName)

      assert(connection.stateName == Connecting)

      connection ! FSM.StateTimeout
      expectMsg(Terminated(connection))
    }

  }

  private def captureEventDispatcher(conn: P2Connection) = {
    var f: StatusHandler = s => ()

    when(conn.dispatchEvents(argThat(new BaseMatcher[StatusHandler] {
      def describeTo(description: Description) {}

      def matches(item: AnyRef) = {
        f = item.asInstanceOf[StatusHandler]
        true
      };
    }))).thenReturn(ReleaseNothing)

    new {
      def !(event: ConnectionStatusChanged) {
        f(event)
      }
    }
  }
}