package com.ergodicity.backtest.service

import com.ergodicity.core.IsinId
import com.ergodicity.marketdb.model.Security
import com.ergodicity.schema
import com.ergodicity.schema.{OptSessContents, FutSessContents}
import scalaz.Scalaz._

case class SessionContext(session: schema.Session, futures: Seq[FutSessContents], options: Seq[OptSessContents]) {
  val isFuture: Security => Boolean = mutableHashMapMemo {
    security => futures.exists(_.isin == security.isin)
  }

  val isOption: Security => Boolean = mutableHashMapMemo {
    security => options.exists(_.isin == security.isin)
  }

  val isinId: Security => Option[IsinId] = mutableHashMapMemo {
    security => futures.find(_.isin === security.isin).map(f => IsinId(f.isin_id)) orElse options.find(_.isin === security.isin).map(o => IsinId(o.isin_id))
  }
}