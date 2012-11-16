package com.ergodicity.cgate.config

import java.io.File
import com.ergodicity.cgate.StreamEvent.ReplState

trait ListenerConfig {
  def config: String

  def apply(): String = config
}

sealed trait ListenerOpenParams {
  def config: String

  def apply(): String = config
}

case class Replication(stream: String, ini: File, scheme: String) extends ListenerConfig {
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

  case class ReplicationParams(mode: ReplicationMode, revisions: Map[String, Long] = Map(), state: Option[ReplState] = None) extends ListenerOpenParams {
    private val modeParam = "mode=" + mode.name
    val config = {
      var conf = modeParam
      revisions.foreach {
        case (table, revision) => conf = conf + ";rev." + table + "=" + revision
      }
      if (state.isDefined) conf = conf + ";replstate=" + state.get.state
      conf
    }
  }

  case object EmptyParams extends ListenerOpenParams {
    val config = ""
  }

}


case class Replies(ref: String) extends ListenerConfig {
  val prefix = "p2mqreply://"

  val config = prefix + ";ref=" + ref
}

object Replies {

  case object RepliesParams extends ListenerOpenParams {
    def config = ""
  }

}
