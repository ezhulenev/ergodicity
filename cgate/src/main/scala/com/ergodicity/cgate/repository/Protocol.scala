package com.ergodicity.cgate.repository

import com.ergodicity.cgate.scheme.FutInfo
import java.nio.ByteBuffer

trait Reads[T] {
  def apply(in: ByteBuffer): T

  def read(in: ByteBuffer): T
}

object Protocol {

  implicit val ReadsFutInfoSessions = new Reads[FutInfo.session] {

    def apply(in: ByteBuffer) = read(in)

    def read(in: ByteBuffer) = new FutInfo.session(in)
  }

}