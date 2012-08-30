package com.ergodicity.engine

import org.scalatest.WordSpec
import strategy.CloseAllPositions

class StrategyEngineSpec extends WordSpec {

  "StrategyEngine" must {
    "work" in {
      val engine = new StrategyEngine(CloseAllPositions() & CloseAllPositions())
    }
  }

}