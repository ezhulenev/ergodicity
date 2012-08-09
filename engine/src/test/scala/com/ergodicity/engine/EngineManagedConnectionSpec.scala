package com.ergodicity.engine

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.util.duration._
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import akka.actor.ActorSystem
import akka.util.Timeout
import org.mockito.Mockito._
import ru.micexrts.cgate.{Connection => CGConnection}
import service.{ManagedConnection, Connection}
import com.ergodicity.engine.Components.ManagedServices

class EngineManagedConnectionSpec extends TestKit(ActorSystem("EngineManagedConnectionSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  implicit val timeout = Timeout(1.second)

  "Trading engine" must {
    "register Connection service and connect on Start Up" in {
      val engine = TestFSMRef(new Engine with ManagedServices with ManagedConnection with MockedConnection, "Engine")

      // Start engine
      engine ! Engine.StartEngine

      Thread.sleep(700)

      verify(engine.underlyingActor.asInstanceOf[Connection].underlyingConnection).open("")
    }
  }

  trait MockedConnection extends Connection {
    self: Engine =>
    lazy val underlyingConnection = mock(classOf[CGConnection])
  }

}