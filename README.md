# Simple message stashing akka-typed behaviour

[Test cases - StasherTest.scala](src/test/scala/stasher/test/StasherTest.scala)

```scala
sealed trait Command
case object MyCommand1 extends Command
case object MyCommand2 extends Command
case object Stop extends Command

val stasher =
Stasher.start[Command](
  onCommandDrop = println,
  stopCommand = Stop,
  stashLimit = 5,
  dropStrategy = DropStrategy.DropOldest
).createActor

val myCommandProcessor = TestProbe[Command]("myCommandProcessor")

stasher ! Push(MyCommand1)
stasher ! Push(MyCommand2)

stasher ! Pop(replyTo = myCommandProcessor.ref)
stasher ! Pop(replyTo = myCommandProcessor.ref)

myCommandProcessor.expectMsg(MyCommand1)
myCommandProcessor.expectMsg(MyCommand2)

stasher ! Push(MyCommand1)
stasher ! Push(MyCommand2)

stasher ! PopAll(replyTo = myCommandProcessor.ref, _ => true)

myCommandProcessor.expectMsg(MyCommand1)
myCommandProcessor.expectMsg(MyCommand2)

stasher ! Push(MyCommand1)
stasher ! Push(MyCommand2)

stasher ! Clear(onClear = myCommandProcessor.ref ! _)

myCommandProcessor.expectMsg(MyCommand1)
myCommandProcessor.expectMsg(MyCommand2)
```

