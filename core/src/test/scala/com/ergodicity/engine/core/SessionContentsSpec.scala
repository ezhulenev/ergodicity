package com.ergodicity.engine.core

import akka.actor.ActorSystem
import model.{InstrumentState, Future}
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import com.ergodicity.engine.plaza2.Repository.Snapshot
import com.ergodicity.engine.plaza2.scheme.FutInfo.SessContentsRecord
import AkkaConfigurations._
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}

class SessionContentsSpec extends TestKit(ActorSystem("SessionContentsSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  val gmkFuture = SessContentsRecord(7477, 47740, 0, 4023, 166911, "GMM2", "GMKR-6.12", "Фьючерсный контракт GMKR-06.12", 115, 2, 0)

  val SessionContentToFuture = (record: SessContentsRecord) => new Future(record.isin, record.shortIsin, record.isinId, record.name)

  "Session Contents" must {
    "should track record updates" in {
      val contents = TestActorRef(new SessionContents(SessionContentToFuture), "Futures")
      contents ! Snapshot(self, gmkFuture :: Nil)

      val instrument = system.actorFor("user/Futures/GMKR-6.12")
      instrument ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(instrument, InstrumentState.SuspendedAll))

      contents ! Snapshot(self, gmkFuture.copy(state = 1) :: Nil)
      expectMsg(Transition(instrument, InstrumentState.SuspendedAll, InstrumentState.Online))
    }
  }

}
