package com.ergodicity.engine

import akka.actor._
import akka.util.duration._
import service.Service.{Start, Stop}
import service.{ServiceStopped, ServiceStarted, ServiceId}
import akka.actor.FSM.Normal
import com.ergodicity.engine.Services._
import com.ergodicity.engine.Services.ServicesStartupTimedOut
import collection.mutable
import scalax.collection.{GraphEdge, GraphPredef}
import GraphPredef._, GraphEdge._

sealed trait ServicesState

object ServicesState {

  case object Idle extends ServicesState

  case object Starting extends ServicesState

  case object Active extends ServicesState

  case object Stopping extends ServicesState

}

sealed trait ServicesData

object ServicesData {

  case object Blank extends ServicesData

  case class PendingServices(pending: Iterable[ServiceId]) extends ServicesData

}

object Services {

  // Commands
  case object StartAllServices

  case object StopAllServices

  // Possible failures

  case class ServicesStartupTimedOut(pending: Iterable[ServiceId]) extends RuntimeException

  class ServiceFailedException(service: ServiceId, message: String) extends RuntimeException

  class ServiceNotFoundException(service: ServiceId) extends RuntimeException

  // Services graph

  import scalax.collection.GraphEdge._

  class ManagedService(val id: ServiceId, val ref: ActorRef) {
    override def equals(obj: Any) = obj.isInstanceOf[ManagedService] && obj.asInstanceOf[ManagedService].id == id

    override def hashCode() = id.hashCode()

    override def toString = "ManagedService[" + id + "]"
  }

  class DependsOn[N](nodes: Product) extends DiEdge[N](nodes)

  object DependsOn {
    def apply(from: ManagedService, to: ManagedService) = new DependsOn[ManagedService](NodeProduct(from, to))

    def unapply(e: DependsOn[ManagedService]) = Some(e)
  }

  object DependsOnImplicits {
    @inline final implicit def edge2DependsOn[S <: ManagedService](e: DiEdge[S]) = new DependsOn[S](e)
  }

}

class Services extends Actor with LoggingFSM[ServicesState, ServicesData] {

  import ServicesState._
  import ServicesData._

  implicit val Self = this

  protected[engine] val services: scalax.collection.mutable.Graph[ManagedService, DependsOn] = scalax.collection.mutable.Graph[ManagedService, DependsOn]()

  // Stop all services on any failed
  override def supervisorStrategy() = AllForOneStrategy() {
    case _: ServiceFailedException => SupervisorStrategy.Stop
  }

  startWith(Idle, Blank)

  when(Idle) {
    case Event(StartAllServices, _) =>
      log.info("Start all services = " + services.nodes.map(_.id))
      services.nodes.foreach(_.ref ! Start)
      goto(Starting) using PendingServices(services.nodes.map(_.id))
  }

  when(Starting, stateTimeout = 30.seconds) {
    case Event(started@ServiceStarted(service), PendingServices(pending)) =>
      val remaining = pending.filterNot(_ == service)
      log.info("Service started = " + service + ", remaining = " + remaining)
      services.nodes.filter(_.id != service).foreach(_.ref ! started)
      remaining.size match {
        case 0 => goto(Active) using Blank
        case _ => stay() using PendingServices(remaining)
      }

    case Event(FSM.StateTimeout, PendingServices(pending)) => throw new ServicesStartupTimedOut(pending)
  }

  when(Active) {
    case Event(StopAllServices, _) =>
      log.info("Stop all services = " + services.nodes.map(_.id))
      services.nodes.foreach(_.ref ! Stop)
      goto(Stopping) using PendingServices(services.nodes.map(_.id))
  }

  when(Stopping) {
    case Event(stopped@ServiceStopped(service), PendingServices(pending)) =>
      services.nodes.filter(_.id != service).foreach(_.ref ! stopped)
      val remaining = pending.filterNot(_ == service)
      remaining.size match {
        case 0 => stop(Normal)
        case _ => stay() using PendingServices(remaining)
      }
  }

  override def preStart() {
    log.info("Registered services = " + services)
  }

  def serviceStarted(implicit service: ServiceId) {
    self ! ServiceStarted(service)
  }

  def serviceStopped(implicit service: ServiceId) {
    self ! ServiceStopped(service)
  }

  protected[engine] def register(ref: ActorRef, dependOn: Seq[ServiceId] = Seq())(implicit id: ServiceId) {
    log.info("Register service, Id = " + id + ", ref = " + ref + ", depends on = " + dependOn)
    val service = new ManagedService(id, ref)

    // Don't register service twice
    if (services.contains(service)) throw new IllegalArgumentException("Service with id = " + id + " has been already registered")

    // Register new service
    services += service
    dependOn foreach {
      dep =>
        val edge: Option[DependsOn[ManagedService]] = services.nodes.find(_.id == dep).map(from => DependsOn(from, service))
        edge.map(services add _).orElse {
          throw new IllegalStateException("Can't add dependency egde " + dep + " ~> " + id + ", as " + dep + " still not registered")
        }
    }
  }

  def service(id: ServiceId): ManagedService = services.nodes.find(_.id == id).getOrElse(throw new ServiceNotFoundException(id))
}
