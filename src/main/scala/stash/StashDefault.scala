package stash

import akka.typed.ActorRef
import stash.StashType.Skip

object StashDefault {

  def stashMapping[T](command: T): StashType = StashType.FIFO

  def onCommandDropped[T]: T => Unit = _ => Unit

  def watchAndRemove[T](command: T): Option[ActorRef[_]] = None

  val fifoStashLimit = 100
  val popLastStashLimit = 100
  val fixedStashLimit = 100

  final val offStashes: Set[StashType] = Set(Skip)

}