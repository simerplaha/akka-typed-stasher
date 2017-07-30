package stash

import stash.StashType.Toggleable

/**
  * API commands for funnel behavior. These commands do not require a replyTo as the funnel behavior is
  * provided with the processor behavior.
  */
sealed trait DedicatedStashCommand[+T]
object DedicatedStashCommand {
  /**
    * Command to Push into the stash.
    */
  final case class Push[T](command: T) extends DedicatedStashCommand[T]
  /**
    * Delivers the next commands to the processor
    */
  object Pop {
    def apply[T](condition: T => Boolean) = new Pop(Some(condition))

    def apply[T]() = new Pop[T](None)
  }

  private[stash] final case class Pop[T](condition: Option[T => Boolean]) extends DedicatedStashCommand[T]
  /**
    * Removed commands from the stash based on the input condition
    */
  final case class Remove[T](condition: T => Boolean) extends DedicatedStashCommand[T]
  /**
    * Clears the stash based on the passed condition
    * This does NOT also invoke onCommandDropped function
    */
  final case class Clear[T](condition: T => Boolean) extends DedicatedStashCommand[T]
  /**
    * Clears all the commands that belong to a Stash
    */
  final case class ClearStash[T](stash: StashType) extends DedicatedStashCommand[T]
  /**
    * Runs the input function on all the stashed commands
    */
  final case class Iterate[T](next: T => Unit) extends DedicatedStashCommand[T]
  /**
    * Turns stashing off and delivers all incoming commands to the processor.
    */
  object Off {
    def apply(stash: Toggleable) = new Off(Some(stash))

    def apply() = new Off(None)
  }
  private[stash] final case class Off(stash: Option[Toggleable]) extends DedicatedStashCommand[Nothing]
  /**
    * Turns stashing on based on the previous stashing configuration.
    */
  object On {
    def apply(stash: Toggleable) = new On(Some(stash))

    def apply() = new On(None)
  }
  private[stash] final case class On(stash: Option[Toggleable]) extends DedicatedStashCommand[Nothing]

}