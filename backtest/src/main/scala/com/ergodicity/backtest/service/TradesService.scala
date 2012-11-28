package com.ergodicity.backtest.service

import akka.actor.ActorRef
import com.ergodicity.backtest.cgate.DataStreamListenerStubActor.DispatchData
import com.ergodicity.backtest.service.TradesService.{OptionTrade, FutureTrade}
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.cgate.scheme.{OptTrade, FutTrade}
import com.ergodicity.core.{IsinId, SessionId}
import com.ergodicity.marketdb.model.TradePayload

object TradesService {

  sealed trait Trade

  case class FutureTrade(session: SessionId, id: IsinId, underlying: TradePayload)

  case class OptionTrade(session: SessionId, id: IsinId, underlying: TradePayload)

}

class TradesService(futTrade: ActorRef, optTrade: ActorRef)(implicit context: SessionContext) {

  val sessionId = SessionId(context.session.sess_id, context.session.opt_sess_id)

  def dispatch(trades: TradePayload*) {
    val futures = trades
      .filter(trade => context.isFuture(trade.security))
      .map(trade => FutureTrade(sessionId, context.isinId(trade.security).get, trade))
      .map(_.asPlazaRecord)
      .map(r => StreamData(FutTrade.deal.TABLE_INDEX, r.getData))

    val options = trades
      .filter(trade => context.isOption(trade.security))
      .map(trade => OptionTrade(sessionId, context.isinId(trade.security).get, trade))
      .map(_.asPlazaRecord)
      .map(r => StreamData(OptTrade.deal.TABLE_INDEX, r.getData))

    futTrade ! DispatchData(futures)
    optTrade ! DispatchData(options)
  }

}