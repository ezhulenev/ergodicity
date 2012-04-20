import com.ergodicity.engine.capture.{CaptureScheme, ConnectionProperties, CaptureEngineConfig}

new CaptureEngineConfig {
  admin.httpPort = 19000

  val connectionProperties = ConnectionProperties("localhost", 4001, "CaptureEngineDev")

  val scheme = CaptureScheme("capture/scheme/OrdLog.ini", "capture/scheme/FutTradeDeal.ini", "capture/scheme/OptTradeDeal.ini")
  
}