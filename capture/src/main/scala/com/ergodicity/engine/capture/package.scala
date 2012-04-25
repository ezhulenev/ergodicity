package com.ergodicity.engine

import com.ergodicity.engine.core.model.{OptionContract, FutureContract}

package object capture {
  type MarketContents = (Option[Map[Int, FutureContract]], Option[Map[Int, OptionContract]])
}
