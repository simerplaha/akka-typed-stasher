# Simple message stashing akka-typed behaviour

[Test cases - StasherTest.scala](src/test/scala/stasher/test/StasherTest.scala)

```scala
import stasher.test.TestActorSystem._
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
stasher ! PopAll(condition = _ => true)
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

```

