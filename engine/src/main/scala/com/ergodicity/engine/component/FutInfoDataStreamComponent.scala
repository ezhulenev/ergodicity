package com.ergodicity.engine.component

import com.ergodicity.plaza2.DataStream
import java.io.File
import plaza2.RequestType.CombinedDynamic
import plaza2.{DataStream => P2DataStream, TableSet => P2TableSet}

object FutInfoDataStreamComponent {
  val StreamName = "FORTS_FUTINFO_REPL"
}

trait FutInfoDataStreamComponent {
  def futInfoCreator: DataStream
}

trait FutInfoDataStream extends FutInfoDataStreamComponent {
  import FutInfoDataStreamComponent._

  def futInfoIni: File
  private val tableSet = P2TableSet(futInfoIni)
  private val underlying = P2DataStream(StreamName, CombinedDynamic, tableSet)

  def futInfoCreator = new DataStream(underlying)
}
