package com.ergodicity.engine.capture

import org.scalatest.WordSpec
import org.slf4j.LoggerFactory


class MarketCaptureRepositorySpec extends WordSpec {
  val log = LoggerFactory.getLogger(classOf[MarketCaptureRepositorySpec])

  "MarketCaptureRepository" must {
    "work with in memory db" in {
      val repository = new MarketCaptureRepository(MemoryDB("MarketCaptureRepositorySpec"))
      repository.close()
    }

    "set and get revision" in {
      val repository = new MarketCaptureRepository(MemoryDB("MarketCaptureRepositorySpec")) with RevisionTracking

//      assert(repository.revision("Stream", "Table") == None)

      repository.setRevision("Stream", "Table", 100)
      repository.setRevision("Stream", "Table", 101)
      repository.setRevision("Stream", "Table", 102)
      repository.setRevision("Stream", "Table", 103)
      repository.setRevision("Stream", "Table", 104)
      repository.setRevision("Stream", "Table", 105)

      val revision = repository.revision("Stream", "Table")
      log.info("REVISION = "+revision)
      assert(revision == Some(105))

      repository.close()
    }
  }
}