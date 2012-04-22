package com.ergodicity.engine.capture

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import org.slf4j.LoggerFactory
import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import com.ergodicity.engine.plaza2.DataStream.DataDeleted
import com.ergodicity.engine.plaza2.scheme.OrdLog

class DataStreamCaptureSpec  extends TestKit(ActorSystem("DataStreamCaptureSpec")) with WordSpec with BeforeAndAfterAll with ImplicitSender {
  val log = LoggerFactory.getLogger(classOf[DataStreamCaptureSpec])

  override def afterAll() {
    system.shutdown()
  }

  "OrdersCapture" must {

    "fail on DataDeleted event" in {
      val ordersCapture = TestActorRef(new DataStreamCapture[OrdLog.OrdersLogRecord](None)((a:OrdLog.OrdersLogRecord)=>()))
      intercept[DataStreamCaptureException] {
        ordersCapture.receive(DataDeleted("table", 1))
      }
    }

  }

}