package com.ergodicity.cgate.config

import java.io.File

sealed trait ListenerType {
  def config: String
}

sealed trait ListenerOpenParams {
  def config: String
}

class Replication(stream: String, ini: File, scheme: String) extends ListenerType {
  if (!ini.exists()) throw new IllegalArgumentException("Scheme ini file doesn't exists")

  val prefix = "p2repl://"

  def config = prefix + stream + ";scheme=|FILE|" + ini.getAbsolutePath + "|" + scheme
}

object Replication {

  sealed trait ReplicationMode {
    def name: String
  }

  object ReplicationMode {

    object Snapshot extends ReplicationMode {
      def name = "snapshot"
    }

    object Online extends ReplicationMode {
      def name = "online"
    }

    object Combined extends ReplicationMode {
      def name = "snapshot+online"
    }

  }

  class ReplicationParams(mode: ReplicationMode, state: Option[String] = None) extends ListenerOpenParams {
    private val modeParam = "mode=" + mode.name
    val config = state.map(modeParam + ";replstate=" + _).getOrElse(modeParam)
  }

}

