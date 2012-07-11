package com.ergodicity.engine.component

import plaza2.MessageFactory
import java.io.File

trait MessageFactoryComponent {
  implicit def messageFactory: MessageFactory
}

trait MessagesFromScheme extends MessageFactoryComponent {

  def messagesScheme: File
  implicit lazy val messageFactory = MessageFactory(messagesScheme)
}