import com.ergodicity.capture.CaptureEngineConfig
import com.ergodicity.capture._

new CaptureEngineConfig {
  admin.httpPort = 19000

  val connectionProperties = ConnectionProperties("localhost", 4001, "CaptureEngineDev")

  val scheme = Plaza2Scheme(
    "capture/scheme/FutInfoSessionsAndContents.ini",
    "capture/scheme/OptInfoSessionContents.ini",
    "capture/scheme/OrdLog.ini",
    "capture/scheme/FutTradeDeal.ini",
    "capture/scheme/OptTradeDeal.ini"
  )

  val database = MongoDefault("MarketCaptureDev")
  
  val kestrel = KestrelConfig("localhost", 22133, "trades", "orders", 30)
}