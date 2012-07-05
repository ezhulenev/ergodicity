package com.ergodicity.engine.component

import java.io.File
import plaza2.RequestType.CombinedDynamic
import plaza2.{DataStream => P2DataStream, TableSet => P2TableSet}

object FutInfoDataStreamComponent {
  val StreamName = "FORTS_FUTINFO_REPL"
}

trait FutInfoDataStreamComponent {
  def underlyingFutInfo: P2DataStream
}

trait FutInfoDataStream extends FutInfoDataStreamComponent {
  import FutInfoDataStreamComponent._

  def futInfoIni: File
  private lazy val tableSet = P2TableSet(futInfoIni)
  lazy val underlyingFutInfo = P2DataStream(StreamName, CombinedDynamic, tableSet)
}
