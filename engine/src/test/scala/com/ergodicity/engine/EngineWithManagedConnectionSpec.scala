package com.ergodicity.engine

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.util.duration._
import akka.testkit.{ImplicitSender, TestKit}
import akka.actor.ActorSystem
import akka.util.Timeout

class EngineWithManagedConnectionSpec extends TestKit(ActorSystem("EngineWithManagedConnectionSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  implicit val timeout = Timeout(1.second)
/*
  "Trading engine" must {
    "register Connection service and connect on Start Up" in {
      val engine = TestFSMRef(new Engine with ManagedServices with ManagedStrategies with Connection with MockedConnection, "Engine")

      // Start engine
      engine ! Engine.StartEngine

      Thread.sleep(700)

      verify(engine.underlyingActor.asInstanceOf[UnderlyingConnection].underlyingConnection).open("")
    }
  }

  trait MockedConnection extends UnderlyingConnection {
    self: Engine =>
    lazy val underlyingConnection = mock(classOf[CGConnection])
  }*/

}