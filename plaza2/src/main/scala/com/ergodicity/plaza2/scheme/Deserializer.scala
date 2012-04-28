package com.ergodicity.plaza2.scheme

import plaza2.{Record => P2Record}

trait Deserializer[T <: Record] {
  def apply(record: P2Record): T
}

