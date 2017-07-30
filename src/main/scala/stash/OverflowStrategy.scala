package stash

sealed trait OverflowStrategy
object OverflowStrategy {
  final case object DropOldest extends OverflowStrategy
  final case object DropNewest extends OverflowStrategy
}
