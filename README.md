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
//processor actor gets the Stash instance
def stashedCommandProcessor(stash: ActorRef[DedicatedStashCommand[MyCommand]]) =
    Actor.immutable[MyCommand] {
      (ctx, command) =>
        //Process stashed messages when ready
        stash ! Pop()
        Actor.same
    }

//plug returns a Behavior that wil only accept Push Command. 
//Restrict outside actors to only Push commands into the Stash.  
val plugStash: Behavior[Push[MyCommand]] = Stash.plug(stashedCommandProcessor)
```

## StashType
1. `FIFO` - first in, first out delivery
2. `PopLast` - Delivered only when `FIFO` messages is empty. Eg: `StopActorCommand`
3. `Fixed` - Permanently stored in the `Stash` until cleared or removed
4. `FixedTap` - Like `Fixed` but initially delivered to the dedicated Stash.
4. `Skip` - Delivered as their arrive

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
