package com.ergodicity.backtest

import akka.actor.{ActorRef, Props, ActorSystem}
import akka.event.Logging
import akka.testkit._
import com.ergodicity.backtest.cgate.{ListenerStub, ListenerStubActor}
import com.ergodicity.cgate.config.ListenerConfig
import com.ergodicity.engine.ReplicationScheme._
import com.ergodicity.engine.service.{ReplicationConnection, InstrumentData}
import com.ergodicity.engine.underlying._
import com.ergodicity.engine.{ServicesActor, Engine}
import org.mockito.Mockito._
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import ru.micexrts.cgate.{Connection => CGConnection, ISubscriber}
import scala.collection.mutable

class InstrumentDataServiceBacktestSpec extends TestKit(ActorSystem("InstrumentDataServiceBacktestSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  object Replications {
    val OptInfo = mock(classOf[com.ergodicity.cgate.config.Replication])
    val FutInfo = mock(classOf[com.ergodicity.cgate.config.Replication])
  }

  // -- Engine Components
  trait Connections extends UnderlyingConnection {
    val underlyingConnection = mock(classOf[CGConnection])
  }

  trait Replication extends FutInfoReplication with OptInfoReplication {
    val optInfoReplication = Replications.OptInfo

    val futInfoReplication = Replications.FutInfo
  }

  trait Listener extends UnderlyingListener {
    self: BacktestEngine =>

    val listenerFactory = new ListenerFactory {
      def apply(connection: CGConnection, config: ListenerConfig, subscriber: ISubscriber) = config match {
        case Replications.OptInfo =>
          listenerActors.getOrElse(Replications.OptInfo, system.actorOf(Props(new ListenerStubActor(subscriber)), "OptInfoListenerActor"))
          ListenerStub wrap listenerActors(Replications.OptInfo)

        case Replications.FutInfo =>
          listenerActors.getOrElse(Replications.FutInfo, system.actorOf(Props(new ListenerStubActor(subscriber)), "FutInfoListenerActor"))
          ListenerStub wrap listenerActors(Replications.FutInfo)

        case _ => throw new IllegalArgumentException("Unknown listener config = " + config)
      }
    }
  }

  // -- Backtest Engine
  class BacktestEngine extends Engine with Connections with Replication with Listener {
    self: BacktestEngine =>

    val listenerActors = mutable.Map[com.ergodicity.cgate.config.Replication, ActorRef]()
  }

  // -- Backtest services
  class BacktestServices(val engine: BacktestEngine) extends ServicesActor with ReplicationConnection with InstrumentData


  "InstrumentData Service" must {
    "do some stuff" in {
      log.info("Ebaka!")
    }
    /*
        "initialized in Idle state" in {
          val underlyingConnection = mock(classOf[CGConnection])
          val optInfoReplication = mock(classOf[Replication])
          val futInfoReplication = mock(classOf[Replication])

          implicit val services = mock(classOf[Services])

          val service = TestFSMRef(new InstrumentDataService(listenerFactory, underlyingConnection, futInfoReplication, optInfoReplication), "InstrumentDataService")

          assert(service.stateName == InstrumentDataState.Idle)
        }
    */

    /*
        "start service" in {
          val underlyingConnection = mock(classOf[CGConnection])
          val optInfoReplication = mock(classOf[Replication])
          val futInfoReplication = mock(classOf[Replication])

          implicit val services = mock(classOf[Services])

          val service = TestFSMRef(new InstrumentDataService(listenerFactory, underlyingConnection, futInfoReplication, optInfoReplication), "InstrumentDataService")
          val underlying = service.underlyingActor.asInstanceOf[InstrumentDataService]

          when("got Start message")
          service ! Start

          then("should go to Starting state")
          assert(service.stateName == InstrumentDataState.Starting)

          when("both streams goes online")
          service ! CurrentState(underlying.FutInfoStream, DataStreamState.Online)
          service ! CurrentState(underlying.OptInfoStream, DataStreamState.Online)

          then("service shoud be started")
          assert(service.stateName == Started)
          and("Service Manager should be notified")
          Thread.sleep(700) // Notification delayed
          verify(services).serviceStarted(InstrumentData.InstrumentData)
        }
    */

    /*
        "stop service" in {
          val underlyingConnection = mock(classOf[CGConnection])
          val optInfoReplication = mock(classOf[Replication])
          val futInfoReplication = mock(classOf[Replication])

          implicit val services = mock(classOf[Services])

          given("service in Started state")
          val service = TestFSMRef(new InstrumentDataService(listenerFactory, underlyingConnection, futInfoReplication, optInfoReplication), "InstrumentDataService")
          val underlying = service.underlyingActor.asInstanceOf[InstrumentDataService]
          service.setState(InstrumentDataState.Started)

          when("stop Service")
          service ! Service.Stop

          then("should go to Stopping states")
          assert(service.stateName == InstrumentDataState.Stopping)

          when("both streams closed")
          service ! Transition(underlying.FutInfoStream, DataStreamState.Online, DataStreamState.Closed)
          service ! Transition(underlying.OptInfoStream, DataStreamState.Online, DataStreamState.Closed)

          then("service shoud be stopped")
          watch(service)
          expectMsg(Terminated(service))

          and("service Manager should be notified")
          verify(services).serviceStopped(InstrumentData.InstrumentData)
        }
    */

    /*
        "fail starting" in {
          val underlyingConnection = mock(classOf[CGConnection])
          val optInfoReplication = mock(classOf[Replication])
          val futInfoReplication = mock(classOf[Replication])

          implicit val services = mock(classOf[Services])

          given("service in Starting state")
          val service = TestFSMRef(new InstrumentDataService(listenerFactory, underlyingConnection, futInfoReplication, optInfoReplication), "InstrumentDataService")
          service.setState(InstrumentDataState.Starting)

          when("starting timed out")
          then("should fail with exception")
          intercept[ServiceFailedException] {
            service receive FSM.StateTimeout
          }
        }
    */

    /*
        "fail stopping" in {
          val underlyingConnection = mock(classOf[CGConnection])
          val optInfoReplication = mock(classOf[Replication])
          val futInfoReplication = mock(classOf[Replication])

          implicit val services = mock(classOf[Services])

          given("service in Stopping state")
          val service = TestFSMRef(new InstrumentDataService(listenerFactory, underlyingConnection, futInfoReplication, optInfoReplication), "InstrumentDataService")
          service.setState(InstrumentDataState.Stopping)

          when("stopping timed out")
          then("should fail with exception")
          intercept[ServiceFailedException] {
            service receive FSM.StateTimeout
          }
        }
    */
  }
}