package com.ergodicity.core.broker

import org.joda.time.DateTime
import org.scala_tools.time.Implicits._
import akka.event.Logging
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import com.ergodicity.core.AkkaConfigurations._
import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import org.mockito.Mockito._
import com.ergodicity.core.common.FutureContract
import plaza2.{Message, MessageFactory, Connection => P2Connection}

class BrokerSpec extends TestKit(ActorSystem("BrokerSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  val primaryInterval = new DateTime to new DateTime
  val positionTransferInterval = new DateTime to new DateTime

  override def afterAll() {
    system.shutdown()
  }

  "Broker" must {
    "support FutAddOrder" in {
      val connection = mock(classOf[P2Connection])
      implicit val messageFactory = mock(classOf[MessageFactory])
      val message = mock(classOf[Message])
      when(messageFactory.createMessage("FutAddOrder")).thenReturn(message)

      val broker = TestActorRef(new Broker("000", connection), "Broker")

      val future = FutureContract("isin", "shortIsin", 111, "name")
      broker ! AddOrder(future, GoodTillCancelled, Buy, BigDecimal(100), 1)

      verify(messageFactory).createMessage("FutAddOrder")
    }
  }
}