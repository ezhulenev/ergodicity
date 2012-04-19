package com.ergodicity.engine.capture

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import org.slf4j.LoggerFactory
import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import com.ergodicity.engine.plaza2.DataStream.{DataDeleted, DatumDeleted}

class OrdersCaptureSpec  extends TestKit(ActorSystem("OrdersCaptureSpec")) with WordSpec with BeforeAndAfterAll with ImplicitSender {
  val log = LoggerFactory.getLogger(classOf[MarketCaptureSpec])

  override def afterAll() {
    system.shutdown()
  }

  "OrdersCapture" must {

    "fail on DatumDeleted event" in {
      val ordersCapture = TestActorRef(new OrdersCapture)
      intercept[OrdersCaptureException] {
        ordersCapture.receive(DatumDeleted(1))
      }
    }

    "fail on DataDeleted event" in {
      val ordersCapture = TestActorRef(new OrdersCapture)
      intercept[OrdersCaptureException] {
        ordersCapture.receive(DataDeleted(1))
      }
    }

  }

}