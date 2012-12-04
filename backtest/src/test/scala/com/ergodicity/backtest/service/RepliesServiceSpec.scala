package com.ergodicity.backtest.service

import akka.actor.ActorSystem
import akka.event.Logging
import akka.testkit._
import akka.util.Timeout
import akka.util.duration._
import com.ergodicity.backtest.Mocking
import com.ergodicity.backtest.cgate.ReplyStreamListenerStubActor.DispatchReply
import com.ergodicity.backtest.cgate.ReplyStreamListenerStubActor.DispatchTimeout
import com.ergodicity.core.Market.{Options, Futures}
import com.ergodicity.core._
import com.ergodicity.core.broker._
import com.ergodicity.core.session.InstrumentState
import com.ergodicity.schema.{OptSessContents, FutSessContents, Session}
import org.joda.time.DateTime
import org.scalatest.{BeforeAndAfterAll, WordSpec}

class RepliesServiceSpec extends TestKit(ActorSystem("RepliesServiceSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val sessionId = SessionId(100, 100)

  val begin = new DateTime(2012, 1, 1, 10, 0)
  val end = begin.withHourOfDay(20)

  val futureContract = FutureContract(IsinId(100), Isin("FISIN"), ShortIsin("FISINS"), "Future")

  val session = Session(Mocking.mockSession(sessionId.fut, sessionId.opt, begin, end))
  val futures = FutSessContents(Mocking.mockFuture(sessionId.fut, futureContract.id.id, futureContract.isin.isin, futureContract.shortIsin.shortIsin, futureContract.name, 115, InstrumentState.Assigned.toInt)) :: Nil
  val options = OptSessContents(Mocking.mockOption(sessionId.fut, 101, "OISIN", "OSISIN", "Option", 115)) :: Nil

  implicit val sessionContext = SessionContext(session, futures, options)

  implicit val timeout = Timeout(1.second)

  "Replies Service" must {
    "decode/encode futures OrderId" in {
      val repliesService = new RepliesService[Futures](self)
      repliesService.reply(100, OrderId(111l))
      val replyData = receiveOne(100.millis).asInstanceOf[DispatchReply].reply

      val protocol = broker.Protocol.FutAddOrder

      assert(replyData.id == 100)
      val reply = protocol.response(replyData.messageId, replyData.data)
      assert(reply == OrderId(111l))
    }

    "decode/encode options OrderId" in {
      val repliesService = new RepliesService[Options](self)
      repliesService.reply(100, OrderId(111l))
      val replyData = receiveOne(100.millis).asInstanceOf[DispatchReply].reply

      val protocol = broker.Protocol.OptAddOrder

      assert(replyData.id == 100)
      val reply = protocol.response(replyData.messageId, replyData.data)
      assert(reply == OrderId(111l))
    }

    "decode/encode futures Cancelled" in {
      val repliesService = new RepliesService[Futures](self)
      repliesService.reply(100, Cancelled(10))
      val replyData = receiveOne(100.millis).asInstanceOf[DispatchReply].reply

      val protocol = broker.Protocol.FutDelOrder

      assert(replyData.id == 100)
      val reply = protocol.response(replyData.messageId, replyData.data)
      assert(reply == Cancelled(10))
    }

    "decode/encode options Cancelled" in {
      val repliesService = new RepliesService[Options](self)
      repliesService.reply(100, Cancelled(10))
      val replyData = receiveOne(100.millis).asInstanceOf[DispatchReply].reply

      val protocol = broker.Protocol.OptDelOrder

      assert(replyData.id == 100)
      val reply = protocol.response(replyData.messageId, replyData.data)
      assert(reply == Cancelled(10))
    }

    "decode/encode BrokerTimeoutException" in {
      val repliesService = new RepliesService[Futures](self)
      repliesService.fail[OrderId](100, BrokerTimedOutException)
      expectMsg(DispatchTimeout(ReplyEvent.TimeoutMessage(100)))
    }

    "decode/encode BrokerErrorException" in {
      val repliesService = new RepliesService[Futures](self)
      repliesService.fail[OrderId](100, BrokerErrorException("Failed"))
      val replyData = receiveOne(100.millis).asInstanceOf[DispatchReply].reply

      val protocol = broker.Protocol.FutAddOrder

      assert(replyData.id == 100)
      intercept[BrokerErrorException] {
        protocol.response(replyData.messageId, replyData.data)
      }
    }

    "decode/encode FloodException" in {
      val repliesService = new RepliesService[Futures](self)
      repliesService.fail[OrderId](100, FloodException(1, 1, "Failed"))
      val replyData = receiveOne(100.millis).asInstanceOf[DispatchReply].reply

      val protocol = broker.Protocol.FutAddOrder

      assert(replyData.id == 100)
      intercept[FloodException] {
        protocol.response(replyData.messageId, replyData.data)
      }
    }


    "decode/encode futures ActionFailedException/AddOrder" in {
      val repliesService = new RepliesService[Futures](self)
      repliesService.fail[OrderId](100, ActionFailedException(123, "Failed"))
      val replyData = receiveOne(100.millis).asInstanceOf[DispatchReply].reply

      val protocol = broker.Protocol.FutAddOrder

      assert(replyData.id == 100)
      intercept[ActionFailedException] {
        protocol.response(replyData.messageId, replyData.data)
      }
    }

    "decode/encode options ActionFailedException/AddOrder" in {
      val repliesService = new RepliesService[Options](self)
      repliesService.fail[OrderId](100, ActionFailedException(123, "Failed"))
      val replyData = receiveOne(100.millis).asInstanceOf[DispatchReply].reply

      val protocol = broker.Protocol.OptAddOrder

      assert(replyData.id == 100)
      intercept[ActionFailedException] {
        protocol.response(replyData.messageId, replyData.data)
      }
    }

    "decode/encode futures ActionFailedException/Cancel" in {
      val repliesService = new RepliesService[Futures](self)
      repliesService.fail[Cancelled](100, ActionFailedException(123, "Failed"))
      val replyData = receiveOne(100.millis).asInstanceOf[DispatchReply].reply

      val protocol = broker.Protocol.FutDelOrder

      assert(replyData.id == 100)
      intercept[ActionFailedException] {
        protocol.response(replyData.messageId, replyData.data)
      }
    }

    "decode/encode options ActionFailedException/Cancel" in {
      val repliesService = new RepliesService[Options](self)
      repliesService.fail[Cancelled](100, ActionFailedException(123, "Failed"))
      val replyData = receiveOne(100.millis).asInstanceOf[DispatchReply].reply

      val protocol = broker.Protocol.OptDelOrder

      assert(replyData.id == 100)
      intercept[ActionFailedException] {
        protocol.response(replyData.messageId, replyData.data)
      }
    }
  }
}