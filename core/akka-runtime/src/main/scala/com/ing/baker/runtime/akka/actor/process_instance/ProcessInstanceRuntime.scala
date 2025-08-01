package com.ing.baker.runtime.akka.actor.process_instance

import java.io.{PrintWriter, StringWriter}
import cats.data.State
import cats.effect.IO
import com.ing.baker.il.petrinet.{Place, Transition}
import com.ing.baker.petrinet.api._
import com.ing.baker.runtime.akka._
import com.ing.baker.runtime.akka.actor.process_instance.ProcessInstanceEventSourcing._
import com.ing.baker.runtime.akka.actor.process_instance.internal.ExceptionStrategy.BlockTransition
import com.ing.baker.runtime.akka.actor.process_instance.internal.{ExceptionStrategy, Instance, Job}
import com.ing.baker.runtime.common.RemoteInteractionExecutionException
import com.ing.baker.runtime.scaladsl.{EventInstance, RecipeInstanceState}
import com.typesafe.scalalogging.LazyLogging
import org.slf4j.{Logger, LoggerFactory}

/**
  * Encapsulates all components required to 'run' a Petri net instance
  *
  * @tparam P The place type
  * @tparam T The transition type
  * @tparam S The state type
  * @tparam E The event type
  */
trait ProcessInstanceRuntime extends LazyLogging {

  val log: Logger = LoggerFactory.getLogger("com.ing.baker.runtime.core.actor.process_instance.ProcessInstanceRuntime")

  /**
    * The event source function for the state associated with a process instance.
    *
    * By default the identity function is used.
    */
  val eventSource: (Long, Transition) => RecipeInstanceState => EventInstance => RecipeInstanceState = (_, _) => s => _ => s

  /**
   * This function is called when a transition throws an exception.
   *
   * By default the transition is blocked.
   */
  def handleException(job: Job[RecipeInstanceState])(throwable: Throwable, failureCount: Int, startTime: Long, outMarking: MultiSet[Place]): ExceptionStrategy = BlockTransition

  /**
   * Returns the task that should be executed for a transition.
   */
  def transitionTask(petriNet: PetriNet, t: Transition)(marking: Marking[Place], state: RecipeInstanceState, input: Any): IO[(Marking[Place], EventInstance)]

  /**
    * Checks if a transition can be fired automatically by the runtime (not triggered by some outside input).
    *
    * By default, cold transitions (without in adjacent places) are not fired automatically
    */
  def canBeFiredAutomatically(instance: Instance[RecipeInstanceState], t: Transition): Boolean = instance.petriNet.incomingPlaces(t).nonEmpty

  /**
   * Defines which tokens from a marking for a particular place are consumable by a transition.
   *
   * By default ALL tokens from that place are consumable.
   *
   * You can override this for example in case you use a colored (data) petri net model with filter rules on the edges.
   */
  def consumableTokens(petriNet: PetriNet)(marking: Marking[Place], p: Place, t: Transition): MultiSet[Any] = marking.getOrElse(p, MultiSet.empty)

  /**
   * Takes a Job specification, executes it and returns a TransitionEvent (asychronously using cats.effect.IO)
   *
   * TODO
   *
   * The use of cats.effect.IO is not really necessary at this point. It was mainly chosen to support cancellation in
   * the future: https://typelevel.org/cats-effect/datatypes/io.html#cancelable-processes
   *
   * However, since that is not used this can be refactored to a simple function: Job -> TransitionEvent
   *
   */
  def jobExecutor(topology: PetriNet)(implicit transitionIdentifier: Identifiable[Transition], placeIdentifier: Identifiable[Place]): Job[RecipeInstanceState] => IO[TransitionEvent] = {

    def exceptionStackTrace(e: Throwable): String = e match {
      case _: RemoteInteractionExecutionException => e.getMessage
      case _ =>
        val sw = new StringWriter()
        e.printStackTrace(new PrintWriter(sw))
        sw.toString
    }

    job => {

      val startTime = System.currentTimeMillis()
      val transition = job.transition
      val consumed: Marking[Id] = job.consume.marshall

      IO.unit.flatMap {_ =>
        // calling transitionTask(...) could potentially throw an exception
        // TODO I don't believe the last statement is true
        transitionTask(topology, transition)(job.consume, job.processState, job.input)
      }.map {
        case (producedMarking, out) =>
          TransitionFiredEvent(job.id, transition.getId, job.correlationId, startTime, System.currentTimeMillis(), consumed, producedMarking.marshall, out)
      }.handleException {
        // In case an exception was thrown by the transition, we compute the failure strategy and return a TransitionFailedEvent
        case e: Throwable =>
          val failureCount = job.failureCount + 1
          val failureStrategy = handleException(job)(e, failureCount, startTime, topology.outMarking(transition))
          TransitionFailedEvent(job.id, transition.getId, job.correlationId, startTime, System.currentTimeMillis(), consumed, job.input, exceptionStackTrace(e), failureStrategy)
      }.handleException {
        // If an exception was thrown while computing the failure strategy we block the interaction from firing
        case e: Throwable =>
          logger.error(s"Exception while handling transition failure", e)
          TransitionFailedEvent(job.id, transition.getId, job.correlationId, startTime, System.currentTimeMillis(), consumed, job.input, exceptionStackTrace(e), ExceptionStrategy.BlockTransition)
      }
    }
  }

