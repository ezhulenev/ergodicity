package com.ergodicity.cgate

import ru.micexrts.cgate.{State => CGState}


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