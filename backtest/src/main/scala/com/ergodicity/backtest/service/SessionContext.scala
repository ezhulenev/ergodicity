package com.ergodicity.backtest.service

import com.ergodicity.core.{Isin, SessionId, IsinId}
import com.ergodicity.marketdb.model.Security
import com.ergodicity.schema
import com.ergodicity.schema.{OptSessContents, FutSessContents}
import scalaz.Scalaz._

case class SessionContext(session: schema.Session, futures: Seq[FutSessContents], options: Seq[OptSessContents]) {
  val sessionId = SessionId(session.sess_id, session.opt_sess_id)

  val assigned: Isin => Boolean = isin => isFuture(isin) || isOption(isin)

  val isFuture: Isin => Boolean = mutableHashMapMemo {
    isin => futures.exists(_.isin == isin.isin)
  }

  val isOption: Isin => Boolean = mutableHashMapMemo {
    isin => options.exists(_.isin == isin.isin)
  }

  val isinId: Isin => Option[IsinId] = mutableHashMapMemo {
    isin => futures.find(_.isin === isin.isin).map(f => IsinId(f.isin_id)) orElse options.find(_.isin === isin.isin).map(o => IsinId(o.isin_id))
  }
}