package stash

import akka.typed.testkit.scaladsl.TestProbe
import org.scalatest.concurrent.Eventually
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}
import stash.DedicatedStashCommand._
import stash.TestActorSystem._

class StashTest extends WordSpec with BeforeAndAfterAll with Matchers with Eventually {

  "A Stash" should {

    "Push and Pop commands" in {
      val processor = TestProbe[String]("processor")
      val stash = Stash.dedicated(processor = processor.ref).createActor

      stash ! Push("command1")
      stash ! Pop()
      processor.expectMsg[String]("command1")

      stash ! Push("command2")
      stash ! Pop()
      processor.expectMsg[String]("command2")
    }

    "Pop and then Push commands" in {
      val processor = TestProbe[String]("processor")
      val stash = Stash.dedicated(processor.ref).createActor

      stash ! Pop()
      stash ! Push("command1")
      processor.expectMsg[String]("command1")

      stash ! Push("command2")
      stash ! Pop()
      processor.expectMsg[String]("command2")
    }
  }

  "An empty Stash" should {

    "remember the last Pop and delivery message on next Push" in {
      val processor = TestProbe[String]("processor")
      val stash = Stash.dedicated(processor.ref).createActor

      stash ! Pop()
      stash ! Push("command1")
      processor.expectMsg[String]("command1")

      stash ! Pop()
      stash ! Push("command2")
      processor.expectMsg[String]("command2")
    }

  }
}
