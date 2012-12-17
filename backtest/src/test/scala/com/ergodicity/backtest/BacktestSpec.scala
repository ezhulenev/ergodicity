package com.ergodicity.backtest

import akka.actor.ActorSystem
import akka.event.Logging
import akka.testkit.{ImplicitSender, TestKit}
import com.ergodicity.backtest.Backtest.Config
import com.ergodicity.engine.strategy.CoverAllPositions
import com.ergodicity.schema.ErgodicitySchema._
import org.joda.time.DateTime
import org.scala_tools.time.Implicits._
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.{FlatSpec, BeforeAndAfterAll}
import org.squeryl.PrimitiveTypeMode._
import org.squeryl.adapters.H2Adapter
import org.squeryl.{SessionFactory, Session => SQRLSession}
import scala.Some

class BacktestSpec extends TestKit(ActorSystem("BacktestSpec", com.ergodicity.engine.EngineSystemConfig)) with FlatSpec with ShouldMatchers with BeforeAndAfterAll with ImplicitSender {
  val log = Logging(system, self)

  override protected def beforeAll() {
    initialize()
  }

  override def afterAll() {
    system.shutdown()
  }

  val begin = new DateTime
  val end = new DateTime
  implicit val repository = new MarketRepository
  implicit val config = Config(begin to end, Nil)

  "Backtest" should "execute backtesting for given interval" in {

    val backtest = new Backtest("Test", CoverAllPositions())

    val report = backtest.apply()

    Thread.sleep(50000)

    log.info("Report = {}", report)
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