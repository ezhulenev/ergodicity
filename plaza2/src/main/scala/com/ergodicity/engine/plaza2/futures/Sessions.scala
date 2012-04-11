package com.ergodicity.engine.plaza2.futures

import akka.actor.{Props, FSM, Actor, ActorRef}
import com.ergodicity.engine.plaza2.scheme.SessionRecord
import com.ergodicity.engine.plaza2.scheme.FutInfo._
import com.ergodicity.engine.plaza2.DataStream.JoinTable
import com.ergodicity.engine.plaza2.{DataStreamState, Repository}
import akka.actor.FSM.{CurrentState, Transition, SubscribeTransitionCallBack}


sealed trait SessionsState
object SessionsState {
  def apply(dataStream: ActorRef) = new Sessions(dataStream)
  case object Idle extends SessionsState
  case object Reopen extends SessionsState
  case object Online extends SessionsState
}


class Sessions(dataStream: ActorRef) extends Actor with FSM[SessionsState, Seq[(Long, ActorRef)]] {
  import SessionsState._

  // Create repository and join it to DataStream
  val repository = context.actorOf(Props(Repository[SessionRecord]))
  dataStream ! JoinTable(repository, "session")
  dataStream ! SubscribeTransitionCallBack(self)

  startWith(Idle, Seq())

  when(Idle) {
    case Event(Transition(_ , _, DataStreamState.Online), _) => goto(Online)
  }

  onTransition {
    case Idle -> Online => log.info("Sessions tracker goes online")
    case from -> to => log.info("Session transition = " + from + " -> " + to)
  }

  whenUnhandled {
    case Event(CurrentState(_, _), _) => stay()
  }

  initialize
}