package com.ergodicity.backtest

import java.nio.ByteBuffer

trait Writes[T] {
  def apply(obj: T): ByteBuffer = write(obj)

  def write(obj: T): ByteBuffer
}

object Protocol {

}