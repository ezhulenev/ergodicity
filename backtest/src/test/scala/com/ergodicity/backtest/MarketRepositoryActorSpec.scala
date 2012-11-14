package com.ergodicity.backtest

import org.scalatest.{FlatSpec, BeforeAndAfterAll}
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import akka.actor.ActorSystem
import org.squeryl.{SessionFactory, Session => SQRLSession}
import org.squeryl.adapters.H2Adapter
import org.squeryl.PrimitiveTypeMode._
import akka.pattern.ask
import akka.event.Logging
import com.ergodicity.schema.Session
import org.joda.time.DateTime
import org.scala_tools.time.Implicits._
import com.ergodicity.schema.ErgodicitySchema._
import akka.dispatch.{Await, ExecutionContext}
import java.util.concurrent.{Executor, TimeUnit, Executors}
import akka.util.{Duration, Timeout}
import org.scalatest.matchers.ShouldMatchers
import com.ergodicity.backtest.MarketRepository.Sessions
import com.ergodicity.backtest.MarketRepository.ScanSessions
import scala.Some

class MarketRepositoryActorSpec extends TestKit(ActorSystem("MarketRepositoryActorSpec")) with FlatSpec with ShouldMatchers with BeforeAndAfterAll with ImplicitSender {
  val log = Logging(system, classOf[MarketRepositoryActorSpec])

  override protected def beforeAll() {
    initialize()
  }

  override def afterAll() {
    system.shutdown()
  }

  implicit val timeout = Timeout(1, TimeUnit.SECONDS)

  val repository = TestActorRef(new MarketRepositoryActor()(ExecutionContext.fromExecutor(CallingThreadExecutor)))

  "MarketRepositoryActor" should "return empty Sessions" in {
    val begin = new DateTime
    val end = begin + 1.hour

    val sess = Await.result((repository ? ScanSessions(begin to end)).mapTo[Sessions], Duration(1, TimeUnit.SECONDS)).sessions
    sess.size should equal (0)
  }

  it should "scan sessions" in {
    val begin = new DateTime(2012, 01, 01, 01, 00)
    val end = begin + 8.hours
    inTransaction {
      log.info("Insert session")
      sessions.insert(new Session(100, begin.getMillis, end.getMillis, 0, 0l, 0l, 0, 0l, 0l, 0, 0l, 0l, 0l, 0l))

      val sess = Await.result((repository ? ScanSessions(begin to end)).mapTo[Sessions], Duration(1, TimeUnit.SECONDS)).sessions
      sess.size should equal (1)
    }
  }

  def initialize() {
    println("Setting up data.")
    Class.forName("org.h2.Driver")
    SessionFactory.concreteFactory = Some(() =>
      SQRLSession.create(
        java.sql.DriverManager.getConnection("jdbc:h2:~/MarketContentsCaptureSpec", "sa", ""),
        new H2Adapter)
    )

    inTransaction {
      drop
      create
    }
  }


}