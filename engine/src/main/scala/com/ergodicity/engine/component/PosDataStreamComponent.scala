package com.ergodicity.engine.component

import java.io.File
import plaza2.RequestType.CombinedDynamic
import plaza2.{DataStream => P2DataStream, TableSet => P2TableSet}

object PosDataStreamComponent {
  val StreamName = "FORTS_POS_REPL"
}

trait PosDataStreamComponent {
  def underlyingPos: P2DataStream
}

trait PosDataStream extends PosDataStreamComponent {
  import PosDataStreamComponent._

  def posIni: File
  private lazy val tableSet = P2TableSet(posIni)
  lazy val underlyingPos = P2DataStream(StreamName, CombinedDynamic, tableSet)
}
