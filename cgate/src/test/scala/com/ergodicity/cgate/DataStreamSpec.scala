package com.ergodicity.cgate

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import akka.actor.ActorSystem
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}
import akka.pattern.ask
import akka.util.duration._
import java.nio.ByteBuffer
import com.ergodicity.cgate.StreamEvent.{ReplState, ClearDeleted, LifeNumChanged, StreamData}
import akka.dispatch.Await
import akka.util.Timeout
import com.ergodicity.cgate.DataStream._

class DataStreamSpec extends TestKit(ActorSystem("DataStreamSpec", AkkaConfigurations.ConfigWithDetailedLogging)) with WordSpec with BeforeAndAfterAll with ImplicitSender {
  val log = Logging(system, self)

  implicit val timeout = Timeout(1.second)

  override def afterAll() {
    system.shutdown()
  }

  "DataStream" must {
    "initialized in Closed state" in {
      val dataStream = TestFSMRef(new DataStream, "DataStream")
      assert(dataStream.stateName == DataStreamState.Closed)
    }

    "bind tables" in {
      val dataStream = TestFSMRef(new DataStream, "DataStream")
      dataStream ? BindTable(0, self)
      dataStream ? BindTable(1, self)
      dataStream ? BindTable(1, self)

      assert(dataStream.stateData.size == 2)
      assert(dataStream.stateData(0).size == 1)
      assert(dataStream.stateData(1).size == 2)
    }

    "binding succeed in Closed state" in {
      val dataStream = TestFSMRef(new DataStream, "DataStream")
      val resp = (dataStream ? BindTable(0, self)).mapTo[BindingResult]
      val res = Await.result(resp, 1.second)

      assert(res == BindingSucceed(dataStream, 0))
    }

    "binding failed in Closed state" in {
      val dataStream = TestFSMRef(new DataStream, "DataStream")
      dataStream.setState(DataStreamState.Opened)
      val resp = (dataStream ? BindTable(0, self)).mapTo[BindingResult]
      val res = Await.result(resp, 1.second)

      assert(res == BindingFailed(dataStream, 0))
    }

    "follow Closed -> Opened -> Online -> Closed states" in {
      val dataStream = TestFSMRef(new DataStream, "DataStream")
      assert(dataStream.stateName == DataStreamState.Closed)

      dataStream ! StreamEvent.Open
      assert(dataStream.stateName == DataStreamState.Opened)

      dataStream ! StreamEvent.StreamOnline
      assert(dataStream.stateName == DataStreamState.Online)

      dataStream ! StreamEvent.Close
      assert(dataStream.stateName == DataStreamState.Closed)
    }

    "forward stream events" in {
      val dataStream = TestFSMRef(new DataStream, "DataStream")
      dataStream ? BindTable(0, self)

      dataStream ! StreamEvent.Open

      dataStream ! StreamEvent.TnBegin
      expectMsg(StreamEvent.TnBegin)

      dataStream ! StreamEvent.TnCommit
      expectMsg(StreamEvent.TnCommit)

      dataStream ! LifeNumChanged(100)
      expectMsg(LifeNumChanged(100))

      dataStream ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(dataStream, DataStreamState.Opened))

      dataStream ! StreamEvent.StreamOnline
      expectMsg(Transition(dataStream, DataStreamState.Opened, DataStreamState.Online))

      dataStream ! StreamEvent.Close
      expectMsg(Transition(dataStream, DataStreamState.Online, DataStreamState.Closed))
    }
    
    "forward stream data" in {
      val dataStream = TestFSMRef(new DataStream, "DataStream")
      dataStream ? BindTable(0, self)

      dataStream ! StreamEvent.Open
      
      val buffer1 = ByteBuffer.wrap(Array[Byte]())
      val buffer2 = ByteBuffer.wrap(Array[Byte]())
      dataStream ! StreamData(0, buffer1)
      dataStream ! StreamData(1, buffer2)

      expectMsg(StreamData(0, buffer1))

      dataStream ! ClearDeleted(0, 100)
      dataStream ! ClearDeleted(1, 101)

      expectMsg(ClearDeleted(0, 100))
    }

    "subscribe repl states" in {
      val dataStream = TestFSMRef(new DataStream, "DataStream")
      dataStream ! SubscribeReplState(self)

      dataStream ! StreamEvent.Open

      dataStream ! ReplState("ebaka")

      expectMsg(DataStreamReplState(dataStream, "ebaka"))
    }
  }
}