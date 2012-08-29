package com.ergodicity.engine.service

import com.ergodicity.engine.Services.ServiceFailedException


trait ServiceId

object Service {

  sealed trait Action

  case object Start extends Action

  case object Stop extends Action

}

trait Service {
  def failed(message: String)(implicit service: ServiceId): Nothing = {
    throw new ServiceFailedException(service, message)
  }
}