  def enabledParameters(petriNet: PetriNet)(m: Marking[Place]): Map[Transition, Iterable[Marking[Place]]] =
    enabledTransitions(petriNet)(m).view.map(t => t -> consumableMarkings(petriNet)(m, t)).toMap

  def consumableMarkings(petriNet: PetriNet)(marking: Marking[Place], t: Transition): Iterable[Marking[Place]] = {
    // TODO this is not the most efficient, should break early when consumable tokens < edge weight
    val consumable = petriNet.inMarking(t).map {
      case (place, count) => (place, count, consumableTokens(petriNet)(marking, place, t))
    }

    // check if any any places have an insufficient number of tokens
    if (consumable.exists {case (_, count, tokens) => tokens.multisetSize < count})
      Seq.empty
    else {
      val consume = consumable.map {
        case (place, count, tokens) => place -> MultiSet.copyOff[Any](tokens.allElements.take(count))
      }.toMarking

      // TODO lazily compute all permutations instead of only providing the first result
      Seq(consume)
    }
  }

  /**
   * Checks whether a transition is 'enabled' in a marking.
   */
  def isEnabled(petriNet: PetriNet)(marking: Marking[Place], t: Transition): Boolean = consumableMarkings(petriNet)(marking, t).nonEmpty

  /**
   * Returns all enabled transitions for a marking.
   */
  def enabledTransitions(petriNet: PetriNet)(marking: Marking[Place]): Iterable[Transition] =
    petriNet.transitions.filter(t => consumableMarkings(petriNet)(marking, t).nonEmpty)

  /**
   * Creates a job for a specific transition with input, computes the marking it should consume
   */
  def createJob(transition: Transition, input: Any, correlationId: Option[String] = None): State[Instance[RecipeInstanceState], Either[String, Job[RecipeInstanceState]]] =
    State {instance =>
      if (instance.isBlocked(transition))
        (instance, Left("Transition is blocked by a previous failure"))
      else
        enabledParameters(instance.petriNet)(instance.availableMarking).get(transition) match {
          case None =>
            (instance, Left(s"Not enough consumable tokens. This might have been caused because the event has already been fired up the the firing limit but the recipe requires more instances of the event, use withSensoryEventNoFiringLimit or increase the amount of firing limit on the recipe if such behaviour is desired"))
          case Some(params) =>
            val job = Job[RecipeInstanceState](instance.nextJobId(), correlationId, instance.state, transition, params.head, input)
            val updatedInstance = instance.copy[RecipeInstanceState](jobs = instance.jobs + (job.id -> job))
            (updatedInstance, Right(job))
        }
    }

  /**
    * Finds the (optional) first transition that is enabled and can be fired automatically
    */
  def firstEnabledJob: State[Instance[RecipeInstanceState], Option[Job[RecipeInstanceState]]] = State { instance =>
    enabledParameters(instance.petriNet)(instance.availableMarking).find {
      case (t, _) => !instance.isBlocked(t) && canBeFiredAutomatically(instance, t)
    }.map {
      case (t, markings) =>
        val job = Job[RecipeInstanceState](instance.nextJobId(), None, instance.state, t, markings.head, null)
        (instance.copy[RecipeInstanceState](jobs = instance.jobs + (job.id -> job)), Some(job))
    }.getOrElse((instance, None))
  }

  /**
   * Finds all automated enabled transitions.
   */
  def allEnabledJobs: State[Instance[RecipeInstanceState], Set[Job[RecipeInstanceState]]] =
    firstEnabledJob.flatMap {
      case None => State.pure(Set.empty)
      case Some(job) => allEnabledJobs.map(_ + job)
    }
}
