package com.ergodicity.cgate

import ru.micexrts.cgate.ISubscriber
import ru.micexrts.cgate.messages.Message
import ru.micexrts.cgate.{Connection => CGConnection, Listener => CGListener}
import java.nio.{ByteOrder, ByteBuffer}

trait Subscriber extends ISubscriber {
  def onMessage(p1: CGConnection, p2: CGListener, p3: Message) = handleMessage(p3)

  def handleMessage(msg: Message): Int

  def clone(original: ByteBuffer) = {
    val clone = ByteBuffer.allocate(original.capacity())
    clone.order(ByteOrder.nativeOrder())
    original.rewind()
    clone.put(original)
    clone
  }
}
