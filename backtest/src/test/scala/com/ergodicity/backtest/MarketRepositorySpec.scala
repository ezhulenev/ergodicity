package com.ergodicity.backtest

import akka.actor.ActorSystem
import akka.event.Logging
import akka.testkit.{ImplicitSender, TestKit}
import com.ergodicity.schema.ErgodicitySchema._
import com.ergodicity.schema.Session
import org.joda.time.DateTime
import org.scala_tools.time.Implicits._
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.{FlatSpec, BeforeAndAfterAll}
import org.squeryl.PrimitiveTypeMode._
import org.squeryl.adapters.H2Adapter
import org.squeryl.{SessionFactory, Session => SQRLSession}

class MarketRepositorySpec extends TestKit(ActorSystem("MarketRepositorySpec")) with FlatSpec with ShouldMatchers with BeforeAndAfterAll with ImplicitSender {
  val log = Logging(system, classOf[MarketRepositorySpec])

  override protected def beforeAll() {
    initialize()
  }

  override def afterAll() {
    system.shutdown()
  }

  val repository = new MarketRepository

  "MarketRepository" should "return empty Sessions" in {
    val begin = new DateTime
    val end = begin + 1.hour

    inTransaction {
      val sessions = repository sessions (begin to end)
      sessions.size should equal(0)
    }
  }

  it should "scan sessions" in {
    val begin = new DateTime(2012, 01, 01, 01, 00)
    val end = begin + 8.hours

    inTransaction {
      sessions.insert(new Session(100, begin.getMillis, end.getMillis, 0, 0l, 0l, 0, 0l, 0l, 0, 0l, 0l, 0l, 0l))
      val sess = repository sessions (begin to end)
      sess.size should equal(1)
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