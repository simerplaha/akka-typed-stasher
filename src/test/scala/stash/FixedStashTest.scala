package stash

import akka.typed.testkit.scaladsl.TestProbe
import org.scalatest.{Matchers, WordSpec}
import stash.DedicatedStashCommand._
import stash.OverflowStrategy.DropNewest
import stash.TestActorSystem._

import scala.concurrent.duration._

class FixedStashTest extends WordSpec with Matchers {

  sealed trait Command
  case object FIFOCommand extends Command
  case object FixedCommand extends Command
  case class FixedTapCommand(number: Int) extends Command

  val stashMapping: PartialFunction[Command, StashType] = {
    case FixedCommand => StashType.Fixed
    case _: FixedTapCommand => StashType.FixedTap
    case _ => StashType.FIFO
  }

  "A Fixed and FixedTap Stash" should {

    "not deliver Fixed messages on Pop" in {
      val processor = TestProbe[Command]("processor")
      val stash = Stash.dedicated(processor.ref, stashMapping = stashMapping).createActor

      stash ! Push(FixedCommand)
      stash ! Push(FixedCommand)
      stash ! Push(FIFOCommand)

      stash ! Pop()
      processor.expectMsg[Command](FIFOCommand)

      stash ! Pop()
      processor.expectNoMsg(100.millis)
    }

    "deliver FixedTap messages on Pop but also keep the messages in the Stash" in {
      val processor = TestProbe[Command]("processor")
      val stash = Stash.dedicated(processor.ref, stashMapping = stashMapping).createActor

      stash ! Push(FixedCommand)
      stash ! Push(FixedTapCommand(1))
      stash ! Push(FIFOCommand)

      processor.expectMsg[Command](FixedTapCommand(1))

      stash ! Pop()
      processor.expectMsg[Command](FIFOCommand)

      stash ! Pop()
      processor.expectNoMsg(100.millis)
    }

    "drop messages when the limit is reached" in {
      val processor = TestProbe[Command]("processor")
      val droppedCommandsProbe = TestProbe[Command]("dropper")

      def onDrop(message: Command): Unit = droppedCommandsProbe.ref ! message

      val stash = Stash.dedicated(processor.ref, fixedOverflowStrategy = DropNewest(10), onCommandDropped = onDrop, stashMapping = stashMapping).createActor

      stash ! Push(FixedCommand)
      for (i <- 1 to 20) stash ! Push(FixedCommand)
      //expect pop last commands to be dropped
      for (i <- 11 to 20) droppedCommandsProbe.expectMsg[Command](FixedCommand)
      //no delivery for Fixed messages
      stash ! Pop()
      processor.expectNoMsg(100.millis)

    }
  }
}
