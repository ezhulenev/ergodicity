package integration.ergodicity.capture

import org.scalatest.WordSpec
import org.slf4j.LoggerFactory
import com.ergodicity.capture.{FutSessionContentsRepository, MongoLocal, MarketCaptureRepository}
import com.ergodicity.plaza2.scheme.FutInfo
import com.mongodb.casbah.commons.MongoDBObject

class FutSessionContentsTrackerSpec extends WordSpec {
  val log = LoggerFactory.getLogger(classOf[FutSessionContentsTrackerSpec])

  val repository = new MarketCaptureRepository(MongoLocal("FutSessionContentsTrackerSpec")) with FutSessionContentsRepository

  val gmkFuture = FutInfo.SessContentsRecord(7477, 47740, 0, 4023, 166911, "GMM2", "GMKR-6.12", "Фьючерсный контракт GMKR-06.12", 115, 2, 0)

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