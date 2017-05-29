package stasher

import akka.typed.scaladsl.Actor
import akka.typed.{ActorRef, Behavior, _}
import com.typesafe.scalalogging.LazyLogging
import stasher.OverflowStrategy._
import stasher.StasherCommand._
import akka.typed.scaladsl.Actor.BehaviorDecorators
import stasher.DedicatedStasherCommand.DedicatedStasherCommand

import scala.collection.immutable.{::, Nil}

object OverflowStrategy {
  sealed trait OverflowStrategy
  case object DropOldest extends OverflowStrategy
  case object DropNewest extends OverflowStrategy
}

object DedicatedStasherCommand {
  sealed trait DedicatedStasherCommand[+T]
  final case class Push[T](command: T) extends DedicatedStasherCommand[T]
  final case object Pop extends DedicatedStasherCommand[Nothing]
  final case class PopAll[T](condition: T => Boolean) extends DedicatedStasherCommand[T]
  final case class Clear[T](onClear: T => Unit) extends DedicatedStasherCommand[T]
}


object StasherCommand {
  sealed trait StasherCommand[T]
  final case class Push[T](command: T) extends StasherCommand[T]
  final case class Pop[T](replyTo: ActorRef[T]) extends StasherCommand[T]
  final case class PopAll[T](replyTo: ActorRef[T], condition: T => Boolean) extends StasherCommand[T]
  final case class Clear[T](onClear: T => Unit) extends StasherCommand[T]
}

object Stasher extends LazyLogging {
  /**
    * Stasher behavior that does not require replyTo in Pop and PopAll.
    *
    * @param onCommandDropped invoked when overflow limit is reached and when Stasher actor is terminated
    * @param stopCommand      Command that does not get removed from the Stash and is always the last command.
    * @param replyTo          Incoming commands always get forward to this actor
    * @param overflowStrategy DropOldest or DropNewest
    * @param skipStash        on true will be sent to replyTo without hitting the stash
    * @param stashLimit       max limit of the stash
    */
  def dedicated[T](onCommandDropped: T => Unit,
                   stopCommand: T,
                   replyTo: ActorRef[T],
                   overflowStrategy: OverflowStrategy,
                   skipStash: T => Boolean,
                   stashLimit: Int = 50) =
    new Stasher(onCommandDropped, stopCommand, overflowStrategy, stashLimit).started(List.empty, None).widen[DedicatedStasherCommand[T]] {
      case DedicatedStasherCommand.Pop => StasherCommand.Pop(replyTo)
      case DedicatedStasherCommand.Push(command) if skipStash(command) =>
        replyTo ! command
        StasherCommand.PopAll(replyTo, _ => false) //does make any change. Or have a DoNothing command instead ?
      case DedicatedStasherCommand.Push(command) => StasherCommand.Push(command)
      case DedicatedStasherCommand.PopAll(condition) => StasherCommand.PopAll(replyTo, condition.asInstanceOf[T => Boolean])
      case DedicatedStasherCommand.Clear(onClear) => StasherCommand.Clear(onClear.asInstanceOf[T => Unit])
    }

  /**
    * Stasher behavior that requires replyTo in Pop and PopAll
    */
  def start[T](onCommandDropped: T => Unit,
               stopCommand: T,
               overflowStrategy: OverflowStrategy,
               stashLimit: Int = 50) =
    new Stasher(onCommandDropped, stopCommand, overflowStrategy, stashLimit).started(List.empty, None)
}

class Stasher[T](onCommandDropped: T => Unit,
                 stopCommand: T,
                 overflowStrategy: OverflowStrategy,
                 stashLimit: Int) extends LazyLogging {

  /**
    * This ensure that the Stop Command is the always the last Command.
    * Stop commands do not get dropped because a parent actor might be awaiting for this actor's termination
    */
  private def sortStash(commands: List[T]): List[T] =
    commands.sortBy(_ == stopCommand)

  private def stashLimitCheck(stashedCommands: List[T]): List[T] =
    if (stashedCommands.size > stashLimit) {
      val (stopCommands, otherCommands) = stashedCommands.partition(_ == stopCommand)
      overflowStrategy match {
        case DropOldest =>
          otherCommands.headOption.foreach(onCommandDropped)
          otherCommands.drop(1) ++ stopCommands
        case DropNewest =>
          otherCommands.lastOption.foreach(onCommandDropped)
          otherCommands.dropRight(1) ++ stopCommands

      }
    }
    else
      stashedCommands

  /**
    * Stasher behavior
    *
    * @param stashedCommands currently stashed commands
    * @param replyTo         if Pop(replyTo) is received when stash is empty, replyTo is added to actor's state
    *                        and a command is sent to this actor when the next Push(command) is received.
    */
  def started(stashedCommands: List[T],
              replyTo: Option[ActorRef[T]]): Behavior[StasherCommand[T]] =
    Actor.immutable[StasherCommand[T]] {
      case (_, command) =>
        command match {
          case Push(commandToPush) =>
            replyTo match {
              case Some(replyTo) =>
                stashedCommands match {
                  case Nil =>
                    replyTo ! commandToPush
                    started(stashedCommands, None)
                  case nextCommand :: remainingCommands =>
                    replyTo ! nextCommand
                    val newStash = sortStash(stashLimitCheck(remainingCommands :+ commandToPush))
                    started(newStash, None)
                }

              case None =>
                val newStash = sortStash(stashLimitCheck(stashedCommands :+ commandToPush))
                started(newStash, replyTo)
            }

          case Pop(replyTo) =>
            stashedCommands match {
              case Nil =>
                started(stashedCommands, Some(replyTo))
              case nextCommand :: remainingCommands =>
                replyTo ! nextCommand
                started(remainingCommands, None)
            }

          case PopAll(replyTo, condition) =>
            val (commandsToUnStash, commandsToKeepStashed) = stashedCommands.partition(condition)
            commandsToUnStash foreach (replyTo ! _)
            started(sortStash(commandsToKeepStashed), None)

          case Clear(onClear) =>
            stashedCommands foreach onClear
            started(List.empty, replyTo)
        }
    } onSignal {
      case (_, PostStop) =>
        logger.debug(s"Stasher stopped dropping all commands List(${stashedCommands.map(_.getClass.getSimpleName).mkString(", ")})")
        stashedCommands foreach onCommandDropped
        Actor.stopped
    }
}
