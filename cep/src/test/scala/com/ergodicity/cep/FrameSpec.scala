package com.ergodicity.cep

import org.scalatest.WordSpec
import org.scala_tools.time.Implicits._
import org.joda.time.{Interval, DateTime}
import org.slf4j.LoggerFactory

class FrameSpec extends WordSpec {
  val log = LoggerFactory.getLogger(classOf[FrameSpec])

  "Frame" must {

    "accumulate events" in {
      val start = DateTime.now().withHourOfDay(0).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0);
      val end = start + 5.minutes
      val frame = Frame[TestPayload](new Interval(start, end))

      val payload1 = TestPayload(start + 1.second)
      val payload2 = TestPayload(start + 2.second)

      assert(frame <<< payload1 match {
        case Accumulate(_) => true
        case _ => false
      })

      assert(frame.<<<(payload2) match {
        case Accumulate(_) => true
        case _ => false
      })
    }

    "overflow by time" in {
      val start = DateTime.now().withHourOfDay(0).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0);
      val end = start + 5.minutes
      val frame = Frame[TestPayload](new Interval(start, end))

      val payload1 = TestPayload(start + 1.second)
      val payload2 = TestPayload(start + 6.minute)

      assert(frame <<< payload1 match {
        case Accumulate(_) => true
        case _ => false
      })

      assert(frame <<< payload2 match {
        case Overflow(_) => true
        case _ => false
      })
    }

    "should fail on bad ordering" in {
      val start = DateTime.now().withHourOfDay(0).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0);
      val end = start + 5.minutes

      val frame1 = Frame[TestPayload](new Interval(start, end))
      val payload1 = TestPayload(start + 7.second)

      val frame2 = (frame1 <<< payload1).frame
      val payload2 = TestPayload(start + 6.second)

      intercept[IllegalArgumentException] {
        frame2 <<< payload2
      }
    }

    "step over to sutiable frame interval" in {
      val start = DateTime.now().withHourOfDay(0).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0);
      val end = start + 5.minutes

      val frame = Frame[TestPayload](new Interval(start, end))
      val payload = TestPayload(start + 12.minutes)

      assert(frame <<< payload match {
        case Overflow(Frame(i, p)) if (i.start == start + 5.minutes) => true
        case _ => false
      })
    }
  }

}

case class TestPayload(time: DateTime) extends Payload