package com.ergodicity.capture

import org.mockito.Mockito._
import org.mockito.Matchers._
import org.scalatest.{WordSpec, BeforeAndAfterAll}
import akka.testkit._
import akka.actor._
import com.ergodicity.cgate.config.Replication
import java.io.File
import ru.micexrts.cgate.{Connection => CGConnection, CGate, CGateException}
import com.ergodicity.capture.Mocking._
import com.twitter.finagle.kestrel.Client
import org.slf4j.{Logger, LoggerFactory}
import com.mongodb.casbah.TypeImports._
import com.ergodicity.capture.MarketCapture.Capture
import com.ergodicity.cgate.DataStream.DataStreamReplState
import com.ergodicity.cgate.config.CGateConfig
import com.ergodicity.capture.CaptureData.Contents
import akka.actor.Terminated
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import akka.actor.SupervisorStrategy.Stop
import com.ergodicity.core.WhenUnhandled

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

    "fail in case of underlying CGateException" in {
      val repository = mock(classOf[Repo])

      val conn = spy(new CGConnection(RouterConnection()))
      val err = mock(classOf[CGateException])
      doThrow(err).when(conn).open(any())

      val guardian = guardMarketCapture(conn, repository)

      watch(guardian.underlyingActor.marketCapture)
      guardian.underlyingActor.marketCapture ! Capture
      expectMsg(Terminated(guardian.underlyingActor.marketCapture))
    }

    "terminate after Connection terminated" in {
      val repository = mock(classOf[Repo])
      val conn = spy(new CGConnection(RouterConnection()))

      val guardian = guardMarketCapture(conn, repository)
      val connection = guardian.underlyingActor.connection

      watch(guardian.underlyingActor.marketCapture)
      guardian.underlyingActor.marketCapture ! Terminated(connection)
      expectMsg(Terminated(guardian.underlyingActor.marketCapture))
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

      marketCapture ! DataStreamReplState(underlying.FutTradeStream, "FutTradeState")
      marketCapture ! DataStreamReplState(underlying.OptTradeStream, "OptTradeState")
      marketCapture ! DataStreamReplState(underlying.OrdLogStream, "OrdLogState")

      Thread.sleep(300)

      verify(repository).setReplicationState("FORTS_FUTTRADE_REPL", "FutTradeState")
      verify(repository).setReplicationState("FORTS_OPTTRADE_REPL", "OptTradeState")
      verify(repository).setReplicationState("FORTS_ORDLOG_REPL", "OrdLogState")

      verify(conn).close()
      verify(conn).dispose()
    }
  }

  def guardMarketCapture(conn: CGConnection, repository: Repo) = {
    val guardian = TestActorRef(new Actor with WhenUnhandled with ActorLogging {
      override val supervisorStrategy = AllForOneStrategy() {
        case _: MarketCaptureException => Stop
      }

      private lazy val capture: MarketCapture = new MarketCapture(conn, replication, repository, KestrelMock)

      lazy val connection = capture.connection

      val marketCapture = context.actorOf(Props(capture), "MarketCapture")

      protected def receive = whenUnhandled
    }, "Guardian")
    Thread.sleep(100)
    guardian
  }


  case object KestrelMock extends KestrelConfig {
    def apply() = mock(classOf[Client])

    def tradesQueue = "trades"

    def ordersQueue = "orders"
  }

}
