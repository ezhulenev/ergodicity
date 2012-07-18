package com.ergodicity.cgate.config

sealed trait SubscriptionType {
  def prefix: String

  def config: String
}

case class Replication(stream: String) extends SubscriptionType {
  val prefix = "p2repl://"

  def config = prefix + stream
}

object Replication {

  sealed trait ReplicationMode {
    def name: String
  }

  object ReplicationMode {

    case object Snapshot extends ReplicationMode {
      def name = "snapshot"
    }

    case object Online extends ReplicationMode {
      def name = "online"
    }

    case object Combined extends ReplicationMode {
      def name = "snapshot+online"
    }

  }

}

