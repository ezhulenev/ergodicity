package com.ergodicity.engine.plaza2.protocol

import plaza2.{Record => P2Record}

trait Record {
  def replID: Long
  def replRev: Long
  def replAct: Long
}