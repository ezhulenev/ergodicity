package integration.ergodicity.capture

import org.mockito.Mockito._
import org.mockito.Matchers._
import org.scalatest.{WordSpec, BeforeAndAfterAll}
import akka.testkit.{ImplicitSender, TestFSMRef, TestKit}
import org.slf4j.LoggerFactory
import akka.actor.{FSM, Terminated, ActorSystem}
import com.ergodicity.capture._
import com.ergodicity.capture.{MarketDbRepository, CaptureState, MarketCapture}
import com.ergodicity.cgate.config.{CGateConfig, Replication}
import java.io.File
import ru.micexrts.cgate.{Connection => CGConnection, CGate, CGateException}
import com.ergodicity.core.Mocking._
import com.ergodicity.capture.CaptureData.Contents
import com.ergodicity.cgate.config.ConnectionType.Tcp

class MarketCaptureSpec extends TestKit(ActorSystem("MarketCaptureSpec")) with WordSpec with BeforeAndAfterAll with ImplicitSender {
  val log = LoggerFactory.getLogger(classOf[MarketCaptureSpec])

  val Host = "localhost"
  val Port = 4001

  val RouterConnection = Tcp(Host, Port, system.name)

  val replication = ReplicationScheme(
    Replication("FORTS_FUTINFO_REPL", new File("cgate/scheme/fut_info.ini"), "CustReplScheme"),
    Replication("FORTS_OPTINFO_REPL", new File("cgate/scheme/opt_info.ini"), "CustReplScheme"),
    Replication("FORTS_ORDLOG_REPL", new File("cgate/scheme/ordLog_trades.ini"), "CustReplScheme"),
    Replication("FORTS_FUTTRADE_REPL", new File("cgate/scheme/fut_trades.ini"), "CustReplScheme"),
    Replication("FORTS_OPTTRADE_REPL", new File("cgate/scheme/opt_trades.ini"), "CustReplScheme")
  )

  val repository = mock(classOf[MarketDbRepository])
  //when(repository.replicationState(anyString())).thenReturn(None)

  val kestrel = KestrelConfig("localhost", 22133, "trades", "orders", 30)

  override def beforeAll() {
    val props = CGateConfig(new File("cgate/scheme/cgate_dev.ini"), "11111111")
    CGate.open(props())
  }

  override def afterAll() {
    system.shutdown()
    CGate.close()
  }

  "MarketCapture" must {

    "be initialized in Idle state" in {
      val conn = spy(new CGConnection(RouterConnection()))
      val marketCapture = TestFSMRef(new MarketCapture(conn, replication, repository, kestrel), "MarketCapture")
      log.info("State: " + marketCapture.stateName)
      assert(marketCapture.stateName == CaptureState.Idle)
    }

    "terminate in case of underlying CGateException" in {
      val conn = spy(new CGConnection(RouterConnection()))
      val err = mock(classOf[CGateException])
      doThrow(err).when(conn).open(any())

      val marketCapture = TestFSMRef(new MarketCapture(conn, replication, repository, kestrel), "MarketCapture")
      watch(marketCapture)

      marketCapture ! Capture
      expectMsg(Terminated(marketCapture))
    }

    "terminate after Connection terminated" in {
      val conn = spy(new CGConnection(RouterConnection()))

      val marketCapture = TestFSMRef(new MarketCapture(conn, replication, repository, kestrel), "MarketCapture")
      val connection = marketCapture.underlyingActor.asInstanceOf[MarketCapture].connection

      watch(marketCapture)
      marketCapture ! Terminated(connection)
      expectMsg(Terminated(marketCapture))
    }

    "initialize with Future session content" in {
      val gmkFuture = mockFuture(4023, 166911, "GMKR-6.12", "GMM2", "Фьючерсный контракт GMKR-06.12", 115, 2, 0)

      val conn = spy(new CGConnection(RouterConnection()))
      val marketCapture = TestFSMRef(new MarketCapture(conn, replication, repository, kestrel), "MarketCapture")

      marketCapture.setState(CaptureState.InitializingMarketContents)
      marketCapture ! FuturesContents(Map(gmkFuture.get_isin_id() -> com.ergodicity.core.session.FutureConverter(gmkFuture)))

      assert(marketCapture.stateName == CaptureState.InitializingMarketContents)
      assert(marketCapture.stateData.asInstanceOf[Contents].contents(166911).isin.isin == "GMKR-6.12")
    }

    "initialize with Option session content" in {
      val rtsOption = mockOption(3550, 160734, "RTS-6.12M150612PA 175000", "RI175000BR2", "Июньский Марж.Амер.Put.175000 Фьюч.контр RTS-6.12", 115)

      val conn = spy(new CGConnection(RouterConnection()))
      val marketCapture = TestFSMRef(new MarketCapture(conn, replication, repository, kestrel), "MarketCapture")

      marketCapture.setState(CaptureState.InitializingMarketContents)
      marketCapture ! OptionsContents(Map(rtsOption.get_isin_id() -> com.ergodicity.core.session.OptionConverter(rtsOption)))

      assert(marketCapture.stateName == CaptureState.InitializingMarketContents)
      log.info("Isin = " + marketCapture.stateData.asInstanceOf[Contents].contents(160734).isin.isin)
      assert(marketCapture.stateData.asInstanceOf[Contents].contents(160734).isin.isin == "RTS-6.12M150612PA 175000")
    }

    "handle multiple contents updates" in {
      val gmkFuture = mockFuture(4023, 166911, "GMKR-6.12", "GMM2", "Фьючерсный контракт GMKR-06.12", 115, 2, 0)
      val lukFuture = mockFuture(4023, 166912, "LUKH-6.12", "LUK2", "Фьючерсный контракт LUKH-06.12", 115, 2, 0)

      val conn = spy(new CGConnection(RouterConnection()))
      val marketCapture = TestFSMRef(new MarketCapture(conn, replication, repository, kestrel), "MarketCapture")

      marketCapture.setState(CaptureState.InitializingMarketContents)
      marketCapture ! FuturesContents(Map(gmkFuture.get_isin_id() -> com.ergodicity.core.session.FutureConverter(gmkFuture)))
      marketCapture ! FuturesContents(Map(gmkFuture.get_isin_id() -> com.ergodicity.core.session.FutureConverter(gmkFuture)))
      marketCapture ! FuturesContents(Map(gmkFuture.get_isin_id() -> com.ergodicity.core.session.FutureConverter(gmkFuture)))
      marketCapture ! FuturesContents(Map(lukFuture.get_isin_id() -> com.ergodicity.core.session.FutureConverter(lukFuture)))
      marketCapture ! FuturesContents(Map(lukFuture.get_isin_id() -> com.ergodicity.core.session.FutureConverter(lukFuture)))

      log.info("State = " + marketCapture.stateName)
      assert(marketCapture.stateName == CaptureState.InitializingMarketContents)
      assert(marketCapture.stateData.asInstanceOf[Contents].contents.size == 2)
      assert(marketCapture.stateData.asInstanceOf[Contents].contents(166911).isin.isin == "GMKR-6.12")
      assert(marketCapture.stateData.asInstanceOf[Contents].contents(166912).isin.isin == "LUKH-6.12")
    }

    "terminate after Initializing timed out" in {
      val conn = spy(new CGConnection(RouterConnection()))
      val marketCapture = TestFSMRef(new MarketCapture(conn, replication, repository, kestrel), "MarketCapture")

      marketCapture.setState(CaptureState.InitializingMarketContents)

      watch(marketCapture)
      marketCapture ! FSM.StateTimeout
      expectMsg(Terminated(marketCapture))
    }
  }
}
