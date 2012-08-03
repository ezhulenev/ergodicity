package com.ergodicity.core.broker

import org.scalatest.WordSpec
import com.ergodicity.core.broker.Action.Buy
import com.ergodicity.core.common.OrderType.GoodTillCancelled
import com.ergodicity.core.common.Isin
import com.ergodicity.core.broker.Market.{Futures, Options}
import org.mockito.Mockito._
import org.mockito.Matchers._
import ru.micexrts.cgate.Publisher
import ru.micexrts.cgate.messages.DataMessage
import java.nio.ByteBuffer
import com.ergodicity.cgate.scheme.Message

class ActionSpec extends WordSpec {

  "Action" must {
    "support FutAddOrder" in {
      import Command._

      val dataMessage = mock(classOf[DataMessage])
      val publisher = mock(classOf[Publisher])
      when(publisher.newMessage(any(), any())).thenReturn(dataMessage)
      when(dataMessage.getData).thenReturn(ByteBuffer.allocate(1000))

      val buy = Buy[Futures](Isin("RTS-6.12"), 1, BigDecimal(100), GoodTillCancelled)
      val futAddOrder = new Message.FutAddOrder(buy.command.write(buy, publisher).getData)

      assert(futAddOrder.get_isin() == "RTS-6.12")
      assert(futAddOrder.get_amount() == 1)
    }
  }
}