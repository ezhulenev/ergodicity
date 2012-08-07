package com.ergodicity.engine.service

trait Service

object Service {

  case object Start

  case object Stop

}

case class ServiceStarted(service: Service)

case class ServiceStopped(service: Service)






