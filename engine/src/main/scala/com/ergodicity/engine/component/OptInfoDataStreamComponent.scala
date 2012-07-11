package com.ergodicity.engine.component

import java.io.File
import plaza2.RequestType.CombinedDynamic
import plaza2.{DataStream => P2DataStream, TableSet => P2TableSet}

object OptInfoDataStreamComponent {
  val StreamName = "FORTS_OPTINFO_REPL"
}

trait OptInfoDataStreamComponent {
  def optInfoIni: Option[File] = None
  def underlyingOptInfo: P2DataStream
}

trait OptInfoDataStream extends OptInfoDataStreamComponent {
  import OptInfoDataStreamComponent._

  private lazy val tableSet = {
    if (!optInfoIni.isDefined) throw new IllegalStateException("OptInfo data stream scheme not defined")
    P2TableSet(optInfoIni.get)
  }

  lazy val underlyingOptInfo = P2DataStream(StreamName, CombinedDynamic, tableSet)
}

