package com.ergodicity.core.broker

import org.mockito.Mockito._
import org.mockito.Matchers._
import java.util.concurrent.Executor
import com.jacob.com.Variant
import akka.dispatch.ExecutionContext
import plaza2.{ServiceRef, Message, MessageFactory, Connection => P2Connection}
import com.ergodicity.core.common.{GoodTillCancelled, Isin, FutureContract}
import akka.testkit.{ImplicitSender, TestKit}
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import com.ergodicity.core.AkkaConfigurations
import akka.actor.ActorSystem

class BrokerSpec extends TestKit(ActorSystem("BrokerSpec", AkkaConfigurations.ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  implicit val ec = ExecutionContext.fromExecutor(new Executor {
    def execute(command: Runnable) {
      command.run()
    }
  })

  "Broker" must {
    "support buy security" in {

      implicit val messageFactory = mock(classOf[MessageFactory])
      val connection = mock(classOf[P2Connection])
      val message = mock(classOf[Message])

      // -- Init mocks
      when(connection.resolveService(any())).thenReturn(ServiceRef("ebaka"))

      when(messageFactory.createMessage("FutAddOrder")).thenReturn(message)

      val response = mock(classOf[Message])
      when(response.field("P2_Category")).thenReturn(new Variant(Broker.FORTS_MSG));
      when(response.field("P2_Type")).thenReturn(new Variant(101));
      when(response.field("code")).thenReturn(new Variant(100))
      when(response.field("message")).thenReturn(new Variant("error"))

      when(message.send(any(), any())).thenReturn(response)

      val broker = new Broker("000", connection)

      // -- Execute
      val future = FutureContract(Isin(111, "isin", "shortIsin"), "name")

      val r = broker.buy(future, GoodTillCancelled, BigDecimal(100), 1)
      log.info("Res = " + r)

      assert(r match {
        case Left(_) => true
        case _ => false
      })

      // -- Verify
      verify(messageFactory).createMessage("FutAddOrder")
    }
  }
}