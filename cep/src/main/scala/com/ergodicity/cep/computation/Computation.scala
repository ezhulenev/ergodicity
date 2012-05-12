package com.ergodicity.cep.computation

trait Computation[A, B] {
  def apply(): B
  def apply(el: A): Computation[A, B]
  
  def reset(): Computation[A, B]
}

case class Counter[T](cnt: Int) extends Computation[T, Int] {
  def apply() = cnt

  def apply(el: T) = Counter(cnt + 1)

  def reset() = Counter(0)
}