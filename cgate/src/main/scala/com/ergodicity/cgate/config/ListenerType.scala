package com.ergodicity.cgate.config

import java.io.File
import com.ergodicity.cgate.StreamEvent.ReplState

sealed trait ListenerType {
  def config: String

  def apply(): String = config
}

sealed trait ListenerOpenParams {
  def config: String
}

case class Replication(stream: String, ini: File, scheme: String) extends ListenerType {
  if (!ini.exists()) throw new IllegalArgumentException("Scheme ini file doesn't exists = " + ini)

  val prefix = "p2repl://"

  val config = prefix + stream + ";scheme=|FILE|" + ini.getAbsolutePath + "|" + scheme
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

  case class ReplicationParams(mode: ReplicationMode, state: Option[ReplState] = None) extends ListenerOpenParams {
    private val modeParam = "mode=" + mode.name
    val config = state.map(modeParam + ";replstate=" + _.state).getOrElse(modeParam)
  }

}

