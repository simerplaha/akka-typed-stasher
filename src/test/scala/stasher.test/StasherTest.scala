package stasher.test

import akka.typed.ActorRef
import akka.typed.testkit.scaladsl.TestProbe
import org.scalatest.concurrent.Eventually
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}
import stasher.StasherCommand._
import stasher._
import stasher.test.StasherTestCommand._
import stasher.test.TestActorSystem._

import scala.concurrent.duration._

object StasherTestCommand {

  //responses
  sealed trait Response

  case class CommandSuccessful(command: Command) extends Response

  case class CommandDropped(command: Command) extends Response

  //commands
  sealed trait Command

  case object Stop extends Command

  case class CreateUser(id: Int)(val replyTo: ActorRef[Response]) extends Command

  case class UpdateUser(id: Int)(val replyTo: ActorRef[Response]) extends Command

  case class DeleteUser(id: Int)(val replyTo: ActorRef[Response]) extends Command

  case class DisableUser(id: Int)(val replyTo: ActorRef[Response]) extends Command

}


class StasherTest extends WordSpec with BeforeAndAfterAll with Matchers with Eventually {

  val timeout: FiniteDuration = 15.minutes

  def onCommandDrop(command: Command): Unit =
    command match {
      case Stop =>
        fail("Stop command should not get dropped")
      case command @ CreateUser(id) => command.replyTo ! CommandDropped(command)
      case command @ UpdateUser(id) => command.replyTo ! CommandDropped(command)
      case command @ DeleteUser(id) => command.replyTo ! CommandDropped(command)
      case command @ DisableUser(id) => command.replyTo ! CommandDropped(command)
    }

  "A Stasher actor" should {

    "be able to Push and Pop commands" in {
      val stasher = Stasher.start[Command](onCommandDrop, Stop, 5, DropStrategy.DropOldest).createActor

      val replyToProbe = TestProbe[Response]("replyTo")
      val command = CreateUser(1)(replyToProbe.ref)
      val commandProcessorProbe = TestProbe[Command]("commandProcessorProbe")
      stasher ! Pop(commandProcessorProbe.ref)
      stasher ! Push(command)

      commandProcessorProbe.expectMsg(command)
    }

    "should be able to receive 1 command when Pop is invoked" in {
      val stasher = Stasher.start[Command](onCommandDrop, Stop, 50, DropStrategy.DropOldest).createActor

      val replyToProbe = TestProbe[Response]("replyTo")
      val commandProcessorProbe = TestProbe[Command]("commandProcessorProbe")
      stasher ! Pop(commandProcessorProbe.ref)

      for (i <- 1 to 10)
        stasher ! Push(CreateUser(i)(replyToProbe.ref))
      commandProcessorProbe.expectMsg(CreateUser(1)(replyToProbe.ref))

      stasher ! Pop(commandProcessorProbe.ref)
      commandProcessorProbe.expectMsg(CreateUser(2)(replyToProbe.ref))
    }

    "be able respond to dropped messages" in {
      val stasher = Stasher.start[Command](onCommandDrop, Stop, 1, DropStrategy.DropOldest).createActor

      val replyToProbe = TestProbe[Response]("replyTo")
      val commandProcessorProbe = TestProbe[Command]("commandProcessorProbe")
      val commands = for (i <- 1 to 10) yield CreateUser(i)(replyToProbe.ref)

      commands foreach (stasher ! Push(_))

      //since the stash size 1, the first 9 messages should get dropped
      for (i <- 0 to 8)
        replyToProbe.expectMsg(CommandDropped(commands(i)))

      //can fetch the last message
      stasher ! Pop(commandProcessorProbe.ref)
      commandProcessorProbe.expectMsg(commands.last)
    }

    "should always have Stop message as the last message" in {

      val stasher = Stasher.start[Command](onCommandDrop, Stop, 10, DropStrategy.DropOldest).createActor

      val replyToProbe = TestProbe[Response]("replyTo")
      val commandProcessorProbe = TestProbe[Command]("commandProcessorProbe")
      stasher ! Push(CreateUser(1)(replyToProbe.ref))
      //can fetch the last message
      stasher ! Pop(commandProcessorProbe.ref)
      commandProcessorProbe.expectMsg(timeout, CreateUser(1)(replyToProbe.ref))

      //push Stop first
      stasher ! Push(Stop)
      //push CreateUser after Stop
      stasher ! Push(CreateUser(2)(replyToProbe.ref))

      //Popping should not return Stop but should return CreateUser instead
      stasher ! Pop(commandProcessorProbe.ref)
      commandProcessorProbe.expectMsg(timeout, CreateUser(2)(replyToProbe.ref))

      //Popping again should return Stop
      stasher ! Pop(commandProcessorProbe.ref)
      commandProcessorProbe.expectMsg(timeout, Stop)
    }

    "should maintain the order of stashed Commands" in {
      def onCommandDrop(command: Command): Unit =
        fail(s"Command $command dropped")

      val stasher = Stasher.start[Command](onCommandDrop, Stop, 10, DropStrategy.DropOldest).createActor

      val replyToProbe = TestProbe[Response]("replyTo")
      val commandProcessorProbe = TestProbe[Command]("commandProcessorProbe")
      //push Stop first since the stashLimit is 1
      stasher ! Push(Stop)

      val commands =
        CreateUser(1)(replyToProbe.ref) ::
          UpdateUser(1)(replyToProbe.ref) ::
          CreateUser(2)(replyToProbe.ref) ::
          CreateUser(3)(replyToProbe.ref) ::
          DeleteUser(2)(replyToProbe.ref) ::
          DisableUser(3)(replyToProbe.ref) ::
          Nil

      commands foreach (stasher ! Push(_))

      //Stop get pushed to the end
      (commands :+ Stop) foreach {
        expectedCommand =>
          stasher ! Pop(commandProcessorProbe.ref)
          commandProcessorProbe.expectMsg(timeout, expectedCommand)
      }
    }

    "should never drop Stop commands" in {

      val stasher = Stasher.start[Command](onCommandDrop, Stop, 1, DropStrategy.DropOldest).createActor

      val replyToProbe = TestProbe[Response]("replyTo")
      val commandProcessorProbe = TestProbe[Command]("commandProcessorProbe")
      //push Stop first since the stashLimit is 1
      stasher ! Push(Stop)

      //push any new commands should get dropped since the stashLimit is 1 and Stop commands do not get dropped
      val commands =
        CreateUser(1)(replyToProbe.ref) ::
          UpdateUser(1)(replyToProbe.ref) ::
          DeleteUser(1)(replyToProbe.ref) ::
          Nil

      commands foreach (stasher ! Push(_))

      commands foreach (command => replyToProbe.expectMsg(CommandDropped(command)))

      stasher ! Pop(commandProcessorProbe.ref)
      commandProcessorProbe.expectMsg(timeout, Stop)
    }

    "should drop Oldest command if the DropStrategy is DropNewest" in {
      val stasher = Stasher.start[Command](onCommandDrop, Stop, 2, DropStrategy.DropNewest).createActor

      val replyToProbe = TestProbe[Response]("replyTo")

      val command0 = CreateUser(0)(replyToProbe.ref)
      stasher ! Push(command0)

      val command1 = CreateUser(1)(replyToProbe.ref)
      stasher ! Push(command1)

      val command2 = CreateUser(2)(replyToProbe.ref)
      stasher ! Push(command2)
      replyToProbe.expectMsg(CommandDropped(command2))

      val command3 = CreateUser(3)(replyToProbe.ref)
      stasher ! Push(command3)
      replyToProbe.expectMsg(CommandDropped(command3))

    }
  }

}
