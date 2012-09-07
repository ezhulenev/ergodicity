import sbt._
import Process._

object SchemeTools {

  val Package = "com.ergodicity.cgate.scheme"

  case class SchemeProps(ini: String, className: String, scheme: String) {
    val fileName = className + ".java"
  }

  private val Schemes = List(
    SchemeProps("FutInfo.ini", "FutInfo", "CustReplScheme"),
    SchemeProps("OptInfo.ini", "OptInfo", "CustReplScheme"),
    SchemeProps("FutTrade.ini", "FutTrade", "CustReplScheme"),
    SchemeProps("OptTrade.ini", "OptTrade", "CustReplScheme"),
    SchemeProps("Pos.ini", "Pos", "CustReplScheme"),
    SchemeProps("OrdLog.ini", "OrdLog", "CustReplScheme"),
    SchemeProps("FortsMessages.ini", "Message", "message"),
    SchemeProps("Orderbook.ini", "OrderBook", "CustReplScheme")
  )

  def generateSchemes(projectDir: File, outDir: File): Seq[File] = {
    val temp = IO.createTemporaryDirectory

    Schemes.foreach {
      case props: SchemeProps =>
        val cmd = makeCmd(
          temp / props.fileName,
          props.className,
          projectDir / "scheme" / props.ini,
          props.scheme
        )
        cmd.!!
    }

    val target = outDir / "com" / "ergodicity" / "cgate" / "scheme"
    IO.copyDirectory(temp, target)
    IO.listFiles(target).toSeq
  }

  private def makeCmd(out: File, className: String, ini: File, scheme: String) = {
    "schemetool makesrc -O java -o %s -Djava-time-format=long -Djava-user-package=%s -Djava-class-name=%s %s %s".format(
      out,
      Package,
      className,
      ini,
      scheme
    )
  }
}