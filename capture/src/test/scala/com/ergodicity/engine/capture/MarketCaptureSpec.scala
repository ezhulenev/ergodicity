package com.ergodicity.engine.capture

import org.mockito.Mockito._
import org.mockito.Matchers._
import org.hamcrest.{BaseMatcher, Description}
import org.scalatest.{WordSpec, BeforeAndAfterAll}
import akka.testkit.{ImplicitSender, TestFSMRef, TestKit}
import plaza2.{SafeRelease, ConnectionStatusChanged, Connection => P2Connection}
import akka.actor.FSM.Transition
import com.ergodicity.engine.plaza2.ConnectionState
import org.slf4j.LoggerFactory
import akka.actor.{Terminated, ActorSystem}
import com.jacob.com.ComFailException

class MarketCaptureSpec  extends TestKit(ActorSystem("MarketCaptureSpec")) with WordSpec with BeforeAndAfterAll with ImplicitSender {
    val log = LoggerFactory.getLogger(classOf[MarketCaptureSpec])

  type StatusHandler = ConnectionStatusChanged => Unit

  val Host = "host"
  val Port = 4001
  val AppName = "MarketCaptureSpec"

  val ReleaseNothing = new SafeRelease {
    def apply() {}
  }

  override def afterAll() {
    system.shutdown()
  }

  "MarketCapture" must {

    "be initialized in Idle state" in {
      val p2 = mock(classOf[P2Connection])
      val marketCapture = TestFSMRef(new MarketCapture(p2), "MarketCapture")
      log.info("State: " + marketCapture.stateName)
      assert(marketCapture.stateName == Idle)
    }

    "got to Capturing state after connection established" in {
      val p2 = mock(classOf[P2Connection])

      val marketCapture = TestFSMRef(new MarketCapture(p2), "MarketCapture")
      val connection = marketCapture.underlyingActor.asInstanceOf[MarketCapture].connection

      marketCapture ! Connect(ConnectionProperties(Host, Port, AppName))

      log.info("State: " + marketCapture.stateName)
      assert(marketCapture.stateName == Starting)

      marketCapture ! Transition(connection, ConnectionState.Connecting, ConnectionState.Connected)
      log.info("State: " + marketCapture.stateName)
      assert(marketCapture.stateName == Capturing)
    }

    "terminate in case of underlying ComFailedException" in {
      val p2 = mock(classOf[P2Connection])
      val err = mock(classOf[ComFailException])
      when(p2.connect()).thenThrow(err)
      captureEventDispatcher(p2)

      val marketCapture = TestFSMRef(new MarketCapture(p2), "MarketCapture")
      watch(marketCapture)

      marketCapture ! Connect(ConnectionProperties(Host, Port, AppName))
      expectMsg(Terminated(marketCapture))
    }

    "terminate after Connection terminated" in {
      val p2 = mock(classOf[P2Connection])

      val marketCapture = TestFSMRef(new MarketCapture(p2), "MarketCapture")
      val connection = marketCapture.underlyingActor.asInstanceOf[MarketCapture].connection
      captureEventDispatcher(p2)

      watch(marketCapture)
      marketCapture ! Terminated(connection)
      expectMsg(Terminated(marketCapture))
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
