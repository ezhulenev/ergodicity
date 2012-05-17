package integration.ergodicity.capture

import org.scalatest.WordSpec
import org.slf4j.LoggerFactory
import com.ergodicity.capture.{SessionTracker, MongoDefault, MarketCaptureRepository}
import com.ergodicity.core.session.SessionState
import com.ergodicity.core.session.SessionState._
import com.ergodicity.plaza2.scheme.FutInfo.SessionRecord
import com.mongodb.casbah.commons.MongoDBObject

class SessionTrackerSpec extends WordSpec {
  val log = LoggerFactory.getLogger(classOf[SessionTrackerSpec])

  val repository = new MarketCaptureRepository(MongoDefault("SessionTrackerSpec")) with SessionTracker

  "MarketCaptureRepository with SessionTracker" must {
    "save session records" in {
      val sessionSpec = MongoDBObject("sessionId" -> 12345)

      repository.Sessions.remove(sessionSpec)
      repository.saveSession(sessionRecord(100, 100, 12345, Assigned))
      assert(repository.Sessions.count(sessionSpec) == 1)

      val optSessionId = repository.Sessions.findOne(sessionSpec).map(obj => obj.get("optionSessionId")).get
      assert(optSessionId == 3547)

      repository.Sessions.remove(sessionSpec)
      assert(repository.Sessions.count(sessionSpec) == 0)

    }
  }

  def sessionRecord(replID: Long, revId: Long, sessionId: Int, sessionState: SessionState) = {
    import SessionState._

    val begin = "2012/04/12 07:15:00.000"
    val end = "2012/04/12 14:45:00.000"
    val interClBegin = "2012/04/12 12:00:00.000"
    val interClEnd = "2012/04/12 12:05:00.000"
    val eveBegin = "2012/04/11 15:30:00.000"
    val eveEnd = "2012/04/11 23:50:00.000"
    val monBegin = "2012/04/12 07:00:00.000"
    val monEnd = "2012/04/12 07:15:00.000"
    val posTransferBegin = "2012/04/12 13:00:00.000"
    val posTransferEnd = "2012/04/12 13:15:00.000"

    val stateValue = sessionState match {
      case Assigned => 0
      case Online => 1
      case Suspended => 2
      case Canceled => 3
      case Completed => 4
    }

    SessionRecord(replID, revId, 0, sessionId, begin, end, stateValue, 3547, interClBegin, interClEnd, 5136, 1, eveBegin, eveEnd, 1, monBegin, monEnd, posTransferBegin, posTransferEnd)
  }
}