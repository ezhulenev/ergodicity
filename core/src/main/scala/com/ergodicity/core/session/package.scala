package com.ergodicity.core

import com.ergodicity.cgate._
import scheme.FutInfo.fut_sess_contents
import scheme.OptInfo.opt_sess_contents
import scheme.{OptInfo, FutInfo}

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

  sealed trait ToSecurity[T] {
    def convert(record: T): Security
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

    implicit val FutInfoToFuture = new ToSecurity[FutInfo.fut_sess_contents] {
      def convert(record: fut_sess_contents) = {
        val enriched = enrichFutInfoContents(record)
        new FutureContract(enriched.id, enriched.isin, enriched.shortIsin, record.get_name().trim)
      }
    }

    implicit val OptInfoToOption = new ToSecurity[OptInfo.opt_sess_contents] {
      def convert(record: opt_sess_contents) = {
        val enriched = enrichOptInfoContents(record)
        new OptionContract(enriched.id, enriched.isin, enriched.shortIsin, record.get_name().trim)
      }
    }
  }
}
