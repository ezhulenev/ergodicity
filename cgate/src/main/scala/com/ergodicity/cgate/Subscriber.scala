package com.ergodicity.cgate

import java.nio.{ByteOrder, ByteBuffer}
import ru.micexrts.cgate.messages.Message
import ru.micexrts.cgate.ISubscriber

object Subscriber {
  implicit def toSubscriber(s: Subscriber) = new ISubscriber {

    import ru.micexrts.cgate.{Connection => CGConnection, Listener => CGListener}
    import ru.micexrts.cgate.messages.{Message => CGMessage}

    def onMessage(connection: CGConnection, listener: CGListener, msg: CGMessage) = s.handleMessage(msg)
  }
}

trait Subscriber {
  def handleMessage(msg: Message): Int

  def clone(original: ByteBuffer) = {
    val clone = ByteBuffer.allocate(original.capacity())
    clone.order(ByteOrder.nativeOrder())
    original.rewind()
    clone.put(original)
    clone
  }
}
