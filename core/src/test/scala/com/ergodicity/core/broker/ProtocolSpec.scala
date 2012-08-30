package com.ergodicity.core.broker

import org.scalatest.WordSpec
import com.ergodicity.cgate.scheme.Message
import java.nio.ByteBuffer


class ProtocolSpec extends WordSpec {

  "Protocol" must {
    "handle errors" in {
      val protocol = com.ergodicity.core.broker.Protocol.FutAddOrder

      val errorMessage = new Message.FORTS_MSG100(ByteBuffer.allocate(1000))
      errorMessage.set_message("Error")

      intercept[BrokerErrorException] {
        protocol.deserialize(Message.FORTS_MSG100.MSG_ID, errorMessage.getData)
      }
    }

    "handle flood" in {
      val protocol = com.ergodicity.core.broker.Protocol.FutAddOrder

      val errorMessage = new Message.FORTS_MSG99(ByteBuffer.allocate(1000))
      errorMessage.set_queue_size(50)
      errorMessage.set_penalty_remain(100)
      errorMessage.set_message("Flood")

      intercept[FloodException] {
        protocol.deserialize(Message.FORTS_MSG99.MSG_ID, errorMessage.getData)
      }
    }

    "fail on unexpected message" in {
      val protocol = com.ergodicity.core.broker.Protocol.FutAddOrder

      val errorMessage = new Message.FORTS_MSG111(ByteBuffer.allocate(1000))
      errorMessage.set_message("Error")

      intercept[MatchError] {
        protocol.deserialize(Message.FORTS_MSG111.MSG_ID, errorMessage.getData)
      }
    }
  }

  "FutAddOrder" must {
    "handle order response" in {
      val protocol = com.ergodicity.core.broker.Protocol.FutAddOrder

      val errorMessage = new Message.FORTS_MSG101(ByteBuffer.allocate(1000))
      errorMessage.set_order_id(1111)

      val res = protocol.deserialize(Message.FORTS_MSG101.MSG_ID, errorMessage.getData)

      assert(res == OrderId(1111))
    }
  }

  "OptAddOrder" must {
    "handle order response" in {
      val protocol = com.ergodicity.core.broker.Protocol.OptAddOrder

      val errorMessage = new Message.FORTS_MSG109(ByteBuffer.allocate(1000))
      errorMessage.set_order_id(1111)

      val res = protocol.deserialize(Message.FORTS_MSG109.MSG_ID, errorMessage.getData)

      assert(res == OrderId(1111))
    }
  }
}