package com.ergodicity.capture

import org.mockito.Mockito._
import org.mockito.Matchers._
import org.scalatest.{WordSpec, BeforeAndAfterAll}
import akka.testkit._
import akka.actor._
import com.ergodicity.cgate.config.Replication
import java.io.File
import ru.micexrts.cgate.{Connection => CGConnection, CGate, CGateException, Listener => CGListener}
import com.ergodicity.capture.Mocking._
import com.twitter.finagle.kestrel.Client
import org.slf4j.LoggerFactory
import com.ergodicity.capture.MarketCapture.Capture
import com.ergodicity.cgate.DataStream.DataStreamClosed
import com.ergodicity.cgate.config.CGateConfig
import com.ergodicity.capture.CaptureData.Contents
import akka.actor.Terminated
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import akka.actor.SupervisorStrategy.Stop
import com.ergodicity.cgate.WhenUnhandled
import com.ergodicity.cgate.StreamEvent.ReplState

class MarketCaptureSpec extends TestKit(ActorSystem("MarketCaptureSpec")) with WordSpec with BeforeAndAfterAll with ImplicitSender {
  val log = LoggerFactory.getLogger(classOf[MarketCaptureSpec])

  val Host = "localhost"
  val Port = 4001

  val RouterConnection = Tcp(Host, Port, system.name)

  val replication = ReplicationScheme(
    Replication("FORTS_FUTINFO_REPL", new File("cgate/scheme/FutInfo.ini"), "CustReplScheme"),
    Replication("FORTS_OPTINFO_REPL", new File("cgate/scheme/OptInfo.ini"), "CustReplScheme"),
    Replication("FORTS_ORDLOG_REPL", new File("cgate/scheme/OrdLog.ini"), "CustReplScheme"),
    Replication("FORTS_FUTTRADE_REPL", new File("cgate/scheme/FutTrades.ini"), "CustReplScheme"),
    Replication("FORTS_OPTTRADE_REPL", new File("cgate/scheme/OptTrades.ini"), "CustReplScheme")
  )

