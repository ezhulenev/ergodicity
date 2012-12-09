package com.ergodicity.engine.service

import akka.actor.FSM.Transition
import akka.actor._
import akka.event.Logging
import akka.testkit.TestActor.AutoPilot
import akka.testkit._
import com.ergodicity.cgate.{ListenerBinding, DataStreamState}
import com.ergodicity.core.order.OrdersSnapshotActor.{OrdersSnapshot, GetOrdersSnapshot}
import com.ergodicity.core.session.SessionActor.AssignedContents
import com.ergodicity.engine.Services
import com.ergodicity.engine.service.Service.{Stop, Start}
import org.joda.time.DateTime
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import ru.micexrts.cgate.{Listener => CGListener}

class OrdersDataServiceSpec  extends TestKit(ActorSystem("OrdersDataServiceSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  implicit val Id = OrdersData.OrdersData

  def createService(implicit services: Services = mock(classOf[Services]), futuresSnapshot: ActorRef = system.deadLetters, optionsSnapshot:ActorRef = system.deadLetters) = {
    val futOrderBookListener = ListenerBinding(_ => mock(classOf[CGListener]))
    val optOrderBookListener = ListenerBinding(_ => mock(classOf[CGListener]))
    val ordLogListener = ListenerBinding(_ => mock(classOf[CGListener]))

    Mockito.when(services.apply(InstrumentData.InstrumentData)).thenReturn(system.deadLetters)

    TestFSMRef(new OrdersDataService(futOrderBookListener, optOrderBookListener, ordLogListener) {
      override val FuturesSnapshot = futuresSnapshot
      override val OptionsSnapshot = optionsSnapshot
    }, "OrdersData")
  }

  "OrdersData Service" must {
    "initialized in Idle state" in {
      val service = createService()
      assert(service.stateName == OrdersDataState.Idle)
    }

    "start service" in {
      val services = mock(classOf[Services])
      val futuresSnapshot = TestProbe()
      val optionsSnapshot = TestProbe()

      val emptyOrdersSnapshot = new AutoPilot {
        def run(sender: ActorRef, msg: Any) = msg match {
          case GetOrdersSnapshot =>
            sender ! OrdersSnapshot(100, new DateTime, Nil)
            None
        }
      }
      futuresSnapshot.setAutoPilot(emptyOrdersSnapshot)
      optionsSnapshot.setAutoPilot(emptyOrdersSnapshot)

      val service = createService(services, futuresSnapshot.ref, optionsSnapshot.ref)
      val underlying = service.underlyingActor.asInstanceOf[OrdersDataService]

      when("receive start message")
      service ! Start
      then("should wait for ongoing session & assigned contents")
      assert(service.stateName == OrdersDataState.AssigningInstruments)

      when("got assigned contents")
      service ! AssignedContents(Set())
      then("go to WaitingSnapshots state")
      and("ask for orders snapshots")
      futuresSnapshot.expectMsg(GetOrdersSnapshot)
      optionsSnapshot.expectMsg(GetOrdersSnapshot)

      Thread.sleep(300)

      when("receive snapshots")
      then("should go to StartingOrderBooks state")
      assert(service.stateName == OrdersDataState.StartingOrderBooks)

      then("OrdLog stream goes online")
      service ! Transition(underlying.OrdLogStream, DataStreamState.Opened, DataStreamState.Online)

      then("service shoud be started")
      assert(service.stateName == OrdersDataState.Started)
      and("Service Manager should be notified")
      verify(services).serviceStarted(Id)
    }

    "stop service" in {
      val services = mock(classOf[Services])
      val service = createService(services)
      service.setState(OrdersDataState.Started)
      val underlying = service.underlyingActor.asInstanceOf[OrdersDataService]

      when("receive Stop message")
      service ! Stop
      then("should go to Stopping state")
      assert(service.stateName == OrdersDataState.Stopping)

      when("OrdLog stream closed")
      service ! Transition(underlying.OrdLogStream, DataStreamState.Online, DataStreamState.Closed)

      then("service shoud be stopped")
      watch(service)
      expectMsg(Terminated(service))

      and("service Manager should be notified")
      verify(services).serviceStopped(Id)
    }
  }
}
