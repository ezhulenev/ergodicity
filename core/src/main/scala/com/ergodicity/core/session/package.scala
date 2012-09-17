package com.ergodicity.core

import com.ergodicity.cgate._
import scheme.FutInfo.fut_sess_contents
import scheme.OptInfo.opt_sess_contents
import scheme.{OptInfo, FutInfo}
import session.InstrumentParameters.{Limits, OptionParameters, FutureParameters}

package object session {

  trait RichFutInfoContents {
    def isFuture: Boolean

    def id: IsinId

    def isin: Isin

    def shortIsin: ShortIsin
  }

  trait RichOptInfoContents {
    def id: IsinId

    def isin: Isin

    def shortIsin: ShortIsin
  }

  sealed trait ToInstrument[T, S <: Security, P <: InstrumentParameters] {
    def security(record: T): S
    def parameters(record: T): P
  }

  object Implicits {
    implicit def enrichOptInfoContents(contents: OptInfo.opt_sess_contents) = new RichOptInfoContents {

      def id = IsinId(contents.get_isin_id())

      def isin = Isin(contents.get_isin().trim)

      def shortIsin = ShortIsin(contents.get_short_isin().trim)
    }

    implicit def enrichFutInfoContents(contents: FutInfo.fut_sess_contents) = new RichFutInfoContents {
      def isFuture = {
        val signs = com.ergodicity.cgate.Signs(contents.get_signs())
        !signs.spot && !signs.moneyMarket && signs.anonymous
      }

      def id = IsinId(contents.get_isin_id())

      def isin = Isin(contents.get_isin().trim)

      def shortIsin = ShortIsin(contents.get_short_isin().trim)
    }

    implicit val FutureInstrument = new ToInstrument[FutInfo.fut_sess_contents, FutureContract, FutureParameters] {
      def security(record: fut_sess_contents) = {
        val enriched = enrichFutInfoContents(record)
        new FutureContract(enriched.id, enriched.isin, enriched.shortIsin, record.get_name().trim)
      }

      def parameters(record: fut_sess_contents) = FutureParameters(record.get_last_cl_quote(), Limits(record.get_limit_down(), record.get_limit_up()))
    }

    implicit val OptionInstrument = new ToInstrument[OptInfo.opt_sess_contents, OptionContract, OptionParameters] {
      def security(record: opt_sess_contents) = {
        val enriched = enrichOptInfoContents(record)
        new OptionContract(enriched.id, enriched.isin, enriched.shortIsin, record.get_name().trim)
      }

      def parameters(record: opt_sess_contents) = OptionParameters(record.get_last_cl_quote())
    }
  }
}
