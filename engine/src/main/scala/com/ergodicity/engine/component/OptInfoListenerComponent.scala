package com.ergodicity.engine.component

import ru.micexrts.cgate.{ISubscriber, Listener => CGListener}


trait OptInfoListenerComponent {
  def underlyingOptInfoListener: ISubscriber => CGListener
}


