package stash.command

import akka.typed.testkit.scaladsl.TestProbe
import org.scalatest.{Matchers, WordSpec}
import stash.DedicatedStashCommand._
import stash.StashType.Toggleable
import stash.TestActorSystem._
import stash.{Stash, StashType}

class OffTest extends WordSpec with Matchers {

  "A Stash" can {

    "be able to turned off completely" in {

      val stashMapping: PartialFunction[Int, StashType] = {
        case i if i == 1 => StashType.Skip
        case i if i == 2 => StashType.PopLast
        case i if i == 3 => StashType.Fixed
        case _ => StashType.FIFO
      }

      val processor = TestProbe[Int]("processor")
      val stash = Stash.dedicated(processor.ref, stashMapping = stashMapping).createActor

      stash ! Off()
      //Since the stash is turned off, Pushing FIFO commands will skip the stash
      for (i <- 1 to 10) {
        stash ! Push(i)
        processor.expectMsg[Int](i)
      }
    }

    def turnOffStashTest(stashType: Toggleable) = {
      val processor = TestProbe[Int]("processor")

      def stashMapping: PartialFunction[Int, StashType] = {
        case _ => stashType
      }

      val stash = Stash.dedicated(processor.ref, stashMapping = stashMapping).createActor

      stash ! Off(stashType)
      //Since the stash is turned off, Pushing FIFO commands will skip the stash
      for (i <- 1 to 10) {
        stash ! Push(i)
        processor.expectMsg[Int](i)
      }
    }

    "be able to turn off FIFO stash" in {
      turnOffStashTest(StashType.FIFO)
    }

    "be able to turn off Fixed stash" in {
      turnOffStashTest(StashType.Fixed)
    }

    "be able to turn off FixedTap stash" in {
      turnOffStashTest(StashType.FixedTap)
    }

    "be able to turn off PopLast stash" in {
      turnOffStashTest(StashType.PopLast)
    }
  }
}
