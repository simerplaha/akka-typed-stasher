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

  val timeout: FiniteDuration = 3.seconds

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
      val stasher = Stasher.start[Command](onCommandDrop, Stop, OverflowStrategy.DropOldest, 5).createActor

      val replyToProbe = TestProbe[Response]("replyTo")
      val command = CreateUser(1)(replyToProbe.ref)
      val commandProcessorProbe = TestProbe[Command]("commandProcessorProbe")
      stasher ! Pop(replyTo = commandProcessorProbe.ref)
      stasher ! Push(command)

      commandProcessorProbe.expectMsg(command)
    }

    "should be able to receive 1 command when Pop is invoked" in {
      val stasher = Stasher.start[Command](onCommandDrop, Stop, OverflowStrategy.DropOldest, 50).createActor

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
      val stasher = Stasher.start[Command](onCommandDrop, Stop, OverflowStrategy.DropOldest, 1).createActor

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

      val stasher = Stasher.start[Command](onCommandDrop, Stop, OverflowStrategy.DropOldest, 10).createActor

      val replyToProbe = TestProbe[Response]("replyTo")
      val commandProcessorProbe = TestProbe[Command]("commandProcessorProbe")

      stasher ! Push(CreateUser(1)(replyToProbe.ref))
      stasher ! Pop(commandProcessorProbe.ref)

      commandProcessorProbe.expectMsg(timeout, CreateUser(1)(replyToProbe.ref))

      stasher ! Push(Stop)
      stasher ! Push(CreateUser(2)(replyToProbe.ref))

      //Even though Stop was pushed first, CreateUser is expected as Stop commands are always added as the end of the stack.
      stasher ! Pop(commandProcessorProbe.ref)
      commandProcessorProbe.expectMsg(timeout, CreateUser(2)(replyToProbe.ref))

      //Popping again should return Stop
      stasher ! Pop(commandProcessorProbe.ref)
      commandProcessorProbe.expectMsg(timeout, Stop)
    }

    "should maintain the order of stashed Commands" in {
      def onCommandDrop(command: Command): Unit =
        fail(s"Command $command dropped")

      val stasher = Stasher.start[Command](onCommandDrop, Stop, OverflowStrategy.DropOldest, 10).createActor

      val replyToProbe = TestProbe[Response]("replyTo")
      val commandProcessorProbe = TestProbe[Command]("commandProcessorProbe")
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

      //Stop command is always the last stashed command
      (commands :+ Stop) foreach {
        expectedCommand =>
          stasher ! Pop(commandProcessorProbe.ref)
          commandProcessorProbe.expectMsg(timeout, expectedCommand)
      }
    }

    "should never drop Stop commands" in {

      val stasher = Stasher.start[Command](onCommandDrop, Stop, OverflowStrategy.DropOldest, 1).createActor

      val replyToProbe = TestProbe[Response]("replyTo")
      val commandProcessorProbe = TestProbe[Command]("commandProcessorProbe")
      //push Stop first since the stashLimit is 1
      stasher ! Push(Stop)

      //push any new commands should get dropped since the stashLimit is 1 and Stop commands do NOT get dropped
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

    "should drop newest command if the OverflowStrategy is DropNewest" in {
      val stasher = Stasher.start[Command](onCommandDrop, Stop, OverflowStrategy.DropNewest, 2).createActor

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

    "demo" in {

      sealed trait Command
      case object MyCommand1 extends Command
      case object MyCommand2 extends Command
      case object MyCommand3 extends Command
      case object MyCommand4 extends Command
      case class CommandDropped(command: Command) extends Command
      case object Stop extends Command

      val myCommandProcessor = TestProbe[Command]("myCommandProcessor")

      val stasher =
        Stasher.start[Command](
          onCommandDropped = myCommandProcessor.ref ! CommandDropped(_),
          stopCommand = Stop,
          stashLimit = 2,
          overflowStrategy = OverflowStrategy.DropOldest
        ).createActor


      stasher ! Push(MyCommand1)
      stasher ! Push(MyCommand2)
      stasher ! Pop(replyTo = myCommandProcessor.ref)
      stasher ! Pop(replyTo = myCommandProcessor.ref)
      myCommandProcessor.expectMsg(MyCommand1)
      myCommandProcessor.expectMsg(MyCommand2)

      stasher ! Push(MyCommand1)
      stasher ! Push(MyCommand2)
      stasher ! PopAll(replyTo = myCommandProcessor.ref, condition = _ => true)
      myCommandProcessor.expectMsg(MyCommand1)
      myCommandProcessor.expectMsg(MyCommand2)

      stasher ! Push(MyCommand1)
      stasher ! Push(MyCommand2)
      stasher ! Clear(onClear = myCommandProcessor.ref ! _)
      myCommandProcessor.expectMsg(MyCommand1)
      myCommandProcessor.expectMsg(MyCommand2)

      stasher ! Push(MyCommand1)
      stasher ! Push(MyCommand2)
      //expect oldest messages to get dropped as overflowStrategy == OverflowStrategy.DropOldest and stashLimit is 2
      stasher ! Push(MyCommand3)
      myCommandProcessor.expectMsg(CommandDropped(MyCommand1))
      stasher ! Push(MyCommand4)
      myCommandProcessor.expectMsg(CommandDropped(MyCommand2))
    }

    "dedicated stasher demo" in {

      import DedicatedStasherCommand._

      sealed trait Command
      case object MyCommand1 extends Command
      case object MyCommand2 extends Command
      case object MyCommand3 extends Command
      case object MyCommand4 extends Command
      case object MyCommandToSkip extends Command
      case class CommandDropped(command: Command) extends Command
      case object Stop extends Command

      val myCommandProcessor = TestProbe[Command]("myCommandProcessor")

      val stasher =
        Stasher.dedicated[Command](
          onCommandDropped = myCommandProcessor.ref ! CommandDropped(_),
          stopCommand = Stop,
          stashLimit = 2,
          overflowStrategy = OverflowStrategy.DropOldest,
          replyTo = myCommandProcessor.ref,
          skipStash = _ == MyCommandToSkip
        ).createActor


      stasher ! Push(MyCommand1)
      stasher ! Push(MyCommand2)
      stasher ! Pop
      stasher ! Pop
      myCommandProcessor.expectMsg(MyCommand1)
      myCommandProcessor.expectMsg(MyCommand2)

      stasher ! Push(MyCommand1)
      stasher ! Push(MyCommand2)
      stasher ! PopAll(condition = (_: Command) => true)
      myCommandProcessor.expectMsg(MyCommand1)
      myCommandProcessor.expectMsg(MyCommand2)

      stasher ! Push(MyCommand1)
      stasher ! Push(MyCommandToSkip)
      myCommandProcessor.expectMsg(MyCommandToSkip)
      stasher ! Push(MyCommand2)
      stasher ! Clear(onClear = myCommandProcessor.ref ! _)
      myCommandProcessor.expectMsg(MyCommand1)
      myCommandProcessor.expectMsg(MyCommand2)

      stasher ! Push(MyCommand1)
      stasher ! Push(MyCommand2)
      //expect oldest messages to get dropped as overflowStrategy == OverflowStrategy.DropOldest and stashLimit is 2
      stasher ! Push(MyCommand3)
      myCommandProcessor.expectMsg(CommandDropped(MyCommand1))
      stasher ! Push(MyCommand4)
      myCommandProcessor.expectMsg(CommandDropped(MyCommand2))
      stasher ! Clear(onClear = myCommandProcessor.ref ! _)
      myCommandProcessor.expectMsg(MyCommand3)
      myCommandProcessor.expectMsg(MyCommand4)


      stasher ! Push(MyCommand1)
      stasher ! Push(MyCommand2)
      myCommandProcessor.expectNoMsg(1 seconds)
      //turn off the stashing and check that all existing commands and new incoming commands get forwarded to the replyTo actor
      stasher ! Off
      myCommandProcessor.expectMsg(MyCommand1)
      myCommandProcessor.expectMsg(MyCommand2)
      stasher ! Push(MyCommand3)
      myCommandProcessor.expectMsg(MyCommand3)
      stasher ! Push(MyCommand4)
      myCommandProcessor.expectMsg(MyCommand4)


      //turn stashing back on
      stasher ! On
      stasher ! Push(MyCommand1)
      stasher ! Push(MyCommand2)
      myCommandProcessor.expectNoMsg(1 seconds)
      stasher ! Push(MyCommand3)
      myCommandProcessor.expectMsg(CommandDropped(MyCommand1))
      stasher ! PopAll(condition = (_: Command) => true)
      myCommandProcessor.expectMsg(MyCommand2)
      myCommandProcessor.expectMsg(MyCommand3)
      stasher ! Push(MyCommand4)
      stasher ! Pop
      myCommandProcessor.expectMsg(MyCommand4)
    }
  }

}
