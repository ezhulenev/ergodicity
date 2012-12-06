package com.ergodicity.backtest

import akka.actor.{Props, ActorSystem}
import com.ergodicity.backtest.cgate._
import com.ergodicity.engine.Listener._
import com.ergodicity.engine.{StrategyEngineActor, Engine, ServicesActor}
import com.ergodicity.engine.service._
import com.ergodicity.engine.underlying.{UnderlyingPublisher, UnderlyingTradingConnections, UnderlyingConnection}
import com.ergodicity.engine.strategy.{StrategiesFactory, CoverAllPositions}

trait Connections extends UnderlyingConnection with UnderlyingTradingConnections {
  self: BacktestEngine =>
  lazy val underlyingConnection = ConnectionStub wrap connectionStub

  lazy val underlyingTradingConnection = ConnectionStub wrap tradingConnectionStub
}

trait Listeners extends FutInfoListener with OptInfoListener with FutOrdersListener with OptOrdersListener with FutTradesListener with OptTradesListener with FutOrderBookListener with OptOrderBookListener with OrdLogListener with PosListener with RepliesListener {
  self: BacktestEngine =>

  lazy val repliesListener = ListenerBindingStub wrap repliesListenerStub

  lazy val futInfoListener = ListenerBindingStub wrap futInfoListenerStub
  lazy val optInfoListener = ListenerBindingStub wrap optInfoListenerStub
  lazy val futOrdersListener = ListenerBindingStub wrap futOrdersListenerStub
  lazy val optOrdersListener = ListenerBindingStub wrap optOrdersListenerStub
  lazy val futTradesListener = ListenerBindingStub wrap futTradesListenerStub
  lazy val optTradesListener = ListenerBindingStub wrap optTradesListenerStub
  lazy val futOrderbookListener = ListenerBindingStub wrap futOrderBookListenerStub
  lazy val optOrderbookListener = ListenerBindingStub wrap optOrderBookListenerStub
  lazy val ordLogListener = ListenerBindingStub wrap ordLogListenerStub
  lazy val posListener = ListenerBindingStub wrap posListenerStub
}

trait Publisher extends UnderlyingPublisher {
  self: BacktestEngine =>

  val publisherName = "BacktestPublisher"
  val brokerCode = "000"

  lazy val underlyingPublisher = PublisherStub wrap publisherStub
}

abstract class BacktestEngine(system: ActorSystem) extends Engine with Connections with Listeners with Publisher {

  import system._

  // Connections stubs
  val connectionStub = actorOf(Props(new ConnectionStubActor), "ConnectionStub")
  val tradingConnectionStub = actorOf(Props(new ConnectionStubActor), "TradingConnectionStub")

  // Replication streams stubs
  val futInfoListenerStub = actorOf(Props(new ReplicationStreamListenerStubActor), "FutInfoListenerStub")
  val optInfoListenerStub = actorOf(Props(new ReplicationStreamListenerStubActor), "OptInfoListenerStub")
  val futTradesListenerStub = actorOf(Props(new ReplicationStreamListenerStubActor), "FutTradesStub")
  val optTradesListenerStub = actorOf(Props(new ReplicationStreamListenerStubActor), "OptTradesStub")
  val futOrdersListenerStub = actorOf(Props(new ReplicationStreamListenerStubActor), "FutOrdersStub")
  val optOrdersListenerStub = actorOf(Props(new ReplicationStreamListenerStubActor), "OptOrdersStub")
  val futOrderBookListenerStub = actorOf(Props(new ReplicationStreamListenerStubActor), "FutOrderBookListener")
  val optOrderBookListenerStub = actorOf(Props(new ReplicationStreamListenerStubActor), "OptOrderBookListener")
  val ordLogListenerStub = actorOf(Props(new ReplicationStreamListenerStubActor), "OrdLogListenerStub")
  val posListenerStub = actorOf(Props(new ReplicationStreamListenerStubActor), "PosListenerStub")

  // Publisher and replies stream stubs
  val repliesListenerStub = actorOf(Props(new ReplyStreamListenerStubActor), "RepliesListenerStub")
  val publisherStub = actorOf(Props(new PublisherStubActor()), "PublisherStub")


  def strategies: StrategiesFactory

  // Services & Strategies
  implicit lazy val underlyingServices = new BacktestServices(this)
  lazy val Services = actorOf(Props(underlyingServices), "Services")
  lazy val Strategies = actorOf(Props(new StrategyEngineActor(strategies)), "StrategiesEngine")
}

class BacktestServices(val engine: BacktestEngine) extends ServicesActor with ReplicationConnection with TradingConnection with InstrumentData with Portfolio with TradesData with OrdersData with Trading