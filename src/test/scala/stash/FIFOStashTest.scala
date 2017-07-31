package stash

import akka.typed.testkit.scaladsl.TestProbe
import org.scalatest.{Matchers, WordSpec}
import stash.DedicatedStashCommand._
import stash.OverflowStrategy.{DropNewest, DropOldest}
import stash.TestActorSystem._

class FIFOStashTest extends WordSpec with Matchers {

  "A FIFO Stash" should {

    "deliver stashed messages in order" in {
      val processor = TestProbe[Int]("processor")
      val stash = Stash.dedicated(processor.ref).createActor

      for (i <- 1 to 10) stash ! Push(i)

      for (i <- 1 to 10) {
        stash ! Pop()
        processor.expectMsg[Int](i)
      }
    }

    "with a limit and DropNewest strategy should drop new messages" in {
      val processor = TestProbe[Int]("processor")
      val droppedCommandsProbe = TestProbe[Int]("dropper")

      def onDrop(message: Int): Unit = droppedCommandsProbe.ref ! message

      val stash =
        Stash.dedicated(
          processor = processor.ref,
          fifoOverflowStrategy = DropNewest(10),
          onCommandDropped = onDrop
        ).createActor

      for (i <- 1 to 20) stash ! Push(i)
      //since size limit is 10, messages 11 to 20 should get dropped
      for (i <- 11 to 20) droppedCommandsProbe.expectMsg[Int](i)
      //Pop the remaining
      for (i <- 1 to 10) {
        stash ! Pop()
        processor.expectMsg[Int](i)
      }
    }

    "with a limit and DropOldest strategy should drop old messages" in {
      val processor = TestProbe[Int]("processor")
      val droppedCommandsProbe = TestProbe[Int]("dropper")

      def onDrop(message: Int): Unit = droppedCommandsProbe.ref ! message

      val stash =
        Stash.dedicated(
          processor = processor.ref,
          fifoOverflowStrategy = DropOldest(10),
          onCommandDropped = onDrop
        ).createActor

      for (i <- 1 to 20) stash ! Push(i)
      //since size limit is 10, messages 1 to 10 should get dropped
      for (i <- 1 to 10) droppedCommandsProbe.expectMsg[Int](i)
      //Pop the remaining
      for (i <- 11 to 20) {
        stash ! Pop()
        processor.expectMsg[Int](i)
      }
    }
  }
}
