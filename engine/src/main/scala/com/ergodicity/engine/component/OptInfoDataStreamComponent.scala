package com.ergodicity.engine.component

import com.ergodicity.plaza2.DataStream
import java.io.File
import plaza2.RequestType.CombinedDynamic
import plaza2.{DataStream => P2DataStream, TableSet => P2TableSet}

object OptInfoDataStreamComponent {
  val StreamName = "FORTS_OPTINFO_REPL"
}

trait OptInfoDataStreamComponent {
  def optInfoCreator: DataStream
}

trait OptInfoDataStream extends OptInfoDataStreamComponent {
  import OptInfoDataStreamComponent._

  def optInfoIni: File
  private val tableSet = P2TableSet(optInfoIni)
  private val underlying = P2DataStream(StreamName, CombinedDynamic, tableSet)

  def optInfoCreator = new DataStream(underlying)
}

