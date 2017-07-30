package stash

sealed trait StashType
object StashType {
  /**
    * A Stash that can be turned ON and OFF
    */
  sealed trait Toggleable extends StashType
  /**
    * Messages in this stash get added to the end of FIFO stash and are delivered only when the FIFO is empty
    */
  final case object PopLast extends Toggleable
  /**
    * Fixed stash commands cannot be Popped. They can be cleared or removed.
    */
  final case object Fixed extends Toggleable
  /**
    * FixedTap stash cannot be Popped. But a copy of a FixedTap command is delivered initially. This can be used
    * to alert the processor of the command.
    */
  final case object FixedTap extends Toggleable
  /**
    * Stashed in the order or arrival and delivered to the processor on first in, first out basis.
    */
  final case object FIFO extends Toggleable
  /**
    * Messages a skipped from the stash and are delivered instantly.
    */
  final case object Skip extends StashType

  val allStashes: Set[StashType] = Set(PopLast, Fixed, FixedTap, FIFO, Skip)
}