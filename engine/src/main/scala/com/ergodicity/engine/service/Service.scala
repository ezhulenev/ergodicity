package com.ergodicity.engine.service


trait ServiceId

object Service {

  case object Start

  case object Stop

}

case class ServiceStarted(service: ServiceId)

case class ServiceStopped(service: ServiceId)






