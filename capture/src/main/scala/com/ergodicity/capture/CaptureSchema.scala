package com.ergodicity.capture

import org.squeryl.Schema
import java.sql.Timestamp

case class CapturedSession(id: Int,
                              begin: Timestamp,
                              end: Timestamp,
                              optionSessionId: Int,
                              interClBegin: Timestamp,
                              interClEnd: Timestamp,
                              eveBegin: Option[Timestamp],
                              eveEnd: Option[Timestamp],
                              monBegin: Option[Timestamp],
                              monEnd: Option[Timestamp],
                              posTransferBegin: Timestamp,
                              posTransferEnd: Timestamp)

object CaptureSchema extends Schema {
  val sessions = table[CapturedSession]("SESSIONS")
}
