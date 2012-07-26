package com.ergodicity.engine.component

import ru.micexrts.cgate.{ISubscriber, Listener => CGListener}


trait PosListenerComponent {
  def underlyingPosListener: ISubscriber => CGListener
}