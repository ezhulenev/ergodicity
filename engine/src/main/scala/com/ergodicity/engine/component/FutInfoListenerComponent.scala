package com.ergodicity.engine.component

import ru.micexrts.cgate.{ISubscriber, Listener => CGListener}


trait FutInfoListenerComponent {
  def underlyingFutInfoListener: ISubscriber => CGListener
}

