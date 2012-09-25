package com.ergodicity.capture

import org.mockito.Mockito._
import org.scalatest.{WordSpec, BeforeAndAfterAll}
import akka.testkit.{ImplicitSender, TestFSMRef, TestKit}
import org.slf4j.LoggerFactory
import akka.actor.ActorSystem
import com.ergodicity.cgate.config.Replication
import java.io.File
import com.ergodicity.cgate.DataStream
import com.ergodicity.capture.Mocking._
import org.squeryl.{Session => SQRLSession, SessionFactory}
import org.squeryl.adapters.H2Adapter
import org.squeryl.PrimitiveTypeMode._
import com.ergodicity.capture.CaptureSchema._
import com.ergodicity.capture.Repository.Snapshot
import scala.Some

class MarketContentsCaptureSpec extends TestKit(ActorSystem("MarketContentsCaptureSpec")) with WordSpec with BeforeAndAfterAll with ImplicitSender {
  val log = LoggerFactory.getLogger(classOf[MarketContentsCaptureSpec])

  override protected def beforeAll() {
    initialize()
  }

  override def afterAll() {
    system.shutdown()
  }

  val replication = ReplicationScheme(
    Replication("FORTS_FUTINFO_REPL", new File("cgate/scheme/FutInfo.ini"), "CustReplScheme"),
    Replication("FORTS_OPTINFO_REPL", new File("cgate/scheme/OptInfo.ini"), "CustReplScheme"),
    Replication("FORTS_ORDLOG_REPL", new File("cgate/scheme/OrdLog.ini"), "CustReplScheme"),
    Replication("FORTS_FUTTRADE_REPL", new File("cgate/scheme/FutTrades.ini"), "CustReplScheme"),
    Replication("FORTS_OPTTRADE_REPL", new File("cgate/scheme/OptTrades.ini"), "CustReplScheme")
  )

  trait Repo extends MarketCaptureRepository with SessionRepository with FutSessionContentsRepository with OptSessionContentsRepository

  val repository = mock(classOf[Repo])

  "MarketContentsCapture" must {

    "save session data" in {
      val FutInfoStream = TestFSMRef(new DataStream, "FutInfoStream")
      val OptInfoStream = TestFSMRef(new DataStream, "OptInfoStream")

      val capture = TestFSMRef(new MarketContentsCapture(FutInfoStream, OptInfoStream, repository), "MarketContentsCapture")
      val underlying = capture.underlyingActor.asInstanceOf[MarketContentsCapture]

      val record = Mocking.mockSession(1000, 1000)
      capture ! Snapshot(underlying.SessionsRepository, record :: Nil)

      verify(repository).saveSession(record)
    }

    "initialize with Future session content" in {
      val gmkFuture = mockFuture(4023, 166911, "GMM2", "GMKR-6.12", "Фьючерсный контракт GMKR-06.12", 115, 2, 0)

      val FutInfoStream = TestFSMRef(new DataStream, "FutInfoStream")
      val OptInfoStream = TestFSMRef(new DataStream, "OptInfoStream")

      val capture = TestFSMRef(new MarketContentsCapture(FutInfoStream, OptInfoStream, repository), "MarketContentsCapture")
      val underlying = capture.underlyingActor.asInstanceOf[MarketContentsCapture]

      Thread.sleep(100)

      capture ! SubscribeMarketContents(self)
      capture ! Snapshot(underlying.FutSessContentsRepository, gmkFuture :: Nil)

      expectMsg(FuturesContents(Map(gmkFuture.get_isin_id() -> com.ergodicity.core.session.Implicits.FutureInstrument.security(gmkFuture))))

      verify(repository).saveSessionContents(gmkFuture)
    }

    "initialize with Option session content" in {
      val rtsOption = mockOption(3550, 160734, "RI175000BR2", "RTS-6.12M150612PA 175000", "Июньский Марж.Амер.Put.175000 Фьюч.контр RTS-6.12", 115)

      val FutInfoStream = TestFSMRef(new DataStream, "FutInfoStream")
      val OptInfoStream = TestFSMRef(new DataStream, "OptInfoStream")

      val capture = TestFSMRef(new MarketContentsCapture(FutInfoStream, OptInfoStream, repository), "MarketContentsCapture")
      val underlying = capture.underlyingActor.asInstanceOf[MarketContentsCapture]

      Thread.sleep(100)

      capture ! SubscribeMarketContents(self)
      capture ! Snapshot(underlying.OptSessContentsRepository, rtsOption :: Nil)

      expectMsg(OptionsContents(Map(rtsOption.get_isin_id() -> com.ergodicity.core.session.Implicits.OptionInstrument.security(rtsOption))))
      verify(repository).saveSessionContents(rtsOption)
    }
  }

  def initialize() {
    println("Setting up data.")
    Class.forName("org.h2.Driver")
    SessionFactory.concreteFactory = Some(() =>
      SQRLSession.create(
        java.sql.DriverManager.getConnection("jdbc:h2:~/MarketContentsCaptureSpec", "sa", ""),
        new H2Adapter)
    )

    inTransaction {
      drop
      create
    }
  }


}