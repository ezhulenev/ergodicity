package integration.ergodicity.engine.capture

import org.mockito.Mockito._
import org.mockito.Matchers._
import org.hamcrest.{BaseMatcher, Description}
import org.scalatest.{WordSpec, BeforeAndAfterAll}
import akka.testkit.{ImplicitSender, TestFSMRef, TestKit}
import plaza2.{SafeRelease, ConnectionStatusChanged, Connection => P2Connection}
import org.slf4j.LoggerFactory
import com.jacob.com.ComFailException
import com.ergodicity.engine.plaza2.DataStream.LifeNumChanged
import com.ergodicity.engine.plaza2.scheme.{OptInfo, FutInfo}
import akka.actor.{FSM, Terminated, ActorSystem}
import com.ergodicity.engine.capture._

class MarketCaptureSpec  extends TestKit(ActorSystem("MarketCaptureSpec")) with WordSpec with BeforeAndAfterAll with ImplicitSender {
    val log = LoggerFactory.getLogger(classOf[MarketCaptureSpec])

  type StatusHandler = ConnectionStatusChanged => Unit

  val Host = "host"
  val Port = 4001
  val AppName = "MarketCaptureSpec"

  val scheme = Plaza2Scheme(
    "capture/scheme/FutInfoSessionContents.ini",
    "capture/scheme/OptInfoSessionContents.ini",
    "capture/scheme/OrdLog.ini",
    "capture/scheme/FutTradeDeal.ini",
    "capture/scheme/OptTradeDeal.ini"
  )
  
  val repository = mock(classOf[RevisionTracker])
  when(repository.revision(any(), any())).thenReturn(None)

  val kestrel = KestrelConfig("localhost", 22133, "trades", "orders", 30)

  val ReleaseNothing = new SafeRelease {
    def apply() {}
  }

  override def afterAll() {
    system.shutdown()
  }

  "MarketCapture" must {

    "be initialized in Idle state" in {
      val p2 = mock(classOf[P2Connection])
      val marketCapture = TestFSMRef(new MarketCapture(p2, scheme, repository, kestrel), "MarketCapture")
      log.info("State: " + marketCapture.stateName)
      assert(marketCapture.stateName == CaptureState.Idle)
    }

    "terminate in case of underlying ComFailedException" in {
      val p2 = mock(classOf[P2Connection])
      val err = mock(classOf[ComFailException])
      when(p2.connect()).thenThrow(err)
      captureEventDispatcher(p2)

      val marketCapture = TestFSMRef(new MarketCapture(p2, scheme, repository, kestrel), "MarketCapture")
      watch(marketCapture)

      marketCapture ! Connect(ConnectionProperties(Host, Port, AppName))
      expectMsg(Terminated(marketCapture))
    }

    "terminate after Connection terminated" in {
      val p2 = mock(classOf[P2Connection])

      val marketCapture = TestFSMRef(new MarketCapture(p2, scheme, repository, kestrel), "MarketCapture")
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

      val marketCapture = TestFSMRef(new MarketCapture(p2, scheme, repository, kestrel), "MarketCapture")
      val underlying = marketCapture.underlyingActor.asInstanceOf[MarketCapture]

      marketCapture ! LifeNumChanged(underlying.ordersDataStream, 100)
      verify(repository).reset("FORTS_ORDLOG_REPL")

      marketCapture ! LifeNumChanged(underlying.optTradeDataStream, 100)
      verify(repository).reset("FORTS_OPTTRADE_REPL")

      marketCapture ! LifeNumChanged(underlying.futTradeDataStream, 100)
      verify(repository).reset("FORTS_FUTTRADE_REPL")
    }

    "initialize with Future session content" in {
      val gmkFuture = FutInfo.SessContentsRecord(7477, 47740, 0, 4023, 166911, "GMM2", "GMKR-6.12", "Фьючерсный контракт GMKR-06.12", 115, 2, 0)

      val p2 = mock(classOf[P2Connection])
      val marketCapture = TestFSMRef(new MarketCapture(p2, scheme, repository, kestrel), "MarketCapture")

      marketCapture.setState(CaptureState.InitializingMarketContents)
      marketCapture ! FuturesContents(Map(gmkFuture.isinId -> com.ergodicity.engine.core.model.BasicFutInfoConverter(gmkFuture)))

      assert(marketCapture.stateName == CaptureState.InitializingMarketContents)
      assert(marketCapture.stateData(166911).isin == "GMKR-6.12")
    }

    "initialize with Option session content" in {
      val rtsOption = OptInfo.SessContentsRecord(10881, 20023, 0, 3550, 160734, "RI175000BR2", "RTS-6.12M150612PA 175000", "Июньский Марж.Амер.Put.175000 Фьюч.контр RTS-6.12", 115)

      val p2 = mock(classOf[P2Connection])
      val marketCapture = TestFSMRef(new MarketCapture(p2, scheme, repository, kestrel), "MarketCapture")

      marketCapture.setState(CaptureState.InitializingMarketContents)
      marketCapture ! OptionsContents(Map(rtsOption.isinId -> com.ergodicity.engine.core.model.BasicOptInfoConverter(rtsOption)))

      assert(marketCapture.stateName == CaptureState.InitializingMarketContents)
      assert(marketCapture.stateData(160734).isin == "RTS-6.12M150612PA 175000")
    }

    "handle multiple contents updates" in {
      val gmkFuture = FutInfo.SessContentsRecord(7477, 47740, 0, 4023, 166911, "GMM2", "GMKR-6.12", "Фьючерсный контракт GMKR-06.12", 115, 2, 0)
      val lukFuture = FutInfo.SessContentsRecord(7477, 47740, 0, 4023, 166912, "LUK2", "LUKH-6.12", "Фьючерсный контракт LUKH-06.12", 115, 2, 0)

      val p2 = mock(classOf[P2Connection])
      val marketCapture = TestFSMRef(new MarketCapture(p2, scheme, repository, kestrel), "MarketCapture")

      marketCapture.setState(CaptureState.InitializingMarketContents)
      marketCapture ! FuturesContents(Map(gmkFuture.isinId -> com.ergodicity.engine.core.model.BasicFutInfoConverter(gmkFuture)))
      marketCapture ! FuturesContents(Map(gmkFuture.isinId -> com.ergodicity.engine.core.model.BasicFutInfoConverter(gmkFuture)))
      marketCapture ! FuturesContents(Map(gmkFuture.isinId -> com.ergodicity.engine.core.model.BasicFutInfoConverter(gmkFuture)))
      marketCapture ! FuturesContents(Map(lukFuture.isinId -> com.ergodicity.engine.core.model.BasicFutInfoConverter(lukFuture)))
      marketCapture ! FuturesContents(Map(lukFuture.isinId -> com.ergodicity.engine.core.model.BasicFutInfoConverter(lukFuture)))

      assert(marketCapture.stateName == CaptureState.InitializingMarketContents)
      assert(marketCapture.stateData.size == 2)
      assert(marketCapture.stateData(166911).isin == "GMKR-6.12")
      assert(marketCapture.stateData(166912).isin == "LUKH-6.12")
    }

    "terminate after Initializing timed out" in {
      val p2 = mock(classOf[P2Connection])
      val marketCapture = TestFSMRef(new MarketCapture(p2, scheme, repository, kestrel), "MarketCapture")

      marketCapture.setState(CaptureState.InitializingMarketContents)

      watch(marketCapture)
      marketCapture ! FSM.StateTimeout
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
