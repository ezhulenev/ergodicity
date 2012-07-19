package com.ergodicity.cgate.config

import java.io.File

sealed trait SubscriptionType {
  def prefix: String

  def config: String
}

case class Replication(stream: String, scheme: File) extends SubscriptionType {
  if (!scheme.exists()) throw new IllegalArgumentException("Scheme config file doesn't exists")

  val prefix = "p2repl://"

  def config = prefix + stream+";scheme="+scheme.getAbsolutePath
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

