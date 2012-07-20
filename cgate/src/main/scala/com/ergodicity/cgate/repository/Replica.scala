package com.ergodicity.cgate.repository

import com.ergodicity.cgate.scheme.FutInfo
import com.ergodicity.cgate.scheme.FutInfo.session

case class Replica(replID: Long, replRev: Long, replAct: Long)

trait ReplicaExtractor[T] {
  def apply(in: T): Replica

  def repl(in: T): Replica
}

object ReplicaExtractor {

  implicit val FutInfoSessionExtractor = new ReplicaExtractor[FutInfo.session] {

    def apply(in: session) = repl(in)

    def repl(in: session) = Replica(in.get_replID(), in.get_replRev(), in.get_replAct())
  }
}

