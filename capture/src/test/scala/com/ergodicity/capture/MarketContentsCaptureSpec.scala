package com.ergodicity.capture

import org.mockito.Mockito._
import org.scalatest.{WordSpec, BeforeAndAfterAll}
import akka.testkit.{ImplicitSender, TestFSMRef, TestKit}
import org.slf4j.{Logger, LoggerFactory}
import akka.actor.ActorSystem
import com.ergodicity.core.session.SessionState
import com.ergodicity.core.session.SessionState._
import com.ergodicity.cgate.config.Replication
import java.io.File
import com.ergodicity.cgate.DataStream
import java.nio.ByteBuffer
import com.ergodicity.cgate.scheme.FutInfo
import com.ergodicity.capture.Mocking._
import com.mongodb.casbah.Imports._
import com.ergodicity.capture.Repository.Snapshot

class MarketContentsCaptureSpec extends TestKit(ActorSystem("MarketContentsCaptureSpec")) with WordSpec with BeforeAndAfterAll with ImplicitSender {
  val log = LoggerFactory.getLogger(classOf[MarketContentsCaptureSpec])

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

  trait Repo extends SessionRepository with FutSessionContentsRepository with OptSessionContentsRepository {
    def log: Logger = null
    def mongo: MongoDB = null
  }

  val repository = mock(classOf[Repo])

  "MarketContentsCapture" must {

    "save session data" in {
      val FutInfoStream = TestFSMRef(new DataStream, "FutInfoStream")
      val OptInfoStream = TestFSMRef(new DataStream, "OptInfoStream")

      val capture = TestFSMRef(new MarketContentsCapture(FutInfoStream, OptInfoStream, repository), "MarketContentsCapture")
      val underlying = capture.underlyingActor.asInstanceOf[MarketContentsCapture]

      val record = sessionRecord(1000, 1000, 12345, Assigned)
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

      expectMsg(FuturesContents(Map(gmkFuture.get_isin_id() -> com.ergodicity.core.session.Implicits.FutInfoToFuture.convert(gmkFuture))))

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

      expectMsg(OptionsContents(Map(rtsOption.get_isin_id() -> com.ergodicity.core.session.Implicits.OptInfoToOption.convert(rtsOption))))
      verify(repository).saveSessionContents(rtsOption)
    }
  }

  def sessionRecord(replID: Long, revId: Long, sessionId: Int, sessionState: SessionState) = {
    import SessionState._

    val stateValue = sessionState match {
      case Assigned => 0
      case Online => 1
      case Suspended => 2
      case Canceled => 3
      case Completed => 4
    }

    val buff = ByteBuffer.allocate(1000)
    val session = new FutInfo.session(buff)
    session.set_replID(replID)
    session.set_replRev(revId)
    session.set_sess_id(sessionId)
    session.set_state(stateValue)
    session
  }

}