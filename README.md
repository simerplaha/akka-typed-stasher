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
Used as a plug when the outside actor should not send messages to the processor actor directly, 
but submit them to the Stash for the processor to process them later.
```scala
def stashedCommandProcessor(stash: ActorRef[DedicatedStashCommand[MyCommand]]) =
    Actor.immutable[MyCommand] {
      (ctx, command) =>
        //Delivers the next messages in Stash to this actor.
        stash ! Pop()
        Actor.same
    }

//plug returns a Behavior that wil only accept Push Command. 
//Restrict outside actors to only Push commands into the Stash.  
val plugStash: Behavior[Push[MyCommand]] = Stash.plug(stashedCommandProcessor)
val out
```

## StashType
Messages can be mapped to different `StashType`s in a `Stash`. Default `StashType` is `FIFO`

1. `FIFO` - Messages get delivered on first in, first out basis
2. `PopLast` - Delivered only when `FIFO` messages is empty Eg: `StopActorCommand` 
3. `Fixed` - Always kept in the `Stash` until cleared, removed or `Fixed` stash limit reached. 
Used for subscription based commands that expect responses for changes in an Actor's state.  
4. `FixedTap` - Like `Fixed` but delivered initially
4. `Skip` - Delivered as they arrive

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

//Off/On all stashes
stash ! Off()
stash ! On()

stash ! ClearStash(StashType.FIFO)

stash ! Clear(condition = (command: String) => true)

stash ! Iterate(next = (command: String) => Unit)
```

## Example Stash config
```scala
val stash =
    Stash[String](
      fifoStashLimit = 100,
      popLastStashLimit = 100,
      fixedStashLimit = 100,
      //executed when the stash limit is reached
      onCommandDropped = (message: String) => println(message),
      //Some messages may have replyTo ActorRef. The Stash can watch for these actor and remove the message
      //if the replyTo actor is terminated
      watchAndRemove = (message: String) => None,
      //maps messages to their target stashes
      stashMapping = {
        message: String =>
          message match {
            //Is skipped
            case "Skip it" => StashType.Skip
            //Parent can
            case "StopActor" => StashType.PopLast
            //gets removed from the stash only when limit is reached. Useful for subscription based messages
            case "Keep it in stash" => StashType.Fixed
            //Like Fixed, but the processor actor receives a copy of the message initially.
            case "Keep it in stash 2" => StashType.FixedTap
            //default
            case "First in first out" => StashType.FIFO
          }
      },
      fifoOverflowStrategy = OverflowStrategy.DropNewest,
      popLastOverflowStrategy = OverflowStrategy.DropOldest,
      fixedOverflowStrategy = OverflowStrategy.DropNewest
    )
```

[Test cases](src/test/scala/stash)
