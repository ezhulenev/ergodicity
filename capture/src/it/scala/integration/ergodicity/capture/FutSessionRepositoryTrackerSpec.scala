package integration.ergodicity.capture

import org.scalatest.WordSpec
import org.slf4j.LoggerFactory
import com.ergodicity.capture.{FutSessionContentsRepository, MongoLocal, MarketCaptureRepository}
import com.mongodb.casbah.commons.MongoDBObject
import com.ergodicity.core.Mocking._

class FutSessionRepositoryTrackerSpec extends WordSpec {
  val log = LoggerFactory.getLogger(classOf[FutSessionRepositoryTrackerSpec])

  val repository = new MarketCaptureRepository(MongoLocal("FutSessionRepositoryTrackerSpec")) with FutSessionContentsRepository

  val gmkFuture = mockFuture(4023, 166911, "GMM2", "GMKR-6.12", "Фьючерсный контракт GMKR-06.12", 115, 2, 0)

  "MarketCaptureRepository with FutSessionContentsRepository" must {
    "save fut session contents record" in {
      val sessionSpec = MongoDBObject("sessionId" -> 4023)

      repository.FutContents.remove(sessionSpec)
      repository.saveSessionContents(gmkFuture)
      assert(repository.FutContents.count(sessionSpec) == 1)

      val isinId = repository.FutContents.findOne(sessionSpec).map(obj => obj.get("isinId")).get
      assert(isinId == 166911)

      repository.FutContents.remove(sessionSpec)
      assert(repository.FutContents.count(sessionSpec) == 0)
    }
  }
}