import com.ergodicity.engine.capture.{ConnectionProperties, MarketCaptureConfig}

new MarketCaptureConfig {
  admin.httpPort = 19000

  def connectionProperties = ConnectionProperties("localhost", 4001, "MarketCaptureDev")
}