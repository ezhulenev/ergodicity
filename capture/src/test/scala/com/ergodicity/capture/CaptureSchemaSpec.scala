package com.ergodicity.capture

import org.scalatest.{BeforeAndAfterAll, FlatSpec}
import org.slf4j.LoggerFactory
import org.squeryl.{Session => SQRLSession, _}
import adapters.{PostgreSqlAdapter, H2Adapter}
import PrimitiveTypeMode._
import CaptureSchema._
import org.joda.time.DateTime

class CaptureSchemaSpec extends FlatSpec with BeforeAndAfterAll {
  val log = LoggerFactory.getLogger(classOf[CaptureSchemaSpec])

  override protected def beforeAll() {
    initialize()
  }

  "Capture Schema" should "load sessions" in {
    val begin = new DateTime().getMillis
    val end = new DateTime().getMillis
    val zero = 0l

    val session1 = new Session(100, begin, end, 100, begin, end, 0, zero, zero, 0, zero, zero, begin, end)
    val session2 = new Session(100, begin, end, 100, begin, end, 1, begin, end, 0, zero, zero, begin, end)
    transaction {
      val session = sessions.insertOrUpdate(session1)
      val selected = sessions.where(a => a.sess_id === 100).single

      log.info("Selected = " + selected)
      log.info("Session  = " + session)
      assert(selected == session)

      sessions.update(session2)
      val updated = sessions.where(a => a.sess_id === 100).single
      log.info("Updated = " + updated)
    }
  }


  def initialize() {
    println("Setting up data.")

    Class.forName("org.h2.Driver")
    SessionFactory.concreteFactory = Some(() =>
      SQRLSession.create(
        java.sql.DriverManager.getConnection("jdbc:h2:~/example", "sa", ""),
        new H2Adapter {
          override def bigDecimalTypeDeclaration(precision: Int, scale: Int) = super.bigDecimalTypeDeclaration(20, 6)
        })
    )

    inTransaction {
      drop
      create
      printDdl
    }
  }
}