package com.ergodicity.engine.service

trait Service

object Service {

  case object Start

  case object Stop

}

case class ServiceActivated(service: Service)

case class ServicePassivated(service: Service)






