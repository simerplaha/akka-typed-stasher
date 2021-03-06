//package stash
//
//import akka.typed._
//import akka.typed.scaladsl._
//import org.scalatest.{Matchers, WordSpec}
//import stash.DedicatedStashCommand._
//import stash.TestActorSystem._
//
//class Demo extends WordSpec with Matchers {
//
//  sealed trait MyCommand
//
//  "" should {
//
//    "" in {
//      val stash = Stash[MyCommand]()
//
//      val processorActor: ActorRef[MyCommand] = ???
//      val dedicatedStash = Stash.dedicated(processorActor)
//
//      //processor actor gets the Stash instance
//      def processorBehavior(stash: ActorRef[DedicatedStashCommand[MyCommand]]) =
//        Actor.immutable[MyCommand] {
//          (ctx, command) =>
//            //Pop messages from the stash when ready
//            stash ! Pop()
//            Actor.same
//        }
//
//      //plug returns a Behavior that wil only accept Push Command.
//      //Restrict outside actors to only Push commands into the Stash.
//      val plugStash: Behavior[Push[MyCommand]] = Stash.plug(processorBehavior)
//    }
//
//    "deliver stashed messages in order" in {
//      val messageProcessorActor: ActorRef[String] = ???
//      val stash = Stash.dedicated(messageProcessorActor).createActor
//
//      stash ! Push("message")
//      stash ! Pop()
//      stash ! Pop(condition = (command: String) => true)
//
//      //Off/On a stash type
//      stash ! Off(StashType.FIFO)
//      stash ! On(StashType.FIFO)
//
//      //Off/On all stashes
//      stash ! Off()
//      stash ! On()
//
//      stash ! ClearStash(StashType.FIFO)
//
//      stash ! Clear(condition = (command: String) => true)
//
//      stash ! Iterate(next = (command: String) => Unit)
//    }
//
//    "Multiple stashed" in {
//      val stash =
//        Stash[String](
//          fifoOverflowStrategy = OverflowStrategy.DropNewest(limit = 10),
//          popLastOverflowStrategy = OverflowStrategy.DropOldest(limit = 10),
//          fixedOverflowStrategy = OverflowStrategy.DropNewest(limit = 10),
//          //executed when the stash limit is reached. Can be used to reply to the sender of the failure.
//          onCommandDropped = (message: String) => println(message),
//          //Some messages may have replyTo ActorRef. Stash can watch for these actor and remove the message
//          //if the replyTo actor is terminated
//          watchAndRemove = (message: String) => None,
//          //maps messages to their target stashes
//          stashMapping = {
//            message: String =>
//              message match {
//                case "Skip it" => StashType.Skip
//                //Other actors send commands to stop the processor actor. This StashType
//                //can be used to make sure that Stop commands are processed only if FIFO
//                //is empty
//                case "Stop actor" => StashType.PopLast
//                //gets removed from the stash only when limit is reached. Useful for subscription based messages.
//                //where clients are subscribed to state changes in an actor.
//                case "Keep it in stash" => StashType.Fixed
//                //Like Fixed, but the processor actor also receives this message initially.
//                case "Keep it in stash 2" => StashType.FixedTap
//                //default
//                case "First in first out" => StashType.FIFO
//              }
//          }
//        )
//    }
//  }
//}
