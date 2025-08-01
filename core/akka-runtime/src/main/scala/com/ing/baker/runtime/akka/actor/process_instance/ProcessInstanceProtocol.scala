package com.ing.baker.runtime.akka.actor.process_instance

import akka.actor.ActorRef
import com.ing.baker.il.petrinet.Place
import com.ing.baker.petrinet.api._
import com.ing.baker.runtime.akka.actor.serialization.BakerSerializable
import com.ing.baker.runtime.scaladsl.RecipeInstanceState
import com.ing.baker.types.Value

/**
 * Describes the messages to and from a PetriNetInstance actor.
 */
object ProcessInstanceProtocol {

  /**
   * A common trait for all commands to a petri net instance.
   */
  sealed trait Command extends BakerSerializable

  /**
   * Command to request the current state of the petri net instance.
   */
  case object GetState extends Command

  /**
    * Command to request the current ingredient
    */
  case class GetIngredient(name: String) extends Command

  case class IngredientFound(value: Value) extends Response

  case object IngredientNotFound extends Response

  /**
    * Command to stop and optionally delete the process instance.
    */
  case class Stop(delete: Boolean = false) extends Command

  /**
    * Command to add Ingredient
    */
  case class AddMetaData(metaData: Map[String, String]) extends Command

  object Initialize {

    def apply[P : Identifiable](marking: Marking[Place]): Initialize = Initialize(marking.marshall, null)

    def apply[P : Identifiable](marking: Marking[Place], state: RecipeInstanceState): Initialize = Initialize(marking.marshall, state)
  }

  /**
   * Command to initialize a petri net instance.
   */
  case class Initialize(marking: Marking[Id], state: Any) extends Command

  /**
   * Command to fire a specific transition with input.
   */
  case class FireTransition(
    transitionId: Long,
    input: Any,
    correlationId: Option[String] = None) extends Command

  case class TransitionDelayed(
    jobId: Long,
    transitionId: Id,
    consumed: Marking[Id])  extends Command

  case class DelayedTransitionFired(
    jobId: Long,
    transitionId: Id,
    eventToFire: String)  extends Command

  /**
    * Overrides the chosen exception strategy of a job (running transition)
    *
    * @param jobId The id of the job.
    * @param failureStrategy The new failure strategy
    */
  case class OverrideExceptionStrategy(jobId: Long, failureStrategy: ExceptionStrategy) extends Command

  /**
   * A common trait for all responses coming from a petri net instance.
   */
  sealed trait Response extends BakerSerializable

  /**
   * A response send in case any other command then 'Initialize' is sent to the actor in initialized state.
   *
   * @param recipeInstanceId The identifier of the uninitialized actor.
   */
  case class Uninitialized(recipeInstanceId: String) extends Response

  /**
   * Custom termination message with optional replyTo actor ref in case not only the parent needs to be
   * informed of node termination.
   *
   * @param actorRef        The node that terminated.
   * @param replyToWhenDone An optional node to inform. This is just a ref you need to send a message to this actor yourself.
   */
  case class TerminatedWithReplyTo(actorRef: ActorRef, replyToWhenDone: Option[ActorRef] = None) extends Response

  /**
   * Returned in case a second Initialize is send after a first is processed
   */
  case class AlreadyInitialized(recipeInstanceId: String) extends Response

  /**
    * Indicates that the received FireTransition command with a specific correlation id was already received.
    */
  case class AlreadyReceived(correlationId: String) extends Response

  case object MetaDataAdded extends Response

  object Initialized {

    def apply[P : Identifiable](marking: Marking[Place]): Initialized = Initialized(marking.marshall, null)

    def apply[P : Identifiable](marking: Marking[Place], state: Any): Initialized = Initialized(marking.marshall, state)
  }

  /**
   * A response indicating that the instance has been initialized in a certain state.
   *
   * This message is only send in response to an Initialize message.
   */
  case class Initialized(
    marking: Marking[Id],
    state: Any) extends Response

  /**
   * Any message that is a response to a FireTransition command.
   */
  sealed trait TransitionResponse extends Response {
    val transitionId: Long
  }

  /**
   * Response indicating that a transition has fired successfully
   */
  case class TransitionFired(
    jobId: Long,
    override val transitionId: Id,
    correlationId: Option[String],
    consumed: Marking[Id],
    produced: Marking[Id],
    newJobsIds: Set[Long],
    output: Any) extends TransitionResponse

  /**
   * Response indicating that a transition has failed.
   */
  case class TransitionFailed(
    jobId: Long,
    override val transitionId: Id,
    correlationId: Option[String],
    consume: Marking[Id],
    input: Any,
    reason: String,
    strategy: ExceptionStrategy) extends TransitionResponse

  /**
   * Response indicating that the transition could not be fired because it is not enabled.
   */
  case class TransitionNotEnabled(
    override val transitionId: Id,
    reason: String) extends TransitionResponse

  /**
    * General response indicating that the send command was invalid.
    *
    * @param reason The invalid reason.
    */
  case class InvalidCommand(reason: String) extends Response

  /**
   * The exception state of a transition.
   */
  case class ExceptionState(
    failureCount: Int,
    failureReason: String,
    failureStrategy: ExceptionStrategy)

  sealed trait ExceptionStrategy {

    def isBlock: Boolean

    def isRetry: Boolean

    def isContinue: Boolean
  }

  object ExceptionStrategy {

    case object BlockTransition extends ExceptionStrategy {

      def isBlock: Boolean = true

      def isRetry: Boolean = false

      def isContinue: Boolean = false
    }

    case class RetryWithDelay(delay: Long) extends ExceptionStrategy {
      require(delay >= 0, "Delay must be greater then zero")

      def isBlock: Boolean = false

      def isRetry: Boolean = true

      def isContinue: Boolean = false
    }

    case class Continue(marking: Marking[Id], output: Any) extends ExceptionStrategy {

      def isBlock: Boolean = false

      def isRetry: Boolean = false

      def isContinue: Boolean = true
    }

    case class ContinueAsFunctional(marking: Marking[Id], output: Any) extends ExceptionStrategy {

      def isBlock: Boolean = false

      def isRetry: Boolean = false

      def isContinue: Boolean = true
    }
  }

  /**
   * Response containing the state of the `Job`.
   */
  case class JobState(
      id: Long,
      transitionId: Long,
      consumedMarking: Marking[Id],
      input: Any,
      exceptionState: Option[ExceptionState]) {

    def isActive: Boolean = exceptionState match {
      case Some(ExceptionState(_, _, ExceptionStrategy.RetryWithDelay(_))) => true
      case None                                          => true
      case _                                             => false
    }
  }

  /**
   * Response containing the state of the process.
   */
  case class InstanceState(
    sequenceNr: Long,
    marking: Marking[Id],
    state: Any,
    jobs: Map[Long, JobState]) extends Response
}
