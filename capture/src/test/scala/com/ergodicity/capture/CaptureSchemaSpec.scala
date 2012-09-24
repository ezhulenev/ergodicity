package com.ergodicity.capture

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import org.slf4j.LoggerFactory
import org.squeryl._
import adapters.H2Adapter
import PrimitiveTypeMode._
import CaptureSchema._
import java.sql.Timestamp
import org.joda.time.DateTime

class CaptureSchemaSpec extends WordSpec with BeforeAndAfterAll {
  val log = LoggerFactory.getLogger(classOf[CaptureSchemaSpec])

  override protected def beforeAll() {
    initialize()
  }

  "Capture Schema" must {
    "load sessions" in {
      transaction {
        val begin = new Timestamp(new DateTime().getMillis)
        val end = new Timestamp(new DateTime().getMillis)
        val session = sessions.insert(new CapturedSession(100, begin, end, 100, begin, end, None, None, None, None, begin, end))
        val selected = sessions.where(a => a.id === 100).single

        log.info("Selected = "+selected)
        log.info("Session  = "+session)
        assert(selected == session)
      }
    }
  }

  def initialize() {
    println("Setting up data.")
    Class.forName("org.h2.Driver")
    SessionFactory.concreteFactory = Some(() =>
      Session.create(
        java.sql.DriverManager.getConnection("jdbc:h2:~/example", "sa", ""),
        new H2Adapter)
    )

    inTransaction {
      drop
      create
      printDdl
    }
  }
}