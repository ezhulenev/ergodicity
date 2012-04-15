package com.ergodicity.engine.core.model

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.actor.ActorSystem
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import InstrumentState._
import com.ergodicity.engine.core.AkkaConfigurations
import AkkaConfigurations._

class InstrumentSpec extends TestKit(ActorSystem("InstrumentSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  "Instrument" must {
    "support state inialization" in {
      val future = Future("GMKR-6.12", "GMM2", 166911, "Фьючерсный контракт GMKR-06.12")
      val instrument = TestFSMRef(new Instrument[Future](future, Assigned))
      assert(instrument.stateName == Assigned)
    }

    "support state updates" in {
      val future = Future("GMKR-6.12", "GMM2", 166911, "Фьючерсный контракт GMKR-06.12")
      val instrument = TestFSMRef(new Instrument[Future](future, Assigned))

      instrument ! Online
      assert(instrument.stateName == Online)
    }
  }

}
