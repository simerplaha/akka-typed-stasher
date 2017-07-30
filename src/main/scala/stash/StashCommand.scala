package stash

import akka.typed.ActorRef
import stash.StashType.Toggleable

/**
  * Stasher command that require replyTo input as the stasher processor is unknown at the time to
  * Stasher initialisation.
  */
sealed trait StashCommand[T]
object StashCommand {
  final case class Push[T](command: T, replyTo: ActorRef[T]) extends StashCommand[T]
  final case class Pop[T](replyTo: ActorRef[T], condition: Option[T => Boolean]) extends StashCommand[T]
  final case class Remove[T](condition: T => Boolean) extends StashCommand[T]
  final case class Clear[T](condition: T => Boolean) extends StashCommand[T]
  final case class ClearStash[T](stash: StashType) extends StashCommand[T]
  final case class Iterate[T](next: T => Unit) extends StashCommand[T]
  final case class CopyStash[T](replyTo: ActorRef[Seq[T]]) extends StashCommand[T]

  object Off {
    def apply[T](stash: Toggleable, replyTo: ActorRef[T]) = new Off(Some(stash), replyTo)

    def apply[T](replyTo: ActorRef[T]) = new Off(None, replyTo)
  }
  private[stash] final case class Off[T](stash: Option[Toggleable], replyTo: ActorRef[T]) extends StashCommand[T]

  object On {
    def apply(stash: Toggleable) = new On(Some(stash))

    def apply() = new On(None)
  }
  private[stash] final case class On[T](stash: Option[Toggleable]) extends StashCommand[T]
}