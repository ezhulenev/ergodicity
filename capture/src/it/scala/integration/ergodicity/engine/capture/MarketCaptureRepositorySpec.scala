package integration.ergodicity.engine.capture

import org.scalatest.WordSpec
import org.slf4j.LoggerFactory
import com.ergodicity.engine.capture.{RevisionTracker, MongoDefault, MarketCaptureRepository}


class MarketCaptureRepositorySpec extends WordSpec {
  val log = LoggerFactory.getLogger(classOf[MarketCaptureRepositorySpec])

  val repository = new MarketCaptureRepository(MongoDefault("MarketCaptureRepositorySpec")) with RevisionTracker

  "MarketCaptureRepository" must {
    "set, get and reset revision" in {
      repository.setRevision("Stream", "Table", 100)
      assert(repository.revision("Stream", "Table") == Some(100))
      repository.reset("Stream")
      assert(repository.revision("Stream", "Table") == None)
    }
  }
}