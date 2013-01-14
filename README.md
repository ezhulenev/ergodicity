Ergodicity is built using [sbt](http://code.google.com/p/simple-build-tool/wiki/RunningSbt). To build:

    $ ./sbt package


# Ergodicity

## What is Ergodicity

Ergodicity is open source actor-based automated trading platform, providing tools for strategy-driven trading with the use of Level 1 and Level 2 market data. Ergodicity supports direct market-data connectivity to ["Russian Trading System" Stock Exchange](http://www.rts.ru/en/) using it's proprietary protocol [Plaza2](http://www.rts.ru/a22520/?nt=115) for real-time low-latency execution. Furthermore it provides tick-by-tick backtest engine for running the same strategies on historical data stored in [MarketDb](http://github.com/Ergodicity/marketdb) for performance analysis and optimization.

Platfrom uses Scala as it's primary language and relies on Event-Driven architecture using [Akka](http://akka.io/) Actors for concurrency and scalability.

## Projects and Packages

The [`MarketDb`](http://github.com/Ergodicity/marketdb) project is also a part of Ergodicity platfrom, and used for market data capture and strategies backtest

* `backtest` - strategies backtesting framework
* `capture` - market data capture using direct market connecitivty with RTS [CGate](http://ftp.rts.ru/pub/forts/) API
* `cgate`    - actor based abstraction over CGate
* `core`     - core components of platform: Order, OrderBook, Trade, Session etc...
* `engine`   - strategy execution engine
* `schema`   - shared project for `backtest` and `capture' with database schema

## Main Features

Ergodicity is built using Event-Driven architecture. Each market data received from Stock Exchange (trade, add order, cancel order, session updates, etc.) considered as an event. Core components of a platform presented as Actors: Trading session, each stock assigned for session, each order, etc. Akka as a backbone for the platform allows to take advantage of this approach, and build platform with fault tolerance and high scalability in it's nature.

##### Example of Order Actor

        sealed trait OrderState

        object OrderState {
        
          case object Active extends OrderState
        
          case object Filled extends OrderState
        
          case object Cancelled extends OrderState
        
        }

        class OrderActor(val order: Order) extends Actor with FSM[OrderState, Trace] {
        
          import OrderState._
        
          var subscribers = Set[ActorRef]()
        
          startWith(Active, Trace(order.amount, Create(order) :: Nil))
        
          when(Active) {
            case Event(fill@Fill(amount, rest, _), Trace(restAmount, actions)) =>
              dispatch(fill)
              if (restAmount - amount != rest)
                throw new IllegalOrderEvent("Rest amounts doesn't match", fill)
        
              if (restAmount - amount == 0)
                goto(Filled) using Trace(0, actions :+ fill)
              else stay() using Trace(restAmount - amount, actions :+ fill)
          }
        
          when(Filled) {
            case Event(e@Fill(_, _, _), _) => throw new IllegalOrderEvent("Order already Filled", e)
          }
        
          when(Cancelled) {
            case e => throw new IllegalOrderEvent("Order already Cancelled", e)
          }
        
          whenUnhandled {
            case Event(cancel@Cancel(cancelAmount), Trace(restAmount, actions)) =>
              dispatch(cancel)
              goto(Cancelled) using Trace(restAmount - cancelAmount, actions :+ cancel)
        
            case Event(SubscribeOrderEvents(ref), trace) =>
              trace.actions foreach (ref ! OrderEvent(order, _))
              subscribers = subscribers + ref
              stay()
          }
        
          onTransition {
            case Active -> Filled => log.debug("Order filled")
            case Active -> Cancelled => log.debug("Order cancelled")
          }
        
          private def dispatch(action: Action) {
            subscribers foreach (_ ! OrderEvent(order, action))
          }
        }
        

## CGate

In order to compile Ergodicity projects it's required to install CGate library. Windows and linux version are available on [RTS server](http://ftp.rts.ru/pub/forts/test/CGate/). After installation you should add `$CGATE_HOME/p2bin` directory into `$Path` variable to provide access to code generation tool.

Refer `project/SchemeTools.scala` for details.
