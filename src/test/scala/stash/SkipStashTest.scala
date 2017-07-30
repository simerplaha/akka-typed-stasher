package stash

import akka.typed.testkit.scaladsl.TestProbe
import org.scalatest.concurrent.Eventually
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}
import stash.DedicatedStashCommand._
import stash.TestActorSystem._

class SkipStashTest extends WordSpec with BeforeAndAfterAll with Matchers with Eventually {

  sealed trait Command
  case object FIFOCommand extends Command
  case class SkipCommand(number: Int) extends Command

  val stashMapping: PartialFunction[Command, StashType] = {
    case _: SkipCommand => StashType.Skip
    case _ => StashType.FIFO
  }

  "A Skip Stash" should {

    "deliver messages instantly" in {
      val processor = TestProbe[Command]("processor")
      val stash = Stash.dedicated(processor.ref, stashMapping = stashMapping).createActor

      stash ! Push(SkipCommand(1))
      processor.expectMsg[Command](SkipCommand(1))

      stash ! Push(FIFOCommand)
      stash ! Pop()
      processor.expectMsg[Command](FIFOCommand)

      stash ! Push(SkipCommand(2))
      processor.expectMsg[Command](SkipCommand(2))
      stash ! Push(SkipCommand(3))
      processor.expectMsg[Command](SkipCommand(3))
    }
  }
}
