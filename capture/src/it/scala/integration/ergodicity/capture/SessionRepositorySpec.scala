package integration.ergodicity.capture

import org.scalatest.WordSpec
import org.slf4j.LoggerFactory
import com.ergodicity.capture.{SessionRepository, MongoLocal, MarketCaptureRepository}
import com.ergodicity.core.session.SessionState
import com.ergodicity.core.session.SessionState._
import com.mongodb.casbah.commons.MongoDBObject
import java.nio.ByteBuffer
import com.ergodicity.cgate.scheme.FutInfo

class SessionRepositorySpec extends WordSpec {
  val log = LoggerFactory.getLogger(classOf[SessionRepositorySpec])

  val OptSessionId = 3547

  val repository = new MarketCaptureRepository(MongoLocal("SessionRepositorySpec")) with SessionRepository

  "MarketCaptureRepository with SessionRepository" must {
    "save session records" in {
      val sessionSpec = MongoDBObject("sessionId" -> 12345)

      repository.Sessions.remove(sessionSpec)
      repository.saveSession(sessionRecord(100, 100, 12345, Assigned))
      assert(repository.Sessions.count(sessionSpec) == 1)

      val optSessionId = repository.Sessions.findOne(sessionSpec).map(obj => obj.get("optionSessionId")).get
      assert(optSessionId == OptSessionId)

      repository.Sessions.remove(sessionSpec)
      assert(repository.Sessions.count(sessionSpec) == 0)

    }
  }

  def sessionRecord(replID: Long, revId: Long, sessionId: Int, sessionState: SessionState) = {
    import SessionState._

    val stateValue = sessionState match {
      case Assigned => 0
      case Online => 1
      case Suspended => 2
      case Canceled => 3
      case Completed => 4
    }

    val buff = ByteBuffer.allocate(1000)
    val session = new FutInfo.session(buff)
    session.set_replID(replID)
    session.set_replRev(revId)
    session.set_sess_id(sessionId)
    session.set_state(stateValue)
    session.set_opt_sess_id(OptSessionId)
    session
  }
}