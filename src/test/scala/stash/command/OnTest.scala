package stash.command

import akka.typed.testkit.scaladsl.TestProbe
import org.scalatest.{Matchers, WordSpec}
import stash.DedicatedStashCommand._
import stash.StashType.Toggleable
import stash.TestActorSystem._
import stash.{Stash, StashType}

import scala.concurrent.duration._

class OnTest extends WordSpec with Matchers {

  "A Stash" can {

    "can be able to turn off and then on" in {

      val processor = TestProbe[Int]("processor")
      val stash = Stash.dedicated(processor.ref).createActor

      stash ! Off()
      for (i <- 1 to 10) {
        stash ! Push(i)
        processor.expectMsg[Int](i)
      }

      stash ! On()
      stash ! Push(1)
      processor.expectNoMsg(100.millisecond)

      stash ! Pop()
      processor.expectMsg[Int](1)
    }

    def turnOnStashTest(stashType: Toggleable) = {
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

      stash ! On(stashType)
      for (i <- 1 to 10) {
        stash ! Push(i)
      }
      val clearProbe = TestProbe[Int]("processor")
      stash ! Clear {
        command: Int =>
          clearProbe.ref ! command
          true
      }

      for (i <- 1 to 10) clearProbe.expectMsg[Int](i)
    }

    "be able to turn on FIFO stash" in {
      turnOnStashTest(StashType.FIFO)
    }

    "be able to turn on Fixed stash" in {
      turnOnStashTest(StashType.Fixed)
    }

    "be able to turn on FixedTap stash" in {
      turnOnStashTest(StashType.FixedTap)
    }

    "be able to turn on PopLast stash" in {
      turnOnStashTest(StashType.PopLast)
    }
  }
}
