# Message stashing akka-typed behaviour

## Creating a Stash
```scala
val stash = Stash[MyCommand]()
```

## Stash tied to an actor
```scala
val actor: ActorRef[MyCommand] = ???
val dedicatedStash = Stash.dedicated(actor)
```

## Stash that spawn the processor actor
`Stash.plug` is used when the outside actors should not send messages to the processor actor directly, 
but submit them to the Stash instead which can be processed by the `processor` actor later.

Plug returns a `Behavior[Push[T]]` that accepts `Push` commands only. This restricts outside actors to only 
send `Push` commands to the `Stash`. 

The processor actor has access to all the Stash commands.

```scala
def stashedCommandProcessor(stash: ActorRef[DedicatedStashCommand[MyCommand]]) =
    Actor.immutable[MyCommand] {
      (ctx, command) =>
        stash ! Pop() //Delivers the next message in the Stash to this actor.
        Actor.same
    }
    
val plugStash: Behavior[Push[MyCommand]] = Stash.plug(stashedCommandProcessor)
```

## StashType
Messages can be mapped to different `StashType`s in a `Stash`. Default `StashType` is `FIFO`

1. `FIFO` - Messages get delivered on first in, first out basis
2. `PopLast` - Delivered only when `FIFO` messages is empty. Useful when an actor receive `StopActorCommand`
by another actor but the processor actor has `FIFO` messages that require processing before stopping the actor.  
3. `Fixed` - Always kept in the `Stash` until it's `Clear`ed, `Remove`d or the stash limit is reached. 
Useful for subscription based commands when the client wants to receive period updates of the state
of an Actor. `Iterator` command can be used to send the state to the clients. `Stash.watchAndRemove` can be used 
to automatically remove these subscription commands from the `Stash` if the client dies.
4. `FixedTap` - Like `Fixed` but delivered initially to the processor as an alert.
4. `Skip` - Do not get stashed and are delivered to the processor actor instantly.

## Dedicated and plug Stash commands
```scala
val messageProcessorActor: ActorRef[String] = ???
val stash = Stash.dedicated(messageProcessorActor).createActor

stash ! Push("message")
stash ! Pop()
stash ! Pop(condition = (command: String) => true)

//Off/On a stash type
stash ! Off(StashType.FIFO)
stash ! On(StashType.FIFO)

//Off all stashes. All messages get delivered to the processor as their arrive and do not get stashed.
stash ! Off()
//Continues stashing using the stashMapping provided on start.
stash ! On()

stash ! ClearStash(StashType.FIFO)

stash ! Clear(condition = (command: String) => true)

stash ! Iterate(next = (command: String) => Unit)
```

## Example Stash config
```scala
val stash =
    Stash[String](
      fifoOverflowStrategy = OverflowStrategy.DropNewest(limit = 100),
      popLastOverflowStrategy = OverflowStrategy.DropOldest(limit = 100),
      fixedOverflowStrategy = OverflowStrategy.DropNewest(limit = 100),
      //executed when the stash limit is reached. Can be used to reply to the sender of the failure.
      onCommandDropped = (message: String) => println(message),
      //Some messages may have replyTo ActorRef. Stash can watch for these actor and remove the message
      //if the replyTo actor is terminated
      watchAndRemove = (message: String) => None,
      //maps messages to their target stashes
      stashMapping = {
        message: String =>
          message match {
            case "Skip it" => StashType.Skip
            //Other actors send commands to stop the processor actor. This StashType
            //can be used to make sure that Stop commands are processed only if FIFO
            //is empty
            case "Stop actor" => StashType.PopLast
            //gets removed from the stash only when limit is reached. Useful for subscription based messages.
            //where clients are subscribed to state changes in an actor.
            case "Keep it in stash" => StashType.Fixed
            //Like Fixed, but the processor actor also receives this message initially.
            case "Keep it in stash 2" => StashType.FixedTap
            //default
            case "First in first out" => StashType.FIFO
          }
      }
    )
```

[Test cases](src/test/scala/stash)
