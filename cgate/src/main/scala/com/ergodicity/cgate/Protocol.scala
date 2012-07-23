package com.ergodicity.cgate

import java.nio.ByteBuffer
import scheme.{OptInfo, FutInfo}

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

  implicit val ReadsOptInfoSessionContents = new Reads[OptInfo.opt_sess_contents] {
    def read(in: ByteBuffer) = new OptInfo.opt_sess_contents(in)
  }

}