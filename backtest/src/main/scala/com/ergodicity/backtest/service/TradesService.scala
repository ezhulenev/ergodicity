package com.ergodicity.backtest.service

import akka.actor.ActorRef
import com.ergodicity.backtest.cgate.ReplicationStreamListenerStubActor.DispatchData
import com.ergodicity.backtest.service.TradesService.{OptionTrade, FutureTrade}
import com.ergodicity.cgate.StreamEvent.StreamData
import com.ergodicity.cgate.scheme.{OptTrade, FutTrade}
import com.ergodicity.core.{IsinId, SessionId}
import com.ergodicity.marketdb.model.TradePayload

object TradesService {

  sealed trait Trade

  case class FutureTrade(session: SessionId, id: IsinId, underlying: TradePayload)

  case class OptionTrade(session: SessionId, id: IsinId, underlying: TradePayload)

  implicit def futureTrade2plaza(trade: FutureTrade) = new {

    import scalaz.Scalaz._

    def asPlazaRecord = {
      val buff = allocate(Size.FutTrade)
      val cgate = new FutTrade.deal(buff)

      cgate.set_sess_id(trade.session.fut)
      cgate.set_isin_id(trade.id.id)
      cgate.set_id_deal(trade.underlying.tradeId)
      cgate.set_price(trade.underlying.price)
      cgate.set_amount(trade.underlying.amount)
      cgate.set_moment(trade.underlying.time.getMillis)
      cgate.set_nosystem(trade.underlying.nosystem ? 1.toByte | 0.toByte)

      cgate
    }
  }

  implicit def optionTrade2plaza(trade: OptionTrade) = new {

    import scalaz.Scalaz._

    def asPlazaRecord = {
      val buff = allocate(Size.OptTrade)
      val cgate = new OptTrade.deal(buff)

      cgate.set_sess_id(trade.session.fut)
      cgate.set_isin_id(trade.id.id)
      cgate.set_id_deal(trade.underlying.tradeId)
      cgate.set_price(trade.underlying.price)
      cgate.set_amount(trade.underlying.amount)
      cgate.set_moment(trade.underlying.time.getMillis)
      cgate.set_nosystem(trade.underlying.nosystem ? 1.toByte | 0.toByte)

      cgate
    }
  }
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