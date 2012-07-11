package com.ergodicity.engine.component

import java.io.File
import plaza2.RequestType.CombinedDynamic
import plaza2.{DataStream => P2DataStream, TableSet => P2TableSet}

object PosDataStreamComponent {
  val StreamName = "FORTS_POS_REPL"
}

trait PosDataStreamComponent {
  def posIni: Option[File] = None
  def underlyingPos: P2DataStream
}

trait PosDataStream extends PosDataStreamComponent {
  import PosDataStreamComponent._

  private lazy val tableSet = {
    if (!posIni.isDefined) throw new IllegalStateException("POS data stream scheme not defined")
    P2TableSet(posIni.get)
  }
  lazy val underlyingPos = P2DataStream(StreamName, CombinedDynamic, tableSet)
}
