package com.ergodicity.backtest.service

import akka.actor.ActorRef
import com.ergodicity.marketdb.model.TradePayload
import com.ergodicity.schema
import com.ergodicity.schema.{OptSessContents, FutSessContents}
import scalaz.Scalaz._
import com.ergodicity.core.{Isin, IsinId, SessionId}
import com.ergodicity.backtest.service.TradesService.{OptionTrade, FutureTrade}
import com.ergodicity.backtest.cgate.ListenerStubActor.Dispatch
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.cgate.scheme.{OptTrade, FutTrade}

object TradesService {

  sealed trait Trade

  case class FutureTrade(session: SessionId, id: IsinId, underlying: TradePayload)

  case class OptionTrade(session: SessionId, id: IsinId, underlying: TradePayload)

}

class TradesService(futTrade: ActorRef, optTrade: ActorRef)(session: schema.Session, futures: Seq[FutSessContents], options: Seq[OptSessContents]) {

  val sessionId = SessionId(session.sess_id, session.opt_sess_id)

  def dispatch(trades: TradePayload*) {
    val futures = trades.filter(isFuture)
      .map(trade => FutureTrade(sessionId, futureIsinId(Isin(trade.security.isin)), trade))
      .map(_.asPlazaRecord)
      .map(r => StreamData(FutTrade.deal.TABLE_INDEX, r.getData))

    val options = trades.filter(isOption)
      .map(trade => OptionTrade(sessionId, optionIsinId(Isin(trade.security.isin)), trade))
      .map(_.asPlazaRecord)
      .map(r => StreamData(OptTrade.deal.TABLE_INDEX, r.getData))

    futTrade ! Dispatch(futures)
    optTrade ! Dispatch(options)
  }

  def toggleSession(session: schema.Session, futures: Seq[FutSessContents], options: Seq[OptSessContents]) = {
    new TradesService(futTrade, optTrade)(session, futures, options)
  }

  val isFuture: TradePayload => Boolean = mutableHashMapMemo {
    trade => futures.exists(_.isin == trade.security.isin)
  }

  val futureIsinId: Isin => IsinId = mutableHashMapMemo {
    isin =>
      IsinId(futures.find(_.isin == isin).get.isin_id)
  }

  val isOption: TradePayload => Boolean = mutableHashMapMemo {
    trade => options.exists(_.isin == trade.security.isin)
  }

  val optionIsinId: Isin => IsinId = mutableHashMapMemo {
    isin =>
      IsinId(options.find(_.isin == isin).get.isin_id)
  }

}