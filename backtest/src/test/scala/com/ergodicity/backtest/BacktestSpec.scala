package com.ergodicity.backtest

import akka.actor.ActorSystem
import akka.event.Logging
import akka.testkit.{ImplicitSender, TestKit}
import com.ergodicity.backtest.Backtest.Config
import com.ergodicity.core.FutureContract
import com.ergodicity.core._
import com.ergodicity.core.session.InstrumentState
import com.ergodicity.engine.strategy.CoverAllPositions
import com.ergodicity.schema.ErgodicitySchema._
import com.ergodicity.schema.{OptSessContents, FutSessContents, Session}
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

  val (eveBegin, eveEnd) = (new DateTime(2012, 01, 01, 19, 0, 0), new DateTime(2012, 01, 01, 23, 45, 0))
  val (begin, end) = (new DateTime(2012, 01, 02, 10, 0, 0), new DateTime(2012, 01, 02, 18, 0, 0))
  val (intClearingBegin, intClearingEnd) = (new DateTime(2012, 01, 02, 13, 0, 0), new DateTime(2012, 01, 02, 13, 15, 0))

  implicit def time2millis(dt: DateTime) = dt.getMillis

  val id = SessionId(100, 100)
  val session = new Session(id.fut, begin, end, id.opt, intClearingBegin, intClearingEnd, 1, eveBegin, eveEnd, 0, 0, 0, 0, 0)

  val futureContract = FutureContract(IsinId(100), Isin("FISIN"), ShortIsin("FISINS"), "Future")
  val optionContract = OptionContract(IsinId(101), Isin("OISIN"), ShortIsin("OISINS"), "Option")

  val futures = FutSessContents(Mocking.mockFuture(id.fut, futureContract.id.id, futureContract.isin.isin, futureContract.shortIsin.shortIsin, futureContract.name, 115, InstrumentState.Assigned.toInt)) :: Nil
  val options = OptSessContents(Mocking.mockOption(id.fut, optionContract.id.id, optionContract.isin.isin, optionContract.shortIsin.shortIsin, optionContract.name, 115)) :: Nil

  implicit val repository = new MarketRepository
  implicit val config = Config(begin to end, Nil)

  "Backtest" should "execute backtesting for single session" in {
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

      // Insert prepared session
      sessions.insert(session)
      futSessContents.insert(futures)
      optSessContents.insert(options)
    }
  }


}