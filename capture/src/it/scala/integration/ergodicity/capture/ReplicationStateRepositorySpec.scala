package integration.ergodicity.capture

import org.scalatest.WordSpec
import org.slf4j.LoggerFactory
import com.ergodicity.capture.{ReplicationStateRepository, MongoLocal, MarketCaptureRepository}


class ReplicationStateRepositorySpec extends WordSpec {
  val log = LoggerFactory.getLogger(classOf[ReplicationStateRepositorySpec])

  val State = "ReplicationStateValue#10000"

  val repository = new MarketCaptureRepository(MongoLocal("ReplicationStateRepositorySpec")) with ReplicationStateRepository

  "MarketCaptureRepository with ReplicationStateRepository" must {
    "set, get and reset replicationState" in {
      repository.setReplicationState("Stream", State)
      assert(repository.replicationState("Stream") == Some(State))
      repository.reset("Stream")
      assert(repository.replicationState("Stream") == None)
    }
  }
}