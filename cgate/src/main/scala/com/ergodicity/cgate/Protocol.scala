package com.ergodicity.cgate

import com.ergodicity.cgate.scheme.FutInfo
import java.nio.ByteBuffer

trait Reads[T] {
  def apply(in: ByteBuffer): T = read(in)

  def read(in: ByteBuffer): T
}

object Protocol {

  implicit val ReadsFutInfoSessions = new Reads[FutInfo.session] {
    def read(in: ByteBuffer) = new FutInfo.session(in)
  }
  
  implicit val ReadsFutInfoSessionContents = new Reads[FutInfo.fut_sess_contents] {
    def read(in: ByteBuffer) = new FutInfo.fut_sess_contents(in)
  }

}