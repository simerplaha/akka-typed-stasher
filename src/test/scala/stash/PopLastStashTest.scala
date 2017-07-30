package stash

import akka.typed.testkit.scaladsl.TestProbe
import org.scalatest.concurrent.Eventually
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}
import stash.DedicatedStashCommand._
import stash.TestActorSystem._

class PopLastStashTest extends WordSpec with BeforeAndAfterAll with Matchers with Eventually {

  sealed trait Command
  case object FIFOCommand extends Command
  case class PopLastCommand(number: Int) extends Command

  val stashMapping: PartialFunction[Command, StashType] = {
    case _: PopLastCommand => StashType.PopLast
    case _ => StashType.FIFO
  }

  "A PopLast Stash" should {

    "deliver messages only when the stash is empty" in {
      val processor = TestProbe[Command]("processor")
      val stash = Stash.dedicated(processor.ref, stashMapping = stashMapping).createActor

      stash ! Push(PopLastCommand(1))
      stash ! Push(FIFOCommand)

      stash ! Pop()
      processor.expectMsg[Command](FIFOCommand)

      stash ! Pop()
      processor.expectMsg[Command](PopLastCommand(1))
    }

    "drop messages when the limit is reached" in {
      val processor = TestProbe[Command]("processor")
      val droppedCommandsProbe = TestProbe[Command]("dropper")

      def onDrop(message: Command): Unit = droppedCommandsProbe.ref ! message

      val stash = Stash.dedicated(processor.ref, popLastStashLimit = 10, onCommandDropped = onDrop, stashMapping = stashMapping).createActor

      stash ! Push(FIFOCommand)
      for (i <- 1 to 20) stash ! Push(PopLastCommand(i))
      //expect pop last commands to be dropped
      for (i <- 11 to 20) droppedCommandsProbe.expectMsg[Command](PopLastCommand(i))
      //next pop should delivery FIFO command since the popLast stash limit is not overflowed
      stash ! Pop()
      processor.expectMsg[Command](FIFOCommand)
      //no other messages in the Stash, it should Pop all the PopLast Stashed commands
      for (i <- 1 to 10) {
        stash ! Pop()
        processor.expectMsg[Command](PopLastCommand(i))
      }

    }
  }
}
