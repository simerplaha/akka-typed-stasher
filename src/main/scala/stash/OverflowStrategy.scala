package stash

sealed trait OverflowStrategy {
  val limit: Int
}
object OverflowStrategy {
  final case class DropOldest(limit: Int) extends OverflowStrategy
  final case class DropNewest(limit: Int) extends OverflowStrategy
}
