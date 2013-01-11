Ergodicity is built using [sbt](http://code.google.com/p/simple-build-tool/wiki/RunningSbt). To build:

    $ ./sbt package


# Ergodicity

Ergodicity is open source actor-based automated trading platform, providing tools for strategy-driven trading with the use of Level 1 and Level 2 market data. Ergodicity supports direct market-data connectivity to ["Russian Trading System" Stock Exchange](http://www.rts.ru/en/) using it's proprietary protocol [Plaza2](http://www.rts.ru/a22520/?nt=115) for real-time low-latency execution. Furthermore it provides tick-by-tick backtest engine for running the same strategies on historical data stored in [MarketDb](http://github.com/Ergodicity/marketdb) for performance analysis and optimization.

Platfrom uses Scala as it's primary language and relies on Event-Driven architecture using [Akka](http://akka.io/) Actors for concurrency and scalability.

## Ergodicity Projects and Packages

The [`MarketDb`](http://github.com/Ergodicity/marketdb) project is also a part of Ergodicity platfrom, and used for market data capture and strategies backtest

* `backtest` - strategies backtesting framework
* `capture` - market data capture using direct market connecitivty with RTS [CGate](http://ftp.rts.ru/pub/forts/) API
* `cgate`    - actor based abstraction over CGate
* `core`     - core components of platform: Order, OrderBook, Trade, Session etc...
* `engine`   - strategy execution engine
* `schema`   - shared project for `backtest` and `capture' with database schema
