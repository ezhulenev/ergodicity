package com.ergodicity.engine.config

import ru.micexrts.cgate.{ISubscriber, Listener => CGListener}

trait ListenersComponent {
  def underlyingFutInfoListener: ISubscriber => CGListener
  def underlyingOptInfoListener: ISubscriber => CGListener
  def underlyingPosListener: ISubscriber => CGListener
}