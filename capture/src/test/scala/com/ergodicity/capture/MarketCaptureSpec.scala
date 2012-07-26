package com.ergodicity.capture

import org.mockito.Mockito._
import org.mockito.Matchers._
import org.scalatest.{WordSpec, BeforeAndAfterAll}
import akka.testkit.{ImplicitSender, TestFSMRef, TestKit}
import akka.actor.{FSM, Terminated, ActorSystem}
import com.ergodicity.cgate.config.{CGateConfig, Replication}
import java.io.File
import ru.micexrts.cgate.{Connection => CGConnection, CGate, CGateException}
import com.ergodicity.capture.Mocking._
import com.ergodicity.capture.CaptureData.Contents
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import com.twitter.finagle.kestrel.Client
import com.ergodicity.cgate.DataStream.DataStreamReplState
import org.slf4j.{Logger, LoggerFactory}
import com.mongodb.casbah.TypeImports._

class MarketCaptureSpec extends TestKit(ActorSystem("MarketCaptureSpec", AkkaConfigurations.ConfigWithDetailedLogging)) with WordSpec with BeforeAndAfterAll with ImplicitSender {
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

  trait Repo extends ReplicationStateRepository with SessionRepository with FutSessionContentsRepository with OptSessionContentsRepository {
    val log: Logger = null

    val mongo: MongoDB = null
  }

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
      val repository = mock(classOf[Repo])
      val conn = spy(new CGConnection(RouterConnection()))

      val marketCapture = TestFSMRef(new MarketCapture(conn, replication, repository, KestrelMock), "MarketCapture")
      log.info("State: " + marketCapture.stateName)
      assert(marketCapture.stateName == CaptureState.Idle)
    }

    "terminate in case of underlying CGateException" in {
      val repository = mock(classOf[Repo])

      val conn = spy(new CGConnection(RouterConnection()))
      val err = mock(classOf[CGateException])
      doThrow(err).when(conn).open(any())

      val marketCapture = TestFSMRef(new MarketCapture(conn, replication, repository, KestrelMock), "MarketCapture")
      watch(marketCapture)

      marketCapture ! Capture
      expectMsg(Terminated(marketCapture))
    }

    "terminate after Connection terminated" in {
      val repository = mock(classOf[Repo])
      val conn = spy(new CGConnection(RouterConnection()))

      val marketCapture = TestFSMRef(new MarketCapture(conn, replication, repository, KestrelMock), "MarketCapture")
      val connection = marketCapture.underlyingActor.asInstanceOf[MarketCapture].connection

      Thread.sleep(300)

      watch(marketCapture)
      marketCapture ! Terminated(connection)
      expectMsg(Terminated(marketCapture))
    }

    "initialize with Future session content" in {
      val gmkFuture = mockFuture(4023, 166911, "GMKR-6.12", "GMM2", "Фьючерсный контракт GMKR-06.12", 115, 2, 0)

      val repository = mock(classOf[Repo])
      val conn = spy(new CGConnection(RouterConnection()))
      val marketCapture = TestFSMRef(new MarketCapture(conn, replication, repository, KestrelMock), "MarketCapture")

      Thread.sleep(300)

      marketCapture.setState(CaptureState.InitializingMarketContents)
      marketCapture ! FuturesContents(Map(gmkFuture.get_isin_id() -> com.ergodicity.core.session.FutureConverter(gmkFuture)))

      assert(marketCapture.stateName == CaptureState.InitializingMarketContents)
      assert(marketCapture.stateData.asInstanceOf[Contents].contents(166911).isin.isin == "GMKR-6.12")
    }

    "initialize with Option session content" in {
      val rtsOption = mockOption(3550, 160734, "RTS-6.12M150612PA 175000", "RI175000BR2", "Июньский Марж.Амер.Put.175000 Фьюч.контр RTS-6.12", 115)

      val repository = mock(classOf[Repo])
      val conn = spy(new CGConnection(RouterConnection()))
      val marketCapture = TestFSMRef(new MarketCapture(conn, replication, repository, KestrelMock), "MarketCapture")
      
      Thread.sleep(300)

      marketCapture.setState(CaptureState.InitializingMarketContents)
      marketCapture ! OptionsContents(Map(rtsOption.get_isin_id() -> com.ergodicity.core.session.OptionConverter(rtsOption)))

      assert(marketCapture.stateName == CaptureState.InitializingMarketContents)
      log.info("Isin = " + marketCapture.stateData.asInstanceOf[Contents].contents(160734).isin.isin)
      assert(marketCapture.stateData.asInstanceOf[Contents].contents(160734).isin.isin == "RTS-6.12M150612PA 175000")
    }

    "handle multiple contents updates" in {
      val gmkFuture = mockFuture(4023, 166911, "GMKR-6.12", "GMM2", "Фьючерсный контракт GMKR-06.12", 115, 2, 0)
      val lukFuture = mockFuture(4023, 166912, "LUKH-6.12", "LUK2", "Фьючерсный контракт LUKH-06.12", 115, 2, 0)

      val repository = mock(classOf[Repo])
      val conn = spy(new CGConnection(RouterConnection()))
      val marketCapture = TestFSMRef(new MarketCapture(conn, replication, repository, KestrelMock), "MarketCapture")
      
      Thread.sleep(300)

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
      val repository = mock(classOf[Repo])
      val conn = spy(new CGConnection(RouterConnection()))
      val marketCapture = TestFSMRef(new MarketCapture(conn, replication, repository, KestrelMock), "MarketCapture")

      Thread.sleep(300)

      marketCapture.setState(CaptureState.InitializingMarketContents)

      watch(marketCapture)
      marketCapture ! FSM.StateTimeout
      expectMsg(Terminated(marketCapture))
    }

    "wait for all data streams closed" in {
      val repository = mock(classOf[Repo])
      val conn = spy(new CGConnection(RouterConnection()))
      val marketCapture = TestFSMRef(new MarketCapture(conn, replication, repository, KestrelMock), "MarketCapture")
      val underlying = marketCapture.underlyingActor.asInstanceOf[MarketCapture]

      doNothing().when(conn).close()
      doNothing().when(conn).dispose()

      Thread.sleep(300)
      
      watch(marketCapture)

      marketCapture.setState(CaptureState.ShuttingDown, CaptureData.StreamStates())
      log.info("State = " + marketCapture.stateName + "; data = " + marketCapture.stateData)

      marketCapture ! DataStreamReplState(underlying.FutInfoStream, "FutInfoState")
      marketCapture ! DataStreamReplState(underlying.OptInfoStream, "OptInfoState")
      marketCapture ! DataStreamReplState(underlying.FutTradeStream, "FutTradeState")
      marketCapture ! DataStreamReplState(underlying.OptTradeStream, "OptTradeState")
      marketCapture ! DataStreamReplState(underlying.OrdLogStream, "OrdLogState")

      verify(repository).setReplicationState("FORTS_FUTINFO_REPL", "FutInfoState")
      verify(repository).setReplicationState("FORTS_OPTINFO_REPL", "OptInfoState")
      verify(repository).setReplicationState("FORTS_FUTTRADE_REPL", "FutTradeState")
      verify(repository).setReplicationState("FORTS_OPTTRADE_REPL", "OptTradeState")
      verify(repository).setReplicationState("FORTS_ORDLOG_REPL", "OrdLogState")

      expectMsg(Terminated(marketCapture))
      
      verify(conn).close()
      verify(conn).dispose()
    }
  }

  case object KestrelMock extends KestrelConfig {
    def apply() = mock(classOf[Client])

    def tradesQueue = "trades"

    def ordersQueue = "orders"
  }

}
