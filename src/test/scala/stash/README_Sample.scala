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
//      val actor: ActorRef[MyCommand] = ???
//      val dedicatedStash = Stash.dedicated(actor)
//
//      //processor actor gets the Stash instance
//      def stashedCommandProcessor(stash: ActorRef[DedicatedStashCommand[MyCommand]]) =
//        Actor.immutable[MyCommand] {
//          (ctx, command) =>
//            //Process stashed messages when ready
//            stash ! Pop()
//            Actor.same
//        }
//
//      //plug returns a Behavior that wil only accept Push Command.
//      //Restrict outside actors to only Push commands into the Stash.
//      val plugStash: Behavior[Push[MyCommand]] = Stash.plug(stashedCommandProcessor)
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
//
//      val stash: Behavior[StashCommand[String]] =
//        Stash[String](
//          fifoStashLimit = 100,
//          popLastStashLimit = 100,
//          fixedStashLimit = 100,
//          //executed when the stash limit is reached
//          onCommandDropped = (message: String) => println(message),
//          //Some messages may have replyTo ActorRef. The Stash can watch for these actor and remove the message
//          //if the replyTo actor is terminated
//          watchAndRemove = (message: String) => None,
//          //maps messages to their target stashes
//          stashMapping = {
//            message: String =>
//              message match {
//                //Is skipped
//                case "Skip it" => StashType.Skip
//                //Parent can
//                case "StopActor" => StashType.PopLast
//                //gets removed from the stash only when limit is reached. Useful for subscription based messages
//                case "Keep it in stash" => StashType.Fixed
//                //Like Fixed, but the processor actor receives a copy of the message initially.
//                case "Keep it in stash 2" => StashType.FixedTap
//                //default
//                case "First in first out" => StashType.FIFO
//              }
//          },
//          fifoOverflowStrategy = OverflowStrategy.DropNewest,
//          popLastOverflowStrategy = OverflowStrategy.DropOldest,
//          fixedOverflowStrategy = OverflowStrategy.DropNewest
//        )
//    }
//  }
//}
