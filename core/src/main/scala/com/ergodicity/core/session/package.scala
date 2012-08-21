package com.ergodicity.core

import com.ergodicity.cgate._
import scheme.FutInfo.fut_sess_contents
import scheme.OptInfo.opt_sess_contents
import scheme.{OptInfo, FutInfo}

package object session {

  trait RichFutInfoContents {
    def isFuture: Boolean

    def isin: Isins
  }

  trait RichOptInfoContents {
    def isin: Isins
  }

  sealed trait ToSecurity[T] {
    def convert(record: T): Security
  }

  object Implicits {
    implicit def enrichOptInfoContents(contents: OptInfo.opt_sess_contents) = new RichOptInfoContents {
      def isin = Isins(contents.get_isin_id(), contents.get_isin().trim, contents.get_short_isin().trim)
    }

    implicit def enrichFutInfoContents(contents: FutInfo.fut_sess_contents) = new RichFutInfoContents {
      def isFuture = {
        val signs = com.ergodicity.cgate.Signs(contents.get_signs())
        !signs.spot && !signs.moneyMarket && signs.anonymous
      }

      def isin = Isins(contents.get_isin_id(), contents.get_isin().trim, contents.get_short_isin().trim)
    }

    implicit val FutInfoToFuture = new ToSecurity[FutInfo.fut_sess_contents] {
      def convert(record: fut_sess_contents) = {
        new FutureContract(enrichFutInfoContents(record).isin, record.get_name().trim)
      }
    }

    implicit val OptInfoToOption = new ToSecurity[OptInfo.opt_sess_contents] {
      def convert(record: opt_sess_contents) =
        new OptionContract(enrichOptInfoContents(record).isin, record.get_name().trim)
    }
  }


}
