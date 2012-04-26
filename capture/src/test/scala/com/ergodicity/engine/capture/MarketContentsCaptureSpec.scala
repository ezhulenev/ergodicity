package com.ergodicity.engine.capture

import org.mockito.Mockito._
import org.scalatest.{WordSpec, BeforeAndAfterAll}
import akka.testkit.{ImplicitSender, TestFSMRef, TestKit}
import plaza2.{Connection => P2Connection}
import org.slf4j.LoggerFactory
import com.ergodicity.engine.plaza2.Repository.Snapshot
import com.ergodicity.engine.plaza2.scheme.{OptInfo, FutInfo}
import akka.actor.ActorSystem

class MarketContentsCaptureSpec extends TestKit(ActorSystem("MarketContentsCaptureSpec")) with WordSpec with BeforeAndAfterAll with ImplicitSender {
  val log = LoggerFactory.getLogger(classOf[MarketContentsCaptureSpec])

  override def afterAll() {
    system.shutdown()
  }

  val scheme = Plaza2Scheme(
    "capture/scheme/FutInfoSessionContents.ini",
    "capture/scheme/OptInfoSessionContents.ini",
    "capture/scheme/OrdLog.ini",
    "capture/scheme/FutTradeDeal.ini",
    "capture/scheme/OptTradeDeal.ini"
  )

  "MarketContentsCapture" must {

    "initialize with Future session content" in {
      val gmkFuture = FutInfo.SessContentsRecord(7477, 47740, 0, 4023, 166911, "GMM2", "GMKR-6.12", "Фьючерсный контракт GMKR-06.12", 115, 2, 0)

      val p2 = mock(classOf[P2Connection])
      val capture = TestFSMRef(new MarketContentsCapture(p2, scheme), "MarketContentsCapture")
      val underlying = capture.underlyingActor.asInstanceOf[MarketContentsCapture]

      capture ! SubscribeMarketContents(self)
      capture ! Snapshot(underlying.futSessContentsRepository, gmkFuture :: Nil)

      expectMsg(FuturesContents(Map(gmkFuture.isinId -> com.ergodicity.engine.core.model.BasicFutInfoConverter(gmkFuture))))
    }

    "initialize with Option session content" in {
      val rtsOption = OptInfo.SessContentsRecord(10881, 20023, 0, 3550, 160734, "RI175000BR2", "RTS-6.12M150612PA 175000", "Июньский Марж.Амер.Put.175000 Фьюч.контр RTS-6.12", 115)

      val p2 = mock(classOf[P2Connection])
      val capture = TestFSMRef(new MarketContentsCapture(p2, scheme), "MarketContentsCapture")
      val underlying = capture.underlyingActor.asInstanceOf[MarketContentsCapture]

      capture ! SubscribeMarketContents(self)
      capture ! Snapshot(underlying.optSessContentsRepository, rtsOption :: Nil)

      expectMsg(OptionsContents(Map(rtsOption.isinId -> com.ergodicity.engine.core.model.BasicOptInfoConverter(rtsOption))))
    }
  }
}