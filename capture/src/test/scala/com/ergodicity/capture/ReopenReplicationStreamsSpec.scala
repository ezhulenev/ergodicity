package com.ergodicity.capture

import org.scalatest.{GivenWhenThen, WordSpec, BeforeAndAfterAll}
import akka.testkit.{TestProbe, TestFSMRef, ImplicitSender, TestKit}
import akka.actor.{Terminated, ActorSystem}
import ru.micexrts.cgate.{Connection => CGConnection}
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import org.slf4j.{Logger, LoggerFactory}
import com.mongodb.casbah.TypeImports._
import com.ergodicity.capture.ReopenReplicationStreams.StreamRef
import org.mockito.Mockito._
import com.ergodicity.cgate.{Closed, Listener}
import com.ergodicity.cgate.DataStream.DataStreamReplState
import com.ergodicity.cgate.StreamEvent.ReplState
import com.ergodicity.cgate.config.Replication.ReplicationMode.Combined
import com.ergodicity.cgate.config.Replication.ReplicationParams
import akka.actor.FSM.{UnsubscribeTransitionCallBack, CurrentState, SubscribeTransitionCallBack}

class ReopenReplicationStreamsSpec extends TestKit(ActorSystem("ReopenReplicationStreamsSpec", AkkaConfigurations.ConfigWithDetailedLogging)) with WordSpec with GivenWhenThen with BeforeAndAfterAll with ImplicitSender {
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
      assert(reopen.stateName == ReopenReplicationStreams.SavingReplStates)

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

      given("ReopenReplicationStreams")
      val reopen = TestFSMRef(new ReopenReplicationStreams(repository, futTrade, optTrade, ordLog))

      then("listeners should be closed")
      futTradeListener.expectMsg(Listener.Close)
      optTradeListener.expectMsg(Listener.Close)
      ordLogListener.expectMsg(Listener.Close)

      assert(reopen.stateName == ReopenReplicationStreams.SavingReplStates)

      watch(reopen)

      when("replication states received")
      reopen ! DataStreamReplState(futTradeStream.ref, "FutTradeState")
      reopen ! DataStreamReplState(optTradeStream.ref, "OptTradeState")
      reopen ! DataStreamReplState(ordLogStream.ref, "OrdLogState")

      assert(reopen.stateName == ReopenReplicationStreams.ShuttingDown)

      then("repository should persist new replication states")
      verify(repository).setReplicationState("FutTrade", "FutTradeState")
      verify(repository).setReplicationState("OptTrade", "OptTradeState")
      verify(repository).setReplicationState("OrdLog", "OrdLogState")

      and("track listener states")
      futTradeListener.expectMsg(SubscribeTransitionCallBack(reopen))
      optTradeListener.expectMsg(SubscribeTransitionCallBack(reopen))
      ordLogListener.expectMsg(SubscribeTransitionCallBack(reopen))

      when("all listeners are closed")
      reopen ! CurrentState(futTradeListener.ref, Closed)
      reopen ! CurrentState(optTradeListener.ref, Closed)
      reopen ! CurrentState(ordLogListener.ref, Closed)

      then("unsubscribe from it's states")
      futTradeListener.expectMsg(UnsubscribeTransitionCallBack(reopen))
      optTradeListener.expectMsg(UnsubscribeTransitionCallBack(reopen))
      ordLogListener.expectMsg(UnsubscribeTransitionCallBack(reopen))

      and("reopen them all with previously saved states")
      futTradeListener.expectMsg(Listener.Open(ReplicationParams(Combined, Some(ReplState("FutTradeState")))))
      optTradeListener.expectMsg(Listener.Open(ReplicationParams(Combined, Some(ReplState("OptTradeState")))))
      ordLogListener.expectMsg(Listener.Open(ReplicationParams(Combined, Some(ReplState("OrdLogState")))))

      and("ReopenReplicationStreams should be terminated")
      expectMsg(Terminated(reopen))
    }
  }


}