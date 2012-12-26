package com.ergodicity.backtest

import com.ergodicity.marketdb.TimeSeries.Qualifier
import com.ergodicity.marketdb.iteratee.{TimeSeriesEnumerator, MarketDbReader}
import com.ergodicity.marketdb.mock.ScannerMock
import com.ergodicity.marketdb.model._
import com.ergodicity.marketdb.{TimeSeries, ByteArray}
import org.hbase.async.{Scanner, HBaseClient}
import org.joda.time.DateTime
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito._
import org.powermock.core.classloader.annotations.PowerMockIgnore
import org.powermock.core.classloader.annotations.PrepareForTest
import org.powermock.modules.junit4.PowerMockRunner
import org.scala_tools.time.Implicits._

@RunWith(classOf[PowerMockRunner])
@PowerMockIgnore(Array("javax.management.*", "javax.xml.parsers.*",
  "com.sun.org.apache.xerces.internal.jaxp.*", "ch.qos.logback.*", "org.slf4j.*"))
@PrepareForTest(Array(classOf[Scanner], classOf[HBaseClient]))
class BacktestIterateeTest {

  val NoSystem = true

  val market = Market("RTS")
  val security = Security("RTS 3.12")
  val time = new DateTime

  val today = new DateTime
  val interval = today.withHourOfDay(0) to today.withHourOfDay(23)

  implicit val marketId = (_: Market) => ByteArray(0)
  implicit val securityId = (_: Security) => ByteArray(1)

  @Test
  def testMarketPayloadDispatch() {
    val Count = 10

    val scanner1 = {
      val payloads = for (i <- 1 to Count) yield TradePayload(market, security, i, BigDecimal("111"), 1, time + i.second, NoSystem)
      ScannerMock(payloads, batchSize = 10)
    }

    val scanner2 = {
      val payloads = for (i <- 1 to Count) yield OrderPayload(market, security, i, time + 500.millis + i.second, 0, 0, 0, BigDecimal("222"), 1, 1, None)
      ScannerMock(payloads, batchSize = 15)
    }

    val qualifier1 = Qualifier(ByteArray("Trades").toArray, ByteArray("start").toArray, ByteArray("stop").toArray)
    val qualifier2 = Qualifier(ByteArray("Orders").toArray, ByteArray("start").toArray, ByteArray("stop").toArray)

    val timeSeries1 = new TimeSeries[TradePayload](market, security, interval, qualifier1)
    val timeSeries2 = new TimeSeries[OrderPayload](market, security, interval, qualifier2)

    // -- Prepare mocks
    implicit val reader = mock(classOf[MarketDbReader])
    val client = mock(classOf[HBaseClient])
    when(reader.client).thenReturn(client)
    when(client.newScanner(qualifier1.table)).thenReturn(scanner1)
    when(client.newScanner(qualifier2.table)).thenReturn(scanner2)

    // -- Iterate over time series
    import com.ergodicity.marketdb.model.TradeProtocol._
    import com.ergodicity.marketdb.model.OrderProtocol._
    val trades: TimeSeriesEnumerator[MarketPayload] = TimeSeriesEnumerator(timeSeries1, timeSeries2)

    val iterv = trades.enumerate(BacktestIteratee.dispatcher[MarketPayload])
    val report = iterv.map(_.run)()

    assert(report.trades == Count)
    assert(report.orders == Count)
  }
}