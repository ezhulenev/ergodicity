package com.ergodicity.backtest

import java.util.concurrent.Executor

object CallingThreadExecutor extends Executor {
  def execute(command: Runnable) {
    command.run()
  }
}