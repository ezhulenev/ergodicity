import com.ergodicity.capture._
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import com.ergodicity.cgate.config.{CGateConfig, Replication}
import org.squeryl.{Session => SQRLSession, _}
import adapters.PostgreSqlAdapter
import java.io.File

new CaptureEngineConfig {
  admin.httpPort = 19000

  val connectionConfig = Tcp("localhost", 4001, "CaptureEngineDev")

  val cgateConfig = CGateConfig(new File("cgate/scheme/cgate_dev.ini"), "11111111")

  val replication = ReplicationScheme(
    Replication("FORTS_FUTINFO_REPL", new File("cgate/scheme/FutInfo.ini"), "CustReplScheme"),
    Replication("FORTS_OPTINFO_REPL", new File("cgate/scheme/OptInfo.ini"), "CustReplScheme"),
    Replication("FORTS_ORDLOG_REPL", new File("cgate/scheme/OrdLog.ini"), "CustReplScheme"),
    Replication("FORTS_FUTTRADE_REPL", new File("cgate/scheme/FutTrades.ini"), "CustReplScheme"),
    Replication("FORTS_OPTTRADE_REPL", new File("cgate/scheme/OptTrades.ini"), "CustReplScheme")
  )

  val kestrel = Kestrel("localhost", 22133, "trades", "orders", 30)

  Class.forName("org.postgresql.Driver")

  val sessionFactory = () => SQRLSession.create(java.sql.DriverManager.getConnection("jdbc:postgresql://localhost:5432/capture-dev", "ezhulenev", ""), new PostgreSqlAdapter)
}