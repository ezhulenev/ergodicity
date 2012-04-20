package com.ergodicity.engine.capture

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import org.slf4j.LoggerFactory
import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import com.ergodicity.engine.plaza2.DataStream.DataDeleted

class DealsCaptureSpec extends TestKit(ActorSystem("DealsCaptureSpec")) with WordSpec with BeforeAndAfterAll with ImplicitSender {
  val log = LoggerFactory.getLogger(classOf[DealsCaptureSpec])

  override def afterAll() {
    system.shutdown()
  }

  "OrdersCapture" must {

    "fail on DataDeleted event" in {
      val ordersCapture = TestActorRef(new DealsCapture)
      intercept[DealsCaptureException] {
        ordersCapture.receive(DataDeleted("table", 1))
      }
    }

  }

}