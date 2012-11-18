package com.ergodicity.backtest

import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer

package object cgate {
  implicit def toAnswer[A](f: InvocationOnMock => A) = new Answer[A] {
    def answer(invocation: InvocationOnMock) = f(invocation)
  }
}
