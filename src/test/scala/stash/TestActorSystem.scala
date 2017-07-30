package stash

import akka.typed.scaladsl.Actor
import akka.typed.testkit.TestKitSettings
import akka.typed.{ActorRef, ActorSystem, Behavior}
import akka.util.Timeout

import scala.concurrent.Await
import scala.concurrent.duration._

object TestActorSystem {

  sealed trait TestActorSystemCommand

  case class CreateBehavior[T](behavior: Behavior[T])(val replyTo: ActorRef[ActorRef[T]]) extends TestActorSystemCommand

  val guardian =
    Actor.immutable[TestActorSystemCommand] {
      (ctx, command) =>
        command match {
          case command @ CreateBehavior(behavior) =>
            command.replyTo ! ctx.spawnAnonymous(behavior)
            Actor.same
        }
    }


  implicit val system = ActorSystem("test-system", guardian)
  implicit val testSettings = TestKitSettings(system)

  implicit class TestActorSystemImplicits[T](behavior: Behavior[T]) {
    def createActor: ActorRef[T] = {
      import akka.typed.scaladsl.AskPattern._
      implicit val scheduler = system.scheduler
      implicit val timeout = Timeout(100.millis)
      Await.result(system ? CreateBehavior(behavior), timeout.duration)
    }
  }


}



