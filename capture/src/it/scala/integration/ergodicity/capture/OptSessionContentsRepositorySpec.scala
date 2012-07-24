package integration.ergodicity.capture

import org.scalatest.WordSpec
import org.slf4j.LoggerFactory
import com.mongodb.casbah.commons.MongoDBObject
import com.ergodicity.capture.{OptSessionContentsRepository, MongoLocal, MarketCaptureRepository}
import com.ergodicity.core.Mocking._

class OptSessionContentsRepositorySpec extends WordSpec {
  val log = LoggerFactory.getLogger(classOf[OptSessionContentsRepositorySpec])

  val repository = new MarketCaptureRepository(MongoLocal("OptSessionContentsRepositorySpec")) with OptSessionContentsRepository

  val rtsOption = mockOption(3550, 160734, "RI175000BR2", "RTS-6.12M150612PA 175000", "Июньский Марж.Амер.Put.175000 Фьюч.контр RTS-6.12", 115)

  "MarketCaptureRepository with OptSessionContentsRepository" must {
    "save fut session contents record" in {
      val sessionSpec = MongoDBObject("sessionId" -> 3550)

      repository.OptContents.remove(sessionSpec)
      repository.saveSessionContents(rtsOption)
      assert(repository.OptContents.count(sessionSpec) == 1)

      val isinId = repository.OptContents.findOne(sessionSpec).map(obj => obj.get("isinId")).get
      assert(isinId == 160734)

      repository.OptContents.remove(sessionSpec)
      assert(repository.OptContents.count(sessionSpec) == 0)
    }
  }
}