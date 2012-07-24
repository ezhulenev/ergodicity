package integration.ergodicity.capture

import org.scalatest.WordSpec
import org.slf4j.LoggerFactory
import com.ergodicity.capture.{ReplicationStateRepository, MongoLocal, MarketCaptureRepository}


class RevisionTrackerSpec extends WordSpec {
  val log = LoggerFactory.getLogger(classOf[RevisionTrackerSpec])

  val repository = new MarketCaptureRepository(MongoLocal("RevisionTrackerSpec")) with ReplicationStateRepository

  "MarketCaptureRepository with ReplicationStateRepository" must {
    "set, get and reset replicationState" in {
      repository.setReplicationState("Stream", "Table", 100)
      assert(repository.replicationState("Stream", "Table") == Some(100))
      repository.reset("Stream")
      assert(repository.replicationState("Stream", "Table") == None)
    }
  }
}