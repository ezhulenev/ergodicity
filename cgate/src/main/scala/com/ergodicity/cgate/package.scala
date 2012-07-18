package com.ergodicity

import ru.micexrts.cgate.{State => CGState}

package object cgate {

  // CGate Connection properties
  sealed trait ConnectionProtocol {
    def prefix: String
  }

  case object Tcp extends ConnectionProtocol {
    def prefix = "p2tcp://"
  }

  case object Lrpcq extends ConnectionProtocol {
    def prefix = "p2lrpcq://"
  }

  case class ConnectionProperties(protocol: ConnectionProtocol, host: String, port: Int, appName: String) {
    val connectionString = protocol.prefix + host + ":" + port + ";app_name=" + appName

    def apply() = connectionString
  }

  // Common CGate

  sealed trait State

  case object Closed extends State

  case object Error extends State

  case object Opening extends State

  case object Active extends State

  object State {
    def apply(i: Int) = i match {
      case CGState.CLOSED => Closed
      case CGState.ERROR => Error
      case CGState.OPENING => Opening
      case CGState.ACTIVE => Active
      case _ => throw new IllegalArgumentException("Illegal state value = " + i)
    }
  }

}