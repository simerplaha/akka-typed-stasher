package stasher

import akka.typed.scaladsl.Actor
import akka.typed.{ActorRef, Behavior, _}
import com.typesafe.scalalogging.LazyLogging
import stasher.OverflowStrategy._
import stasher.StasherCommand._

object OverflowStrategy {
  sealed trait OverflowStrategy
  case object DropOldest extends OverflowStrategy
  case object DropNewest extends OverflowStrategy
}

object StasherCommand {
  sealed trait StasherCommand[T]
  final case class Push[T](command: T) extends StasherCommand[T]
  final case class Pop[T](replyTo: ActorRef[T]) extends StasherCommand[T]
  final case class PopAll[T](replyTo: ActorRef[T], condition: T => Boolean) extends StasherCommand[T]
  final case class Clear[T](onClear: T => Unit) extends StasherCommand[T]
}

object Stasher extends LazyLogging {
  def start[T](onCommandDropped: T => Unit, stopCommand: T, stashLimit: Int = 50, overflowStrategy: OverflowStrategy) =
    new Stasher(onCommandDropped, stopCommand, stashLimit, overflowStrategy).start(List.empty, None)
}

class Stasher[T](onCommandDropped: T => Unit,
                 stopCommand: T,
                 stashLimit: Int,
                 overflowStrategy: OverflowStrategy) extends LazyLogging {

  /**
    * This ensure that the Stop Command is the always the last Command
    */
  private def sortStash(commands: List[T]): List[T] =
    commands.sortBy(_ == stopCommand)

  //Stop commands do not get dropped as the actor the dispatched the Stop command might be listening for the Actor's termination.
  private def stashLimitCheck(stashedCommands: List[T]): List[T] =
    if (stashedCommands.size > stashLimit) {
      val stopCommands = stashedCommands.filter(_ == stopCommand)
      val commandsWithStop = stashedCommands.filterNot(_ == stopCommand)
      overflowStrategy match {
        case DropOldest =>
          commandsWithStop.headOption.foreach(onCommandDropped)
          commandsWithStop.drop(1) ++ stopCommands
        case DropNewest =>
          commandsWithStop.lastOption.foreach(onCommandDropped)
          commandsWithStop.dropRight(1) ++ stopCommands

      }
    }
    else
      stashedCommands

  def start(stashedCommands: List[T], replyTo: Option[ActorRef[T]]): Behavior[StasherCommand[T]] =
    Actor.immutable[StasherCommand[T]] {
      case (_, command) =>
        val currentStash = stashedCommands.map(_.getClass.getSimpleName).mkString(", ")
        command match {
          case Push(commandToPush) =>
            logger.debug(s"Stashing ${commandToPush.getClass.getSimpleName} - current stash ${if (currentStash.isEmpty) "Empty" else currentStash}")
            replyTo match {
              case Some(replyTo) =>
                stashedCommands.headOption match {
                  case Some(nextCommand) =>
                    replyTo ! nextCommand
                    val newStash = sortStash(stashLimitCheck(stashedCommands.drop(1) :+ commandToPush))
                    start(newStash, None)
                  case None =>
                    replyTo ! commandToPush
                    start(stashedCommands, None)
                }
              case None =>
                val newStash = sortStash(stashLimitCheck(stashedCommands :+ commandToPush))
                start(newStash, replyTo)
            }

          case Pop(replyTo) =>
            logger.debug(s"Pushing ${command.getClass.getSimpleName} - current stash ${if (currentStash.isEmpty) "Empty" else currentStash}")
            stashedCommands.headOption match {
              case Some(command) =>
                replyTo ! command
                start(stashedCommands.drop(1), None)
              case None =>
                start(stashedCommands, Some(replyTo))
            }

          case PopAll(replyTo, condition) =>
            val (commandsToUnStash, commandsToKeepStashed) = stashedCommands.partition(condition)
            commandsToUnStash foreach (replyTo ! _)
            start(sortStash(commandsToKeepStashed), None)

          case Clear(onClear) =>
            stashedCommands foreach onClear
            start(List.empty, replyTo)
        }
    } onSignal {
      case (_, PostStop) =>
        logger.debug(s"Stasher stopped dropping all commands List(${stashedCommands.map(_.getClass.getSimpleName).mkString(", ")})")
        stashedCommands foreach onCommandDropped
        Actor.stopped
    }
}
