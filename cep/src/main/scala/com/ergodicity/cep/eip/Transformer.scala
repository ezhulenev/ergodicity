package com.ergodicity.cep.eip

import akka.actor.{ActorRef, Actor}

class Transformer(target:ActorRef, transform: Any => Any) extends Actor {
  protected def receive = {
    case m => target ! transform(m)
  }
}