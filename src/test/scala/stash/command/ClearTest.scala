package stash.command

import akka.typed.testkit.scaladsl.TestProbe
import org.scalatest.{Matchers, WordSpec}
import stash.DedicatedStashCommand._
import stash.Stash
import stash.TestActorSystem._

import scala.concurrent.duration._

class ClearTest extends WordSpec with Matchers {

  "A Stash" can {

    "be cleared" in {
      val processor = TestProbe[Int]("processor")
      val stash = Stash.dedicated(processor.ref).createActor

      for (i <- 1 to 10) stash ! Push(i)

      val clearProbe = TestProbe[Int]("processor")
      stash ! Clear {
        command: Int =>
          clearProbe.ref ! command
          true
      }

      for (i <- 1 to 10) clearProbe.expectMsg[Int](i)

      stash ! Pop()
      processor.expectNoMsg(100.millisecond)
    }
  }
}
