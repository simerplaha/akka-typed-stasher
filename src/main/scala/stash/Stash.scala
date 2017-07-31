package stash

import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.Actor.BehaviorDecorators
import akka.typed.{ActorRef, Behavior, _}
import stash.OverflowStrategy._
import stash.StashCommand._
import stash.StashType._

import scala.collection.immutable.{::, Nil}
import scala.util.{Failure, Success, Try}

object Stash {

  /**
    * If the processor get terminated, the Stasher is also terminated after the input onCommandDropped function is
    * invoked on all the currently stashed commands.
    *
    * A Stash can have multiple other stashes. Currently supported stashes are
    * 1. FIFO - Delivered on FIFO based
    * 2. PopLast - Delivered when FIFO stash is empty
    * 3. Fixed - Is always kept in stashed and is never dropped unless the fixed stash limit is reached.
    * 4. FixedTap - Just like Fixed but a copy of the command is delivered to the processor actor. Can be used as an alert.
    * 5. Skip - Does not get stashed and is delivered instantly.
    *
    * @param onCommandDropped        Used if the stash limit or reached or if the Processor actor is terminated.
    * @param processor               Processor behavior which is spawn a child actor by the stasher and gets an instance of the
    *                                stasher.
    * @param watchAndRemove          Stasher can watch for replyTo attributes in ContactView commands. If the replyTo is terminated.
    *                                Stasher will remove that command from it's stash.
    * @param stashMapping            Determines which stash the command belong to.
    * @param fifoOverflowStrategy    FIFO's overflow strategy
    * @param popLastOverflowStrategy PopLast's overflow strategy
    * @param fixedOverflowStrategy   Fixed and FixedTap's overflow strategy
    * @tparam T - Stash commands type.
    * @return - Stasher behavior.
    */
  def dedicated[T](processor: ActorRef[T],
                   fifoOverflowStrategy: OverflowStrategy = OverflowStrategy.DropNewest(StashDefault.fifoStashLimit),
                   popLastOverflowStrategy: OverflowStrategy = OverflowStrategy.DropNewest(StashDefault.popLastStashLimit),
                   fixedOverflowStrategy: OverflowStrategy = OverflowStrategy.DropNewest(StashDefault.fifoStashLimit),
                   onCommandDropped: T => Unit = StashDefault.onCommandDropped,
                   watchAndRemove: T => Option[ActorRef[_]] = StashDefault.watchAndRemove[T] _,
                   stashMapping: T => StashType = StashDefault.stashMapping[T] _): Behavior[DedicatedStashCommand[T]] =
    Actor.deferred[DedicatedStashCommand[T]] {
      ctx =>
        ctx.watch(processor)

        new Stash(
          onCommandDropped = onCommandDropped,
          watchAndRemove = watchAndRemove,
          fifoOverflowStrategy = fifoOverflowStrategy,
          popLastOverflowStrategy = popLastOverflowStrategy,
          fixedOverflowStrategy = fixedOverflowStrategy
        ).stashing(List.empty, StashDefault.offStashes, stashMapping, None)
          .widen[DedicatedStashCommand[T]] {
          case DedicatedStashCommand.Pop(condition) => StashCommand.Pop[T](processor, condition.asInstanceOf[Option[T => Boolean]])
          case DedicatedStashCommand.On(stash) => StashCommand.On(stash)
          case DedicatedStashCommand.Off(stash) => StashCommand.Off(stash, processor)
          case DedicatedStashCommand.Push(command) => StashCommand.Push(command, processor)
          case DedicatedStashCommand.Clear(condition) => StashCommand.Clear(condition.asInstanceOf[T => Boolean])
          case DedicatedStashCommand.Remove(condition) => StashCommand.Remove(condition.asInstanceOf[T => Boolean])
          case DedicatedStashCommand.ClearStash(stashType) => StashCommand.ClearStash(stashType)
          case DedicatedStashCommand.Iterate(iterator) => StashCommand.Iterate(iterator.asInstanceOf[T => Unit])
        }
    }

  /**
    * This can be used as a plug in situations where we do not want the a parent to send messages to the processor
    * actor directly but want it to submit messages to the Stash and the processor actor can [[Pop]] whenever it's
    * ready to process the next message.
    *
    * Here the Stash will spawn the processor actor.
    */
  def plug[T](processor: ActorRef[DedicatedStashCommand[T]] => Behavior[T],
              fifoOverflowStrategy: OverflowStrategy = OverflowStrategy.DropNewest(StashDefault.fifoStashLimit),
              popLastOverflowStrategy: OverflowStrategy = OverflowStrategy.DropNewest(StashDefault.popLastStashLimit),
              fixedOverflowStrategy: OverflowStrategy = OverflowStrategy.DropNewest(StashDefault.fifoStashLimit),
              onCommandDropped: T => Unit = StashDefault.onCommandDropped,
              watchAndRemove: T => Option[ActorRef[_]] = StashDefault.watchAndRemove[T] _,
              stashMapping: T => StashType = StashDefault.stashMapping[T] _): Behavior[DedicatedStashCommand.Push[T]] =
    Actor.deferred[DedicatedStashCommand[T]] {
      ctx =>
        val processorChild = ctx.spawn(processor(ctx.self), ctx.self.path.name)
        ctx.watch(processorChild)

        dedicated(
          processor = processorChild,
          onCommandDropped = onCommandDropped,
          watchAndRemove = watchAndRemove,
          stashMapping = stashMapping,
          fifoOverflowStrategy = fifoOverflowStrategy,
          popLastOverflowStrategy = popLastOverflowStrategy,
          fixedOverflowStrategy = fixedOverflowStrategy
        )
    }.narrow[DedicatedStashCommand.Push[T]]