  trait Repo extends MarketCaptureRepository with ReplicationStateRepository with SessionRepository with FutSessionContentsRepository with OptSessionContentsRepository

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
      val marketCapture = TestFSMRef(new MarketCapture(replication, repository, KestrelMock) with CaptureConnection with MockedListeners with CaptureListenersImpl {
        def underlyingConnection = mock(classOf[CGConnection])
      }, "MarketCapture")
      log.info("State: " + marketCapture.stateName)
      assert(marketCapture.stateName == CaptureState.Idle)
    }

    "fail in case of underlying CGateException" in {
      val repository = mock(classOf[Repo])

      val conn = mock(classOf[CGConnection])
      val err = mock(classOf[CGateException])
      doThrow(err).when(conn).open(any())

      val guardian = guardMarketCapture(conn, repository)

      watch(guardian.underlyingActor.marketCapture)
      guardian.underlyingActor.marketCapture ! Capture
      expectMsg(Terminated(guardian.underlyingActor.marketCapture))
    }

    "terminate after Connection terminated" in {
      val repository = mock(classOf[Repo])
      val conn = mock(classOf[CGConnection])

      val guardian = guardMarketCapture(conn, repository)
      val connection = guardian.underlyingActor.connection

      watch(guardian.underlyingActor.marketCapture)
      guardian.underlyingActor.marketCapture ! Terminated(connection)
      expectMsg(Terminated(guardian.underlyingActor.marketCapture))
    }

    "initialize with Future session content" in {
      val gmkFuture = mockFuture(4023, 166911, "GMKR-6.12", "GMM2", "Фьючерсный контракт GMKR-06.12", 115, 2, 0)

      val repository = mock(classOf[Repo])
      val marketCapture = TestFSMRef(new MarketCapture(replication, repository, KestrelMock) with CaptureConnection with MockedListeners with CaptureListenersImpl {
        def underlyingConnection = mock(classOf[CGConnection])
      }, "MarketCapture")

      Thread.sleep(300)

      marketCapture.setState(CaptureState.InitializingMarketContents)
      marketCapture ! FuturesContents(Map(gmkFuture.get_isin_id() -> com.ergodicity.core.session.Implicits.FutureInstrument.security(gmkFuture)))

      assert(marketCapture.stateName == CaptureState.InitializingMarketContents)
      assert(marketCapture.stateData.asInstanceOf[Contents].contents(166911).isin.isin == "GMKR-6.12")
    }

    "initialize with Option session content" in {
      val rtsOption = mockOption(3550, 160734, "RTS-6.12M150612PA 175000", "RI175000BR2", "Июньский Марж.Амер.Put.175000 Фьюч.контр RTS-6.12", 115)

      val repository = mock(classOf[Repo])
      val marketCapture = TestFSMRef(new MarketCapture(replication, repository, KestrelMock) with CaptureConnection with MockedListeners with CaptureListenersImpl {
        def underlyingConnection = mock(classOf[CGConnection])
      }, "MarketCapture")

      Thread.sleep(300)

      marketCapture.setState(CaptureState.InitializingMarketContents)
      marketCapture ! OptionsContents(Map(rtsOption.get_isin_id() -> com.ergodicity.core.session.Implicits.OptionInstrument.security(rtsOption)))

      assert(marketCapture.stateName == CaptureState.InitializingMarketContents)
      log.info("Isin = " + marketCapture.stateData.asInstanceOf[Contents].contents(160734).isin.isin)
      assert(marketCapture.stateData.asInstanceOf[Contents].contents(160734).isin.isin == "RTS-6.12M150612PA 175000")
    }

    "handle multiple contents updates" in {
      val gmkFuture = mockFuture(4023, 166911, "GMKR-6.12", "GMM2", "Фьючерсный контракт GMKR-06.12", 115, 2, 0)
      val lukFuture = mockFuture(4023, 166912, "LUKH-6.12", "LUK2", "Фьючерсный контракт LUKH-06.12", 115, 2, 0)

      val repository = mock(classOf[Repo])
      val marketCapture = TestFSMRef(new MarketCapture(replication, repository, KestrelMock) with CaptureConnection with MockedListeners with CaptureListenersImpl {
        def underlyingConnection = mock(classOf[CGConnection])
      }, "MarketCapture")

      Thread.sleep(300)

      marketCapture.setState(CaptureState.InitializingMarketContents)
      marketCapture ! FuturesContents(Map(gmkFuture.get_isin_id() -> com.ergodicity.core.session.Implicits.FutureInstrument.security(gmkFuture)))
      marketCapture ! FuturesContents(Map(gmkFuture.get_isin_id() -> com.ergodicity.core.session.Implicits.FutureInstrument.security(gmkFuture)))
      marketCapture ! FuturesContents(Map(gmkFuture.get_isin_id() -> com.ergodicity.core.session.Implicits.FutureInstrument.security(gmkFuture)))
      marketCapture ! FuturesContents(Map(lukFuture.get_isin_id() -> com.ergodicity.core.session.Implicits.FutureInstrument.security(lukFuture)))
      marketCapture ! FuturesContents(Map(lukFuture.get_isin_id() -> com.ergodicity.core.session.Implicits.FutureInstrument.security(lukFuture)))

      log.info("State = " + marketCapture.stateName)
      assert(marketCapture.stateName == CaptureState.InitializingMarketContents)
      assert(marketCapture.stateData.asInstanceOf[Contents].contents.size == 2)
      assert(marketCapture.stateData.asInstanceOf[Contents].contents(166911).isin.isin == "GMKR-6.12")
      assert(marketCapture.stateData.asInstanceOf[Contents].contents(166912).isin.isin == "LUKH-6.12")
    }

    "wait for all data streams closed" in {
      val repository = mock(classOf[Repo])
      val conn = mock(classOf[CGConnection])
      val marketCapture = TestFSMRef(new MarketCapture(replication, repository, KestrelMock) with CaptureConnection with MockedListeners with CaptureListenersImpl {
        def underlyingConnection = conn
      }, "MarketCapture")
      val underlying = marketCapture.underlyingActor.asInstanceOf[MarketCapture]

      doNothing().when(conn).close()
      doNothing().when(conn).dispose()

      Thread.sleep(300)

      watch(marketCapture)

      marketCapture.setState(CaptureState.ShuttingDown, CaptureData.StreamStates())
      log.info("State = " + marketCapture.stateName + "; data = " + marketCapture.stateData)

      marketCapture ! DataStreamClosed(underlying.FutTradeStream, ReplState("FutTradeState"))
      marketCapture ! DataStreamClosed(underlying.OptTradeStream, ReplState("OptTradeState"))
      marketCapture ! DataStreamClosed(underlying.OrdLogStream, ReplState("OrdLogState"))

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

      private lazy val capture: MarketCapture = new MarketCapture(replication, repository, KestrelMock) with CaptureConnection with MockedListeners with CaptureListenersImpl {
        def underlyingConnection = conn
      }

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

  trait MockedListeners extends UnderlyingListeners {
    self: MarketCapture =>

    lazy val  underlyingFutInfoListener = mock(classOf[CGListener])

    lazy val  underlyingOptInfoListener = mock(classOf[CGListener])

    lazy val  underlyingFutTradeListener = mock(classOf[CGListener])

    lazy val  underlyingOptTradeListener = mock(classOf[CGListener])

    lazy val underlyingOrdLogListener = mock(classOf[CGListener])
  }

}
