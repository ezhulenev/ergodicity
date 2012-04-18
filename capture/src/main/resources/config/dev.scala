import com.ergodicity.engine.capture.{ConnectionProperties, CaptureEngineConfig}

new CaptureEngineConfig {
  admin.httpPort = 19000

  def connectionProperties = ConnectionProperties("localhost", 4001, "CaptureEngineDev")
}