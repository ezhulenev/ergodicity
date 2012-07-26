package com.ergodicity.engine

import akka.actor.Props

trait Sessions {
  this: TradingEngine =>
  
  val Sessions = context.actorOf(Props(new com.ergodicity.core.Sessions(FutInfoStream,  OptInfoStream)))
  
  

}