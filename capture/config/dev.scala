import com.ergodicity.capture._
import com.ergodicity.cgate.config.ConnectionType.Tcp
import com.ergodicity.cgate.config.Replication
import java.io.File

new CaptureEngineConfig {
  admin.httpPort = 19000

  val connectionType = Tcp("localhost", 4001, "CaptureEngineDev")

  val replication = ReplicationScheme(
    Replication("FORTS_FUTINFO_REPL", new File("cgate/scheme/fut_info.ini"), "CustReplScheme"),
    Replication("FORTS_OPTINFO_REPL", new File("cgate/scheme/opt_info.ini"), "CustReplScheme"),
    Replication("FORTS_ORDLOG_REPL", new File("cgate/scheme/ordLog_trades.ini"), "CustReplScheme"),
    Replication("FORTS_FUTTRADE_REPL", new File("cgate/scheme/fut_trades.ini"), "CustReplScheme"),
    Replication("FORTS_OPTTRADE_REPL", new File("cgate/scheme/opt_trades.ini"), "CustReplScheme")
  )

  val database = MongoLocal("MarketCaptureDev")
  
  val kestrel = KestrelConfig("localhost", 22133, "trades", "orders", 30)
}