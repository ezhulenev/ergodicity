package com.ergodicity.core

package object order {

  case class BindSessionOrders(sessionId: Int)
  
  case class DropSessionOrders(sessionId: Int)
}