package com.ergodicity.engine.tools

import com.ergodicity.engine.TradingEngine
import com.ergodicity.core.common.Security

case class TrackInstrument[S <: Security](security: S)

class InstrumentTracker {
  self: TradingEngine =>
  

}