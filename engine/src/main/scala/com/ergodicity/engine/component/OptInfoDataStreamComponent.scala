package com.ergodicity.engine.component

import java.io.File
import plaza2.RequestType.CombinedDynamic
import plaza2.{DataStream => P2DataStream, TableSet => P2TableSet}

object OptInfoDataStreamComponent {
  val StreamName = "FORTS_OPTINFO_REPL"
}

trait OptInfoDataStreamComponent {
  def underlyingOptInfo: P2DataStream
}

trait OptInfoDataStream extends OptInfoDataStreamComponent {
  import OptInfoDataStreamComponent._

  def optInfoIni: File
  private lazy val tableSet = P2TableSet(optInfoIni)
  lazy val underlyingOptInfo = P2DataStream(StreamName, CombinedDynamic, tableSet)
}

