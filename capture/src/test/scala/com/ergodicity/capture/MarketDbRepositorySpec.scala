package com.ergodicity.capture

import org.scalatest.{BeforeAndAfterAll, FlatSpec}
import org.slf4j.LoggerFactory
import org.squeryl.{Session => SQRLSession, SessionFactory}
import org.squeryl.adapters.H2Adapter
import org.squeryl.PrimitiveTypeMode._
import com.ergodicity.capture.CaptureSchema._
import scala.Some
import com.ergodicity.core.SessionId

class MarketDbRepositorySpec extends FlatSpec with BeforeAndAfterAll {
  val log = LoggerFactory.getLogger(classOf[CaptureSchemaSpec])

  override protected def beforeAll() {
    initialize()
  }

  val repository = new MarketCaptureRepository with SessionRepository with FutSessionContentsRepository with OptSessionContentsRepository with ReplicationStateRepository

  "MarketCaptureRepository with SessionRepository" should "save session records" in {
    inTransaction {
      repository.saveSession(Mocking.mockSession(100, 100))
      val session = repository.session(SessionId(100, 100))
      log.info("Session = " + session)
      assert(session.isDefined)
    }
  }

  val gmkFuture = Mocking.mockFuture(4023, 166911, "GMM2", "GMKR-6.12", "Фьючерсный контракт GMKR-06.12", 115, 2, 0)

  "MarketCaptureRepository with FutSessionContentsRepository" should "save fut session contents record" in {
    inTransaction {
      repository.saveSessionContents(gmkFuture)
      assert(repository.futureContents(4023).toSeq.size == 1)
      val isinId = repository.futureContents(4023).toSeq.head.isin_id
      assert(isinId == 166911)
    }
  }

  val rtsOption = Mocking.mockOption(3550, 160734, "RI175000BR2", "RTS-6.12M150612PA 175000", "Июньский Марж.Амер.Put.175000 Фьюч.контр RTS-6.12", 115)

  "MarketCaptureRepository with OptSessionContentsRepository" should "save opt session contents record" in {
    inTransaction {
      repository.saveSessionContents(rtsOption)
      assert(repository.optionContents(3550).toSeq.size == 1)
      val isinId = repository.optionContents(3550).toSeq.head.isin_id
      assert(isinId == 160734)
    }
  }

  val State = "ReplicationStateValue#10000"

  "MarketCaptureRepository with ReplicationStateRepository" should "set, get and reset replication state" in {
    inTransaction {
      repository.setReplicationState("Stream", State)
      assert(repository.replicationState("Stream") == Some(State))
      repository.reset("Stream")
      assert(repository.replicationState("Stream") == None)
    }
  }

  def initialize() {
    println("Setting up data.")
    Class.forName("org.h2.Driver")
    SessionFactory.concreteFactory = Some(() =>
      SQRLSession.create(
        java.sql.DriverManager.getConnection("jdbc:h2:~/example", "sa", ""),
        new H2Adapter)
    )

    inTransaction {
      drop
      create
    }
  }

}