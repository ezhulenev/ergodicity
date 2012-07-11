package com.ergodicity.core.broker

import org.mockito.Mockito._
import org.mockito.Matchers._
import java.util.concurrent.Executor
import com.jacob.com.Variant
import akka.pattern._
import plaza2.{ServiceRef, Message, MessageFactory, Connection => P2Connection}
import com.ergodicity.core.common.{Isin, FutureContract}
import com.ergodicity.core.common.OrderType._
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import com.ergodicity.core.AkkaConfigurations
import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import akka.util.Timeout
import akka.util.duration._
import akka.dispatch.{Await, ExecutionContext}

class BrokerSpec extends TestKit(ActorSystem("BrokerSpec", AkkaConfigurations.ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  import BrokerCommand._

  implicit val timeout = Timeout(5 seconds)

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

      val broker = TestActorRef(new Broker("000", connection))

      // -- Execute
      val future = FutureContract(Isin(111, "isin", "shortIsin"), "name")

      val report = Await.result((broker ? Buy(future, GoodTillCancelled, BigDecimal(100), 1)).mapTo[ExecutionReport[FutOrder]], 100 millis)
      log.info("Res = " + report)

      assert(report match {
        case ExecutionReport(Left(_)) => true
        case _ => false
      })

      // -- Verify
      verify(messageFactory).createMessage("FutAddOrder")
    }
  }
}