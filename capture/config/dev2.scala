import com.ergodicity.capture._
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import com.ergodicity.cgate.config.{CGateConfig, Replication}
import java.io.File

new CaptureEngineConfig {
  admin.httpPort = 19000

  val connectionConfig = Tcp("localhost", 4001, "CaptureEngineDev")


  val cgateConfig = CGateConfig(new File("cgate/scheme/cgate_dev.ini"), "11111111")

  val replication = ReplicationScheme(
    Replication("FORTS_FUTINFO_REPL", new File("cgate/scheme/fut_info.ini"), "CustReplScheme"),
    Replication("FORTS_OPTINFO_REPL", new File("cgate/scheme/opt_info.ini"), "CustReplScheme"),
    Replication("FORTS_ORDLOG_REPL", new File("cgate/scheme/ordLog_trades.ini"), "CustReplScheme"),
    Replication("FORTS_FUTTRADE_REPL", new File("cgate/scheme/fut_trades.ini"), "CustReplScheme"),
    Replication("FORTS_OPTTRADE_REPL", new File("cgate/scheme/opt_trades.ini"), "CustReplScheme")
  )

  val database = MongoRemote("ergodicity01", "MarketCaptureDev")
  
  val kestrel = Kestrel("ergodicity01", 22133, "trades", "orders", 30)
}