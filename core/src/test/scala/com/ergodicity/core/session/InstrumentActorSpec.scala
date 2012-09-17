package com.ergodicity.core.session

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.util.duration._
import akka.actor.ActorSystem
import InstrumentState._
import com.ergodicity.core._
import AkkaConfigurations._
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import com.ergodicity.core.FutureContract
import session.InstrumentParameters.{Limits, FutureParameters}
import session.InstrumentActor.SubscribeInstrumentCallback

class InstrumentActorSpec extends TestKit(ActorSystem("InstrumentActorSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  "InstrumentActor" must {
    "initialized in Suspended state" in {
      val future = FutureContract(IsinId(166911), Isin("GMKR-6.12"), ShortIsin("GMM2"), "Фьючерсный контракт GMKR-06.12")
      val instrument = TestFSMRef(new FutureInstrument(future))
      assert(instrument.stateName == Suspended)
    }

    "support state updates" in {
      val future = FutureContract(IsinId(166911), Isin("GMKR-6.12"), ShortIsin("GMM2"), "Фьючерсный контракт GMKR-06.12")
      val instrument = TestFSMRef(new FutureInstrument(future))

      instrument ! Online
      assert(instrument.stateName == Online)
    }

    "don't respond with paraeters of they are not available" in {
      val future = FutureContract(IsinId(166911), Isin("GMKR-6.12"), ShortIsin("GMM2"), "Фьючерсный контракт GMKR-06.12")
      val instrument = TestFSMRef(new FutureInstrument(future))
      instrument ! SubscribeInstrumentCallback(self)
      expectNoMsg(500.millis)
    }

    "handle paramters subscripbtion" in {
      val future = FutureContract(IsinId(166911), Isin("GMKR-6.12"), ShortIsin("GMM2"), "Фьючерсный контракт GMKR-06.12")
      val instrument = TestFSMRef(new FutureInstrument(future))
      instrument ! SubscribeInstrumentCallback(self)
      instrument ! FutureParameters(100, Limits(100, 100))
      expectMsg(Instrument(instrument, future, FutureParameters(100, Limits(100, 100))))
    }
  }

}
