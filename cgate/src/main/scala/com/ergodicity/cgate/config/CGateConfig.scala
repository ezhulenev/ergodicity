package com.ergodicity.cgate.config

import java.io.File

case class CGateConfig(ini: File, key: String) {
  if (!ini.exists()) throw new IllegalArgumentException("CGate ini file doesn't exists")

  val config = "ini=" + ini.getAbsolutePath + ";key=" + key

  def apply() = config
}
