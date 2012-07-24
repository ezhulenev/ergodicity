package integration.ergodicity.capture

import org.scalatest.WordSpec
import org.slf4j.LoggerFactory
import com.ergodicity.plaza2.scheme.OptInfo
import com.mongodb.casbah.commons.MongoDBObject
import com.ergodicity.capture.{OptSessionContentsRepository, MongoLocal, MarketCaptureRepository}

class OptSessionContentsTrackerSpec extends WordSpec {
  val log = LoggerFactory.getLogger(classOf[OptSessionContentsTrackerSpec])

  val repository = new MarketCaptureRepository(MongoLocal("OptSessionContentsTrackerSpec")) with OptSessionContentsRepository

  val rtsOption = OptInfo.SessContentsRecord(10881, 20023, 0, 3550, 160734, "RI175000BR2", "RTS-6.12M150612PA 175000", "Июньский Марж.Амер.Put.175000 Фьюч.контр RTS-6.12", 115)

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