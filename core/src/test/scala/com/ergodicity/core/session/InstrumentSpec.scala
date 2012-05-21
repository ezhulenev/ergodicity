package com.ergodicity.core.session

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.actor.ActorSystem
import InstrumentState._
import com.ergodicity.core.AkkaConfigurations
import AkkaConfigurations._
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import com.ergodicity.core.common.FutureContract

class InstrumentSpec extends TestKit(ActorSystem("InstrumentSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  "Instrument" must {
    "support state inialization" in {
      val future = FutureContract("GMKR-6.12", "GMM2", 166911, "Фьючерсный контракт GMKR-06.12")
      val instrument = TestFSMRef(new Instrument[FutureContract](future, Assigned))
      assert(instrument.stateName == Assigned)
    }

    "support state updates" in {
      val future = FutureContract("GMKR-6.12", "GMM2", 166911, "Фьючерсный контракт GMKR-06.12")
      val instrument = TestFSMRef(new Instrument[FutureContract](future, Assigned))

      instrument ! Online
      assert(instrument.stateName == Online)
    }
  }

}
