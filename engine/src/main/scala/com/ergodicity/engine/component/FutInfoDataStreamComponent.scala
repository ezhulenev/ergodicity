package com.ergodicity.engine.component

import java.io.File
import plaza2.RequestType.CombinedDynamic
import plaza2.{DataStream => P2DataStream, TableSet => P2TableSet}

object FutInfoDataStreamComponent {
  val StreamName = "FORTS_FUTINFO_REPL"
}

trait FutInfoDataStreamComponent {
  def futInfoIni: Option[File] = None
  def underlyingFutInfo: P2DataStream
}

trait FutInfoDataStream extends FutInfoDataStreamComponent {
  import FutInfoDataStreamComponent._

  private lazy val tableSet = {
    if (!futInfoIni.isDefined) throw new IllegalStateException("FutInfo data stream scheme not defined")
    P2TableSet(futInfoIni.get)
  }

  lazy val underlyingFutInfo = P2DataStream(StreamName, CombinedDynamic, tableSet)
}
