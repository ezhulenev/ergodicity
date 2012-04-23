package com.ergodicity.engine.capture

import org.mockito.Mockito._
import org.mockito.Matchers._
import org.hamcrest.{BaseMatcher, Description}
import org.scalatest.{WordSpec, BeforeAndAfterAll}
import akka.testkit.{ImplicitSender, TestFSMRef, TestKit}
import plaza2.{SafeRelease, ConnectionStatusChanged, Connection => P2Connection}
import org.slf4j.LoggerFactory
import akka.actor.{Terminated, ActorSystem}
import com.jacob.com.ComFailException
import com.ergodicity.engine.plaza2.DataStream.LifeNumChanged

class MarketCaptureSpec  extends TestKit(ActorSystem("MarketCaptureSpec")) with WordSpec with BeforeAndAfterAll with ImplicitSender {
    val log = LoggerFactory.getLogger(classOf[MarketCaptureSpec])

  type StatusHandler = ConnectionStatusChanged => Unit

  val Host = "host"
  val Port = 4001
  val AppName = "MarketCaptureSpec"

  val scheme = CaptureScheme("capture/scheme/OrdLog.ini", "capture/scheme/FutTradeDeal.ini", "capture/scheme/OptTradeDeal.ini")
  
  val repository = mock(classOf[RevisionTracker])
  when(repository.revision(any(), any())).thenReturn(None)

  val ReleaseNothing = new SafeRelease {
    def apply() {}
  }

  override def afterAll() {
    system.shutdown()
  }

  "MarketCapture" must {

    "be initialized in Idle state" in {
      val p2 = mock(classOf[P2Connection])
      val marketCapture = TestFSMRef(new MarketCapture(p2, scheme, repository), "MarketCapture")
      log.info("State: " + marketCapture.stateName)
      assert(marketCapture.stateName == Idle)
    }

    "terminate in case of underlying ComFailedException" in {
      val p2 = mock(classOf[P2Connection])
      val err = mock(classOf[ComFailException])
      when(p2.connect()).thenThrow(err)
      captureEventDispatcher(p2)

      val marketCapture = TestFSMRef(new MarketCapture(p2, scheme, repository), "MarketCapture")
      watch(marketCapture)

      marketCapture ! Connect(ConnectionProperties(Host, Port, AppName))
      expectMsg(Terminated(marketCapture))
    }

    "terminate after Connection terminated" in {
      val p2 = mock(classOf[P2Connection])

      val marketCapture = TestFSMRef(new MarketCapture(p2, scheme, repository), "MarketCapture")
      val connection = marketCapture.underlyingActor.asInstanceOf[MarketCapture].connection
      captureEventDispatcher(p2)

      watch(marketCapture)
      marketCapture ! Terminated(connection)
      expectMsg(Terminated(marketCapture))
    }

    "reset stream revision on LifeNum changed" in {
      val p2 = mock(classOf[P2Connection])
      val repository = mock(classOf[RevisionTracker])
      when(repository.revision(any(), any())).thenReturn(None)

      val marketCapture = TestFSMRef(new MarketCapture(p2, scheme, repository), "MarketCapture")
      val underlying = marketCapture.underlyingActor.asInstanceOf[MarketCapture]

      marketCapture ! LifeNumChanged(underlying.ordersDataStream, 100)
      verify(repository).reset("FORTS_ORDLOG_REPL")

      marketCapture ! LifeNumChanged(underlying.optTradeDataStream, 100)
      verify(repository).reset("FORTS_OPTTRADE_REPL")

      marketCapture ! LifeNumChanged(underlying.futTradeDataStream, 100)
      verify(repository).reset("FORTS_FUTTRADE_REPL")
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
