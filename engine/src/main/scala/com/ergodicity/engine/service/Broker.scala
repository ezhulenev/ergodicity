package com.ergodicity.engine.service


case object BrokerService extends Service

/*
trait Broker {
  engine: Engine with Connection with CreateListener =>

  def Broker: ActorRef
}

trait ManagedBroker extends CreateListener {
  engine: Engine with Connection with CreateListener =>

  def brokerName: String
  def underlyingPublisher: CGPublisher
  def underlyingPublisherConnection: CGConnection
  def underlyingRepliesConnection: CGConnection

  private[this] val PublisherConnection = context.actorOf(Props(new ErgodicityConnection(underlyingConnection)), "PublisherConnection")
  private[this] val RepliesConnection = context.actorOf(Props(new ErgodicityConnection(underlyingConnection)), "RepliesConnection")

  val Broker = context.actorOf(Props(new BrokerCore(BindPublisher(underlyingPublisher) to PublisherConnection)), "Broker")

  private[this] val underlyingRepliesListener = listener(underlyingRepliesConnection, Replies(brokerName)(), new ReplySubscriber(Broker))
  private[this] val replyListener = context.actorOf(Props(new ErgodicityListener(BindListener(underlyingRepliesListener) to RepliesConnection)), "RepliesListener")
}

protected[service] class BrokerManager(engine: Engine with ManagedBroker) extends Actor with WhenUnhandled with Stash {
  val log = Logging(context.system, self)

  protected def receive = null
}*/
