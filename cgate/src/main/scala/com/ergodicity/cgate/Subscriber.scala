package com.ergodicity.cgate

import ru.micexrts.cgate.ISubscriber
import ru.micexrts.cgate.messages.Message
import ru.micexrts.cgate.{Connection => CGConnection, Listener => CGListener}

trait Subscriber extends ISubscriber {
  def onMessage(p1: CGConnection, p2: CGListener, p3: Message) = handleMessage(p3)

  def handleMessage(msg: Message): Int
}