  /**
    * Stasher behavior that requires replyTo in Pop and PopAll
    */
  def apply[T](fifoOverflowStrategy: OverflowStrategy = OverflowStrategy.DropNewest(StashDefault.fifoStashLimit),
               popLastOverflowStrategy: OverflowStrategy = OverflowStrategy.DropNewest(StashDefault.popLastStashLimit),
               fixedOverflowStrategy: OverflowStrategy = OverflowStrategy.DropNewest(StashDefault.fifoStashLimit),
               onCommandDropped: T => Unit = StashDefault.onCommandDropped,
               stashMapping: T => StashType = StashDefault.stashMapping[T] _,
               watchAndRemove: T => Option[ActorRef[_]] = StashDefault.watchAndRemove[T] _): Behavior[StashCommand[T]] =
    new Stash(onCommandDropped, watchAndRemove, fifoOverflowStrategy, popLastOverflowStrategy, fixedOverflowStrategy)
      .stashing(List.empty, StashDefault.offStashes, stashMapping, None)
}

private object Stashes {
  def empty[T] = Stashes[T](List.empty, List.empty, List.empty)
}

//TODO - Make it mutable for performance ?
private case class Stashes[T](popLast: List[T],
                              fixed: List[T],
                              fifo: List[T])

private class Stash[T](onCommandDropped: T => Unit,
                       watchAndRemove: T => Option[ActorRef[_]],
                       fifoOverflowStrategy: OverflowStrategy,
                       popLastOverflowStrategy: OverflowStrategy,
                       fixedOverflowStrategy: OverflowStrategy) {

  /**
    * Check if the limit is reached and drops the commands based on the overflow strategy for that Stash.
    */
  private def dropOneOnOverflow(stashedCommands: List[T], overflowStrategy: OverflowStrategy): List[T] =
    if (stashedCommands.size > overflowStrategy.limit) {
      overflowStrategy match {
        case DropOldest(_) =>
          stashedCommands.headOption.foreach(onCommandDropped)
          stashedCommands.drop(1)

        case DropNewest(_) =>
          stashedCommands.lastOption.foreach(onCommandDropped)
          stashedCommands.dropRight(1)
      }
    }
    else
      stashedCommands

  /**
    * Splits the stashed commands into their respective stashes.
    */
  private def splitStashedCommands(stashedCommands: List[T],
                                   stashMapping: T => StashType): Stashes[T] =
    stashedCommands.foldLeft(Stashes.empty[T]) {
      case (stashes, command) =>
        stashMapping(command) match {
          case PopLast =>
            stashes.copy(popLast = stashes.popLast.:+(command))
          case Fixed | FixedTap =>
            stashes.copy(fixed = stashes.fixed.:+(command))
          case FIFO =>
            stashes.copy(fifo = stashes.fifo.:+(command))
          case _ =>
            stashes
        }
    }

  private def stashLimitCheck(stashedCommands: List[T],
                              stashMapping: T => StashType): List[T] = {

    val stashes: Stashes[T] = splitStashedCommands(stashedCommands, stashMapping)

    dropOneOnOverflow(stashes.fifo, fifoOverflowStrategy) ++
      dropOneOnOverflow(stashes.popLast, popLastOverflowStrategy) ++
      dropOneOnOverflow(stashes.fixed, fixedOverflowStrategy)
  }

  /**
    * @param stashedCommands currently stashed commands
    * @param awaitingActor   if Pop(replyTo) is received when stash is empty, replyTo is added to actor's state
    *                        and a command is sent to this actor when the next Push(command) is received.
    */
  private def stashing(stashedCommands: List[T],
                       offStashes: Set[StashType],
                       stashMapping: T => StashType,
                       awaitingActor: Option[ActorRef[T]]): Behavior[StashCommand[T]] = {

    def cannotPop(command: T): Boolean = !canPop(command)

    /**
      * Commands stashed in Fixed cannot be popped.
      */
    def canPop(command: T): Boolean = {
      val stash = stashMapping(command)
      stash != Fixed && stash != FixedTap
    }

    Actor.immutable[StashCommand[T]] {
      case (ctx, command) =>
        implicit val implicitCtx = ctx
        val logger = ctx.system.log

        def watch(command: T) =
          watchAndRemove(command).foreach(ctx.watch(_))

        def unwatch(command: T) =
          watchAndRemove(command).foreach(ctx.unwatch(_))

        command match {

          case Off(stash, replyTo) =>
            val (offCommands: List[T], otherCommands: List[T], newOffStashes: Set[StashType]) =
              stash match {
                case Some(stashToTurnOff) =>
                  val (offCommands: List[T], otherCommands: List[T]) = stashedCommands.partition(stashMapping(_) == stashToTurnOff)
                  (offCommands, otherCommands, offStashes + stashToTurnOff)
                case None =>
                  (stashedCommands, List.empty, StashType.allStashes)
              }

            offCommands.foreach {
              command =>
                replyTo ! command
                unwatch(command)
            }
            stashing(otherCommands, newOffStashes, stashMapping, None)

          case On(stash) =>
            stash match {
              case Some(stashToTurnOn) =>
                stashing(stashedCommands, offStashes - stashToTurnOn, stashMapping, awaitingActor)
              case None =>
                stashing(stashedCommands, StashDefault.offStashes, stashMapping, awaitingActor)
            }

          case Push(commandToPush, replyTo) if offStashes.contains(stashMapping(commandToPush)) =>
            replyTo ! commandToPush
            Actor.same

          case Push(commandToPush, replyTo) if stashMapping(commandToPush) == FixedTap =>
            replyTo ! commandToPush
            watch(commandToPush)
            val newStash = stashLimitCheck(stashedCommands :+ commandToPush, stashMapping)
            stashing(newStash, offStashes, stashMapping, awaitingActor)

          case Push(commandToPush, _) =>
            awaitingActor match {
              case Some(awaiting) =>
                stashedCommands match {
                  case Nil =>
                    awaiting ! commandToPush
                    Actor.same
                  case _ =>
                    Actor.same
                }
              case None =>
                watch(commandToPush)
                val newStash = stashLimitCheck(stashedCommands :+ commandToPush, stashMapping)
                stashing(newStash, offStashes, stashMapping, None)
            }

          case Pop(replyTo, condition) =>
            condition match {
              case Some(condition) =>
                val (commandsToUnStash, commandsToKeepStashed) = stashedCommands.partition(command => condition(command) && canPop(command))
                commandsToUnStash foreach {
                  command =>
                    replyTo ! command
                    unwatch(command)
                }
                stashing(commandsToKeepStashed, offStashes, stashMapping, None)

              case None =>
                stashedCommands match {
                  case Nil =>
                    stashing(stashedCommands, offStashes, stashMapping, Some(replyTo))

                  case nextCommand :: _ if cannotPop(nextCommand) =>
                    stashing(stashedCommands, offStashes, stashMapping, Some(replyTo))

                  case nextCommand :: remainingCommands =>
                    replyTo ! nextCommand
                    unwatch(nextCommand)
                    stashing(remainingCommands, offStashes, stashMapping, None)
                }

            }

          case Clear(condition) =>
            val (toClear, toKeep) = stashedCommands.partition(condition)
            toClear foreach unwatch
            stashing(toKeep, offStashes, stashMapping, awaitingActor)

          case CopyStash(replyTo) =>
            replyTo ! stashedCommands
            Actor.same

          case Remove(condition) =>
            val newStash = stashedCommands.filterNot(condition)
            stashedCommands.filter(condition).foreach(unwatch)
            stashing(newStash, offStashes, stashMapping, awaitingActor)

          case ClearStash(stash) =>
            val newStash = stashedCommands.filterNot(stashMapping(_) == stash)
            stashedCommands.filter(stashMapping(_) == stash).foreach(unwatch)
            stashing(newStash, offStashes, stashMapping, awaitingActor)

          case Iterate(iterator) =>
            Try(stashedCommands.foreach(iterator)) match {
              case Failure(exception) =>
                logger.error("Failed while iterating", exception)
              case Success(_) =>
            }
            Actor.same

        }
    } onSignal {
      //In the dedicated behavior the child actor always have the same name as the Stasher. So check if the terminated actor is the child actor
      //and stop the stasher which will inturn invoke PostStop which will drop all the existing command in the stash.
      case (ctx, Terminated(ref)) if ref.path.name == ctx.self.path.name =>
        ctx.system.log.debug(s"Stasher's child terminated (${ref.path.toString}). Stopping stash.")
        Actor.stopped

      //Find the request that got terminated and remove that command from the stash
      case (_, Terminated(ref)) =>
        //If one of the stashed command's replyTo actor is terminated. Then remove it from the stash
        val newStashedCommand = stashedCommands.filterNot(watchAndRemove(_).map(_.path).contains(ref.path))
        stashing(newStashedCommand, offStashes, stashMapping, awaitingActor)

      case (ctx, PostStop) =>
        ctx.system.log.debug(s"Stasher stopped dropping all commands List(${stashedCommands.map(_.getClass.getSimpleName).mkString(", ")})")
        stashedCommands foreach onCommandDropped
        Actor.stopped
    }
  }
}