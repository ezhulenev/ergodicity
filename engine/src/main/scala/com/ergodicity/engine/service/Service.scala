package com.ergodicity.engine.service

import com.ergodicity.engine.Services.ServiceFailedException


trait ServiceId

object Service {

  case object Start

  case object Stop

}

trait Service {
  def serviceFailed(message: String)(implicit service: ServiceId): Nothing = {
    throw new ServiceFailedException(service, message)
  }
}

case class ServiceStarted(service: ServiceId)

case class ServiceStopped(service: ServiceId)






