package com.ergodicity.capture

import org.mockito.Mockito._
import org.scalatest.{WordSpec, BeforeAndAfterAll}
import akka.testkit.{ImplicitSender, TestFSMRef, TestKit}
import plaza2.{Connection => P2Connection}
import org.slf4j.LoggerFactory
import com.ergodicity.plaza2.Repository.Snapshot
import com.ergodicity.plaza2.scheme.{OptInfo, FutInfo}
import akka.actor.ActorSystem
import com.ergodicity.core.session.SessionState
import com.ergodicity.core.session.SessionState._
import com.ergodicity.plaza2.scheme.FutInfo.SessionRecord

class MarketContentsCaptureSpec extends TestKit(ActorSystem("MarketContentsCaptureSpec")) with WordSpec with BeforeAndAfterAll with ImplicitSender {
  val log = LoggerFactory.getLogger(classOf[MarketContentsCaptureSpec])

  override def afterAll() {
    system.shutdown()
  }

  val scheme = Plaza2Scheme(
    "capture/scheme/FutInfoSessionsAndContents.ini",
    "capture/scheme/OptInfoSessionContents.ini",
    "capture/scheme/OrdLog.ini",
    "capture/scheme/FutTradeDeal.ini",
    "capture/scheme/OptTradeDeal.ini"
  )

  val sessionTracker = mock(classOf[SessionRepository])
  val futSessionTracker = mock(classOf[FutSessionContentsRepository])
  val optSessionTracker = mock(classOf[OptSessionContentsRepository])

  "MarketContentsCapture" must {

    "save session data" in {
      val p2 = mock(classOf[P2Connection])
      val capture = TestFSMRef(new MarketContentsCapture(p2, scheme, sessionTracker, futSessionTracker, optSessionTracker), "MarketContentsCapture")
      val underlying = capture.underlyingActor.asInstanceOf[MarketContentsCapture]

      val record = sessionRecord(1000, 1000, 12345, Assigned)
      capture ! Snapshot(underlying.sessionsRepository, record :: Nil)

      verify(sessionTracker).saveSession(record)
    }

    "initialize with Future session content" in {
      val gmkFuture = FutInfo.SessContentsRecord(7477, 47740, 0, 4023, 166911, "GMM2", "GMKR-6.12", "Фьючерсный контракт GMKR-06.12", 115, 2, 0)

      val p2 = mock(classOf[P2Connection])
      val capture = TestFSMRef(new MarketContentsCapture(p2, scheme, sessionTracker, futSessionTracker, optSessionTracker), "MarketContentsCapture")
      val underlying = capture.underlyingActor.asInstanceOf[MarketContentsCapture]

      capture ! SubscribeMarketContents(self)
      capture ! Snapshot(underlying.futSessContentsRepository, gmkFuture :: Nil)

      expectMsg(FuturesContents(Map(gmkFuture.isinId -> com.ergodicity.core.session.BasicFutInfoConverter(gmkFuture))))

      verify(futSessionTracker).saveSessionContents(gmkFuture)
    }

    "initialize with Option session content" in {
      val rtsOption = OptInfo.SessContentsRecord(10881, 20023, 0, 3550, 160734, "RI175000BR2", "RTS-6.12M150612PA 175000", "Июньский Марж.Амер.Put.175000 Фьюч.контр RTS-6.12", 115)

      val p2 = mock(classOf[P2Connection])
      val capture = TestFSMRef(new MarketContentsCapture(p2, scheme, sessionTracker, futSessionTracker, optSessionTracker), "MarketContentsCapture")
      val underlying = capture.underlyingActor.asInstanceOf[MarketContentsCapture]

      capture ! SubscribeMarketContents(self)
      capture ! Snapshot(underlying.optSessContentsRepository, rtsOption :: Nil)

      expectMsg(OptionsContents(Map(rtsOption.isinId -> com.ergodicity.core.session.BasicOptInfoConverter(rtsOption))))
      verify(optSessionTracker).saveSessionContents(rtsOption)
    }
  }

  def sessionRecord(replID: Long, revId: Long, sessionId: Int, sessionState: SessionState) = {
    import SessionState._

    val begin = "2012/04/12 07:15:00.000"
    val end = "2012/04/12 14:45:00.000"
    val interClBegin = "2012/04/12 12:00:00.000"
    val interClEnd = "2012/04/12 12:05:00.000"
    val eveBegin = "2012/04/11 15:30:00.000"
    val eveEnd = "2012/04/11 23:50:00.000"
    val monBegin = "2012/04/12 07:00:00.000"
    val monEnd = "2012/04/12 07:15:00.000"
    val posTransferBegin = "2012/04/12 13:00:00.000"
    val posTransferEnd = "2012/04/12 13:15:00.000"

    val stateValue = sessionState match {
      case Assigned => 0
      case Online => 1
      case Suspended => 2
      case Canceled => 3
      case Completed => 4
    }

    SessionRecord(replID, revId, 0, sessionId, begin, end, stateValue, 3547, interClBegin, interClEnd, 5136, 1, eveBegin, eveEnd, 1, monBegin, monEnd, posTransferBegin, posTransferEnd)
  }

}