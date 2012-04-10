package com.ergodicity.engine.plaza2.protocol

import plaza2.{Record => P2Record}

trait Deserializer[T <: Record] {
  def apply(record: P2Record): T
}

