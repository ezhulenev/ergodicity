package com.ergodicity.engine

import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import akka.actor.{Actor, ActorSystem}
import akka.event.Logging
import com.ergodicity.core._
import com.ergodicity.core.position.Position
import strategy.StrategyId
import com.ergodicity.engine.StrategyEngine.Reconciled
import org.mockito.Mockito._
import com.ergodicity.engine.StrategyEngine.Mismatch
import com.ergodicity.core.FutureContract
import com.ergodicity.engine.StrategyEngine.Mismatched

class StrategyEngineSpec  extends TestKit(ActorSystem("StrategyEngineSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  "Strategy engine" must {
    "reconcile positions" in {
      implicit val services = mock(classOf[Services])
      val engine = TestActorRef(new StrategyEngine() with Actor {
        protected def receive = null
      })
      val underlying = engine.underlyingActor

      val contract = FutureContract(IsinId(1), Isin("RTS-9.12"), ShortIsin(""), "Future contract")
      val portfolioPositions: Map[Security, Position] = Map(contract -> Position(2))

      case object Strategy1 extends StrategyId
      case object Strategy2 extends StrategyId

      val strategiesPositions: Map[(StrategyId, Security), Position] = Map((Strategy1, contract) -> Position(5), (Strategy2, contract) -> Position(-3))

      val reconciliation = underlying.reconcile(portfolioPositions, strategiesPositions)

      log.info("Reconciliation = "+reconciliation)

      assert(reconciliation == Reconciled)
    }

    "find mismatched position" in {
      implicit val services = mock(classOf[Services])
      val engine = TestActorRef(new StrategyEngine() with Actor {
        protected def receive = null
      })
      val underlying = engine.underlyingActor

      val contract = FutureContract(IsinId(1), Isin("RTS-9.12"), ShortIsin(""), "Future contract")
      val portfolioPositions: Map[Security, Position] = Map(contract -> Position(2))

      case object Strategy1 extends StrategyId
      case object Strategy2 extends StrategyId

      val strategiesPositions: Map[(StrategyId, Security), Position] = Map((Strategy1, contract) -> Position(6), (Strategy2, contract) -> Position(-3))

      val reconciliation = underlying.reconcile(portfolioPositions, strategiesPositions)

      log.info("Reconciliation = "+reconciliation)

      assert(reconciliation match {
        case Mismatched(x: Set[_]) if (x.size == 1) =>
          val mismatch = x.head.asInstanceOf[Mismatch]
          mismatch == Mismatch(contract, Position(2), Position(3), Map(Strategy1 -> Position(6), Strategy2 -> Position(-3)))
        case _ => false
      })
    }

    "find mismatched position for different isin" in {
      implicit val services = mock(classOf[Services])
      val engine = TestActorRef(new StrategyEngine() with Actor {
        protected def receive = null
      })
      val underlying = engine.underlyingActor

      val contract1 = FutureContract(IsinId(1), Isin("RTS-9.12"), ShortIsin(""), "Future contract #1")
      val contract2 = FutureContract(IsinId(2), Isin("RTS-12.12"), ShortIsin(""), "Future contract #2")
      val portfolioPositions: Map[Security, Position] = Map(contract1 -> Position(1))

      case object Strategy1 extends StrategyId

      val strategiesPositions: Map[(StrategyId, Security), Position] = Map((Strategy1, contract2) -> Position(1))

      val reconciliation = underlying.reconcile(portfolioPositions, strategiesPositions)

      log.info("Reconciliation = "+reconciliation)

      assert(reconciliation match {
        case Mismatched(x: Set[_]) if (x.size == 2) => true
        case _ => false
      })
    }
  }
}