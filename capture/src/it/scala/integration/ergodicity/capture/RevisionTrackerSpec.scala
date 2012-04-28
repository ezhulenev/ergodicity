package integration.ergodicity.capture

import org.scalatest.WordSpec
import org.slf4j.LoggerFactory
import com.ergodicity.capture.{RevisionTracker, MongoDefault, MarketCaptureRepository}


class RevisionTrackerSpec extends WordSpec {
  val log = LoggerFactory.getLogger(classOf[RevisionTrackerSpec])

  val repository = new MarketCaptureRepository(MongoDefault("RevisionTrackerSpec")) with RevisionTracker

  "MarketCaptureRepository with RevisionTracker" must {
    "set, get and reset revision" in {
      repository.setRevision("Stream", "Table", 100)
      assert(repository.revision("Stream", "Table") == Some(100))
      repository.reset("Stream")
      assert(repository.revision("Stream", "Table") == None)
    }
  }
}