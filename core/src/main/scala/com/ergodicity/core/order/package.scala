package com.ergodicity.core

package object order {

  class OrdersTrackingException(message: String) extends RuntimeException(message)

  case class TrackSession(sessionId: Int)
  
  case class DropSession(sessionId: Int)
}