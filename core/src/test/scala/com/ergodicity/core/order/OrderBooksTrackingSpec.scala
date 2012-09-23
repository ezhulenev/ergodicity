package com.ergodicity.core.order

import akka.actor.ActorSystem
import akka.event.Logging
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import akka.util.duration._
import com.ergodicity.cgate.DataStream.SubscribeStreamEvents
import com.ergodicity.core.AkkaConfigurations._
import com.ergodicity.core._
import com.ergodicity.core.order.OrderBooksData.RevisionConstraints
import com.ergodicity.core.order.OrderBooksTracking.{StickyAction, OrderLog, Snapshots}
import com.ergodicity.core.order.OrdersSnapshotActor.OrdersSnapshot
import com.ergodicity.core.session.SessionActor.AssignedContents
import org.joda.time.DateTime
import org.mockito.Mockito._
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack}
import akka.util.Timeout

class OrderBooksTrackingSpec extends TestKit(ActorSystem("OrdersTrackingSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  implicit val timeout = Timeout(1.second)

  val sessionId = 100

  val isinId1: IsinId = IsinId(1)
  val isin1 = Isin("RTS-9.12")
  val shortIsin1 = ShortIsin("")
  val futureContract = FutureContract(isinId1, isin1, shortIsin1, "Future contract")

  val isinId2: IsinId = IsinId(2)
  val isin2 = Isin("RTS-9.12 150000")
  val shortIsin2 = ShortIsin("")
  val optionContract = OptionContract(isinId2, isin2, shortIsin2, "Option contract")

  val assignedContents = AssignedContents(Set(futureContract, optionContract))

  val moment = new DateTime()

  "OrderBooksTracking" must {
    "wait for Orders snapshots after start up" in {
      val tracking = TestFSMRef(new OrderBooksTracking(self), "OrderBooksTracking")
      val underlying = tracking.underlyingActor.asInstanceOf[OrderBooksTracking]
      expectMsg(SubscribeStreamEvents(underlying.dispatcher))
      assert(tracking.stateName == OrderBooksState.WaitingSnapshots)
    }

    "consume orders snapshots" in {
      val tracking = TestFSMRef(new OrderBooksTracking(self), "OrderBooksTracking")
      val underlying = tracking.underlyingActor.asInstanceOf[OrderBooksTracking]
      expectMsg(SubscribeStreamEvents(tracking.underlyingActor.asInstanceOf[OrderBooksTracking].dispatcher))

      val futuresSnapshot = OrdersSnapshot(100, moment, (Order(1, sessionId, isinId1, OrderDirection.Buy, 100, 1, 1), Seq()) :: Nil)
      val optionsSnapshot = OrdersSnapshot(110, moment, Nil)

      tracking ! assignedContents
      tracking ! Snapshots(futuresSnapshot, optionsSnapshot)

      assert(underlying.sessions.size == 1)
      assert(tracking.stateName == OrderBooksState.Synchronizing)

      tracking.stop()

      Thread.sleep(100)
    }

    "consume orders snapshots with Fill action" in {
      val tracking = TestFSMRef(new OrderBooksTracking(self), "OrderBooksTracking")
      val underlying = tracking.underlyingActor.asInstanceOf[OrderBooksTracking]
      expectMsg(SubscribeStreamEvents(tracking.underlyingActor.asInstanceOf[OrderBooksTracking].dispatcher))

      val futuresSnapshot = OrdersSnapshot(100, moment, (Order(1, sessionId, isinId1, OrderDirection.Buy, 100, 1, 1), Seq(Fill(1, 0, None))) :: Nil)
      val optionsSnapshot = OrdersSnapshot(110, moment, Nil)

      tracking ! assignedContents
      tracking ! Snapshots(futuresSnapshot, optionsSnapshot)

      assert(underlying.sessions.size == 1)
      assert(tracking.stateName == OrderBooksState.Synchronizing)

      Thread.sleep(100)

      val orderActor = system.actorFor(underlying.sessions(sessionId).path + "/" + isin1.toActorName+"/1")
      orderActor ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(orderActor, OrderState.Filled))
    }

    "discard orders with smaller revision" in {
      val tracking = TestFSMRef(new OrderBooksTracking(self), "OrderBooksTracking")
      val underlying = tracking.underlyingActor.asInstanceOf[OrderBooksTracking]
      expectMsg(SubscribeStreamEvents(tracking.underlyingActor.asInstanceOf[OrderBooksTracking].dispatcher))

      tracking ! assignedContents
      tracking.setState(OrderBooksState.Synchronizing, RevisionConstraints(100, 200))
      underlying.sessions(sessionId) = self

      val expectedAction = mock(classOf[OrderAction])

      tracking ! OrderLog(90, sessionId, isinId1, mock(classOf[OrderAction]))
      expectNoMsg(100.millis)
      tracking ! OrderLog(100, sessionId, isinId1, mock(classOf[OrderAction]))
      expectNoMsg(100.millis)

      when("get message with bigger revision")
      tracking ! OrderLog(101, sessionId, isinId1, expectedAction)
      then("should forward it to session order books")
      expectMsg(StickyAction(futureContract, expectedAction))
      tracking ! OrderLog(190, sessionId, isinId2, mock(classOf[OrderAction]))
      expectNoMsg(100.millis)
      tracking ! OrderLog(200, sessionId, isinId2, mock(classOf[OrderAction]))
      expectNoMsg(100.millis)
      when("get message with bigger revision")
      tracking ! OrderLog(201, sessionId, isinId2, expectedAction)
      then("should forward it to session order books")
      expectMsg(StickyAction(optionContract, expectedAction))
    }

    "go to online state where receive message with revision greater then max of snapshots" in {
      val tracking = TestFSMRef(new OrderBooksTracking(self), "OrderBooksTracking")
      val underlying = tracking.underlyingActor.asInstanceOf[OrderBooksTracking]
      expectMsg(SubscribeStreamEvents(tracking.underlyingActor.asInstanceOf[OrderBooksTracking].dispatcher))

      tracking ! assignedContents
      tracking.setState(OrderBooksState.Synchronizing, RevisionConstraints(100, 200))
      underlying.sessions(sessionId) = self

      val expectedAction = mock(classOf[OrderAction])

      when("get message with big revision")
      tracking ! OrderLog(1000, sessionId, isinId2, expectedAction)
      then("should forward it to session order books")
      expectMsg(StickyAction(optionContract, expectedAction))
      and("go to Online state")
      assert(tracking.stateName == OrderBooksState.Online)
    }
  }
}