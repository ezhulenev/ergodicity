package com.ergodicity.cgate.repository

import com.ergodicity.cgate.scheme.FutInfo
import com.ergodicity.cgate.scheme.FutInfo.{fut_sess_contents, session}

case class Replica(replID: Long, replRev: Long, replAct: Long)

trait ReplicaExtractor[T] {
  def apply(in: T): Replica = repl(in)

  def repl(in: T): Replica
}

object ReplicaExtractor {

  implicit val FutInfoSessionExtractor = new ReplicaExtractor[FutInfo.session] {
    def repl(in: session) = Replica(in.get_replID(), in.get_replRev(), in.get_replAct())
  }
  
  implicit val FutInfoSessionContentsExtractor = new ReplicaExtractor[FutInfo.fut_sess_contents] {
    def repl(in: fut_sess_contents) = Replica(in.get_replID(), in.get_replRev(), in.get_replAct())
  }
}

