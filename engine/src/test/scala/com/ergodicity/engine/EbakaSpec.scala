package com.ergodicity.engine

import org.scalatest.WordSpec
import org.slf4j.LoggerFactory

trait A {
  var message = ""

  def reg(msg: String) {
    message = message + msg
  }

  reg("A")

}

trait B {
  self : A =>

  reg("B")
}

trait C {
  self: A with B =>

  reg("C")
}

class Heraka

class EbakaSpec extends WordSpec {
  val log = LoggerFactory.getLogger(classOf[EbakaSpec])

  "Ebaka" must {
    "ebaka" in {

      val ebakaA = new Heraka with A
      log.info("Ebaka1 = "+ebakaA.message)

      val ebakaAB = new Heraka with A with B
      log.info("EbakaAB = "+ebakaAB.message)

      val ebakaABC = new Heraka with A with B with C
      log.info("EbakaABC = "+ebakaABC.message)

      val ebakaBC = new Heraka with A with C with B
      log.info("EbakaACB = "+ebakaBC.message)
    }
  }


}
