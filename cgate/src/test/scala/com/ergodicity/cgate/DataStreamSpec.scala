package com.ergodicity.cgate

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.actor.ActorSystem
import akka.event.Logging
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}

class DataStreamSpec extends TestKit(ActorSystem("DataStreamSpec", AkkaConfigurations.ConfigWithDetailedLogging)) with WordSpec with BeforeAndAfterAll with ImplicitSender {
  val log = Logging(system, self)

  val Host = "host"
  val Port = 4001
  val AppName = "ConnectionSpec"

  override def afterAll() {
    system.shutdown()
  }

  "DataStream" must {
    "initialized in Closed state" in {
      val dataStream = TestFSMRef(new DataStream, "DataStream")
      assert(dataStream.stateName == DataStreamState.Closed)
    }

    "follow Closed -> Opened -> Online -> Closed states" in {
      val dataStream = TestFSMRef(new DataStream, "DataStream")
      assert(dataStream.stateName == DataStreamState.Closed)

      dataStream ! StreamEvent.Open
      assert(dataStream.stateName == DataStreamState.Opened)

      dataStream ! StreamEvent.GoOnline
      assert(dataStream.stateName == DataStreamState.Online)

      dataStream ! StreamEvent.Close
      assert(dataStream.stateName == DataStreamState.Closed)
    }
  }

}