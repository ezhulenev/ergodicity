package com.ergodicity.capture

import org.scalatest.{WordSpec, BeforeAndAfterAll}
import akka.testkit.{TestProbe, TestFSMRef, ImplicitSender, TestKit}
import akka.actor.{Terminated, ActorSystem}
import ru.micexrts.cgate.{Connection => CGConnection}
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import org.slf4j.{Logger, LoggerFactory}
import com.mongodb.casbah.TypeImports._
import com.ergodicity.capture.ReopenReplicationStreams.StreamRef
import org.mockito.Mockito._
import com.ergodicity.cgate.Listener
import com.ergodicity.cgate.DataStream.DataStreamReplState
import com.ergodicity.cgate.StreamEvent.ReplState
import com.ergodicity.cgate.config.Replication.ReplicationMode.Combined
import com.ergodicity.cgate.config.Replication.ReplicationParams

class ReopenReplicationStreamsSpec extends TestKit(ActorSystem("ReopenReplicationStreamsSpec", AkkaConfigurations.ConfigWithDetailedLogging)) with WordSpec with BeforeAndAfterAll with ImplicitSender {
  val log = LoggerFactory.getLogger(classOf[ReopenReplicationStreamsSpec])

  trait Repo extends ReplicationStateRepository with SessionRepository with FutSessionContentsRepository with OptSessionContentsRepository {
    val log: Logger = null

    val mongo: MongoDB = null
  }

  override def afterAll() {
    system.shutdown()
  }

  "ReopenReplicationStates" must {
    "stop all listeners after startup" in {
      val futTradeStream = TestProbe()
      val futTradeListener = TestProbe()
      val futTrade = StreamRef("FutTrade", futTradeStream.ref, futTradeListener.ref)

      val optTradeStream = TestProbe()
      val optTradeListener = TestProbe()
      val optTrade = StreamRef("OptTrade", optTradeStream.ref, optTradeListener.ref)

      val ordLogStream = TestProbe()
      val ordLogListener = TestProbe()
      val ordLog = StreamRef("OrdLog", ordLogStream.ref, ordLogListener.ref)

      val repository = mock(classOf[Repo])

      val reopen = TestFSMRef(new ReopenReplicationStreams(repository, futTrade, optTrade, ordLog))
      assert(reopen.stateName == ReopenReplicationStreams.ShuttingDown)

      futTradeListener.expectMsg(Listener.Close)
      optTradeListener.expectMsg(Listener.Close)
      ordLogListener.expectMsg(Listener.Close)
    }

    "save replication state, restart streams and stop" in {
      val futTradeStream = TestProbe()
      val futTradeListener = TestProbe()
      val futTrade = StreamRef("FutTrade", futTradeStream.ref, futTradeListener.ref)

      val optTradeStream = TestProbe()
      val optTradeListener = TestProbe()
      val optTrade = StreamRef("OptTrade", optTradeStream.ref, optTradeListener.ref)

      val ordLogStream = TestProbe()
      val ordLogListener = TestProbe()
      val ordLog = StreamRef("OrdLog", ordLogStream.ref, ordLogListener.ref)

      val repository = mock(classOf[Repo])

      val reopen = TestFSMRef(new ReopenReplicationStreams(repository, futTrade, optTrade, ordLog))

      futTradeListener.expectMsg(Listener.Close)
      optTradeListener.expectMsg(Listener.Close)
      ordLogListener.expectMsg(Listener.Close)

      watch(reopen)

      reopen ! DataStreamReplState(futTradeStream.ref, "FutTradeState")
      reopen ! DataStreamReplState(optTradeStream.ref, "OptTradeState")
      reopen ! DataStreamReplState(ordLogStream.ref, "OrdLogState")

      // Verify states saved
      verify(repository).setReplicationState("FutTrade", "FutTradeState")
      verify(repository).setReplicationState("OptTrade", "OptTradeState")
      verify(repository).setReplicationState("OrdLog", "OrdLogState")

      // And listeners reopened
      futTradeListener.expectMsg(Listener.Open(ReplicationParams(Combined, Some(ReplState("FutTradeState")))))
      optTradeListener.expectMsg(Listener.Open(ReplicationParams(Combined, Some(ReplState("OptTradeState")))))
      ordLogListener.expectMsg(Listener.Open(ReplicationParams(Combined, Some(ReplState("OrdLogState")))))

      expectMsg(Terminated(reopen))
    }
  }


}