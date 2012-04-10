package com.ergodicity.engine.plaza2

import org.scalatest.WordSpec
import org.slf4j.LoggerFactory
import org.mockito.Mockito._
import org.mockito.Matchers._
import org.hamcrest.{Description, BaseMatcher}
import plaza2.{DataStream => P2DataStream, Connection => _, _}
import akka.testkit.{TestFSMRef, TestKit}
import com.ergodicity.engine.plaza2.DataStreamState._
import com.ergodicity.engine.plaza2.DataStream.Open
import akka.actor.{FSM, Terminated, ActorSystem}
import com.jacob.com.ComFailException

class DataStreamSpec extends TestKit(ActorSystem()) with WordSpec {
  val log = LoggerFactory.getLogger(classOf[ConnectionSpec])

  type EventHandler = StreamEvent => Any

  val ReleaseNothing = new SafeRelease {
    def apply() {}
  }

  "DataStream" must {
    "be initialized in Idle state" in {
      val p2 = mock(classOf[P2DataStream])
      val dataStream = TestFSMRef(DataStream(p2), "DataStream")
      log.info("State: " + dataStream.stateName)
      assert(dataStream.stateName == Idle)
    }

    "propagate exception on open" in {
      val conn = mock(classOf[Connection])
      val stream = mock(classOf[P2DataStream])
      val err = mock(classOf[ComFailException])
      when(stream.open(any())).thenThrow(err)

      val dataStream = TestFSMRef(DataStream(stream), "DataStream")
      intercept[ComFailException] {
        dataStream.receive(Open(conn))
      }
    }

    "go to Opening status" in {
      val conn = mock(classOf[Connection])
      val stream = mock(classOf[P2DataStream])

      val dataStream = TestFSMRef(DataStream(stream), "DataStream")

      dataStream ! Open(conn)

      assert(dataStream.stateName == Opening)
    }

    "terminate after P2Stream goes to Error state" in {
      val conn = mock(classOf[Connection])
      val stream = mock(classOf[P2DataStream])

      val dataStream = TestFSMRef(DataStream(stream), "DataStream")
      val streamEvents = captureEventDispatcher(stream)

      watch(dataStream)
      dataStream ! Open(conn)
      assert(dataStream.stateName == Opening)

      streamEvents ! StreamStateChanged(StreamState.Error)
      expectMsg(Terminated(dataStream))
    }

    "terminate after P2Stream goes to Close state" in {
      val conn = mock(classOf[Connection])
      val stream = mock(classOf[P2DataStream])

      val dataStream = TestFSMRef(DataStream(stream), "DataStream")
      val streamEvents = captureEventDispatcher(stream)

      watch(dataStream)
      dataStream ! Open(conn)
      assert(dataStream.stateName == Opening)

      streamEvents ! StreamStateChanged(StreamState.Close)
      expectMsg(Terminated(dataStream))
    }

    "terminate after P2Stream goes to CloseComplete state" in {
      val conn = mock(classOf[Connection])
      val stream = mock(classOf[P2DataStream])

      val dataStream = TestFSMRef(DataStream(stream), "DataStream")
      val streamEvents = captureEventDispatcher(stream)

      watch(dataStream)
      dataStream ! Open(conn)
      streamEvents ! StreamStateChanged(StreamState.CloseComplete)
      expectMsg(Terminated(dataStream))
    }

    "terminate on Opening time out" in {
      val conn = mock(classOf[Connection])
      val stream = mock(classOf[P2DataStream])

      val dataStream = TestFSMRef(DataStream(stream), "DataStream")
      captureEventDispatcher(stream)

      watch(dataStream)
      dataStream ! Open(conn)
      assert(dataStream.stateName == Opening)

      dataStream ! FSM.StateTimeout
      expectMsg(Terminated(dataStream))
    }

    "terminate if not ini for life number updates" in {
      val conn = mock(classOf[Connection])
      val stream = mock(classOf[P2DataStream])

      val dataStream = TestFSMRef(DataStream(stream), "DataStream")
      val streamEvents = captureEventDispatcher(stream)

      watch(dataStream)
      dataStream ! Open(conn)
      streamEvents ! StreamStateChanged(StreamState.LocalSnapshot)
      streamEvents ! StreamStateChanged(StreamState.Reopen)
      assert(dataStream.stateName == Reopen)

      streamEvents ! StreamLifeNumChanged(1000)
      expectMsg(Terminated(dataStream))
    }

    "go throught Idle -> Opening -> Synchronizing -> Reopen -> Synchronizing -> Online -> Reopen -> Stop" in {
      val conn = mock(classOf[Connection])
      val stream = mock(classOf[P2DataStream])

      val dataStream = TestFSMRef(DataStream(stream), "DataStream")
      val streamEvents = captureEventDispatcher(stream)

      watch(dataStream)

      assert(dataStream.stateName == Idle)

      dataStream ! Open(conn)
      assert(dataStream.stateName == Opening)

      streamEvents ! StreamStateChanged(StreamState.LocalSnapshot)
      assert(dataStream.stateName == Synchronizing)

      streamEvents ! StreamStateChanged(StreamState.Reopen)
      assert(dataStream.stateName == Reopen)

      streamEvents ! StreamStateChanged(StreamState.RemoteSnapshot)
      assert(dataStream.stateName == Synchronizing)

      streamEvents ! StreamStateChanged(StreamState.Online)
      assert(dataStream.stateName == Online)

      streamEvents ! StreamStateChanged(StreamState.Reopen)
      assert(dataStream.stateName == Reopen)

      streamEvents ! StreamStateChanged(StreamState.Close)
      expectMsg(Terminated(dataStream))
    }
  }

  private def captureEventDispatcher(stream: P2DataStream) = {
    var f: EventHandler = s => ()

    when(stream.dispatchEvents(argThat(new BaseMatcher[EventHandler] {
      def describeTo(description: Description) {}

      def matches(item: AnyRef) = {
        f = item.asInstanceOf[EventHandler]
        true
      };
    }))).thenReturn(ReleaseNothing)

    new {
      def !(event: StreamEvent) {
        f(event)
      }
    }
  }

}