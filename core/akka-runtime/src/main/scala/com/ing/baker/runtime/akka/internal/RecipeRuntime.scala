package com.ing.baker.runtime.akka.internal

import akka.event.EventStream
import cats.effect.{ContextShift, IO}
import com.ing.baker.il
import com.ing.baker.il.failurestrategy.ExceptionStrategyOutcome
import com.ing.baker.il.petrinet.Place.IngredientPlace
import com.ing.baker.il.petrinet._
import com.ing.baker.il.{CompiledRecipe, IngredientDescriptor}
import com.ing.baker.petrinet.api.{MultiSet, _}
import com.ing.baker.runtime.akka.actor.logging.LogAndSendEvent
import com.ing.baker.runtime.akka.actor.process_instance.ProcessInstanceRuntime
import com.ing.baker.runtime.akka.actor.process_instance.internal.ExceptionStrategy.{BlockTransition, Continue, ContinueAsFunctionalEvent, RetryWithDelay}
import com.ing.baker.runtime.akka.actor.process_instance.internal._
import com.ing.baker.runtime.akka.internal.RecipeRuntime._
import com.ing.baker.runtime.model.InteractionManager
import com.ing.baker.runtime.scaladsl._
import com.ing.baker.types.{ListValue, PrimitiveValue, RecordValue, Value}

import java.lang.reflect.InvocationTargetException
import scala.collection.immutable.{Map, Seq}
import scala.concurrent.ExecutionContext

object RecipeRuntime {
  def recipeEventSourceFn: (Long, Transition) => RecipeInstanceState => EventInstance => RecipeInstanceState =
    (occurredOn, transition) => state => {
      case null => state
      case event: EventInstance =>
        state.copy(
          ingredients = state.ingredients ++ event.providedIngredients,
          events = state.events :+ EventMoment(event.name, occurredOn))
    }

  /**
    * Creates the produced marking (tokens) given the output (event) of the interaction.
    *
    * It fills a token in the out adjacent place of the transition with the interaction name
    */
  def createProducedMarking(outAdjacent: MultiSet[Place], event: Option[EventInstance]): Marking[Place] = {
    outAdjacent.keys.map { place =>

      // use the event name as a token value, otherwise null
      val value: Any = event.map(_.name).orNull

      place -> MultiSet.copyOff(Seq(value))
    }.toMarking
  }

  /**
    * Validates the output event of an interaction
    *
    * Returns an optional error message.
    */
  def validateInteractionOutput(interaction: InteractionTransition, optionalEvent: Option[EventInstance]): Option[String] = {

    optionalEvent match {

      // an event was expected but none was provided
      case None =>
        if (interaction.eventsToFire.nonEmpty)
          Some(s"Interaction '${interaction.interactionName}' did not provide any output, expected one of: ${interaction.eventsToFire.map(_.name).mkString(",")}")
        else
          None

      case Some(event) =>

        val nullIngredientNames = event.providedIngredients.collect {
          case (name, null) => name
        }

        // null values for ingredients are NOT allowed
        if (nullIngredientNames.nonEmpty)
          Some(s"Interaction '${interaction.interactionName}' returned null for the following ingredients: ${nullIngredientNames.mkString(",")}")
        else
        // the event name must match an event name from the interaction output
          interaction.originalEvents.find(_.name == event.name) match {
            case None =>
              Some(s"Interaction '${interaction.interactionName}' returned unknown event '${event.name}', expected one of: ${interaction.eventsToFire.map(_.name).mkString(",")}")
            case Some(eventType) =>
              val errors = event.validate(eventType)

              if (errors.nonEmpty)
                Some(s"Event '${event.name}' does not match the expected type: ${errors.mkString}")
              else
                None
          }
    }
  }


  /**
    * Creates the input parameters for an interaction implementation
    */
  def createInteractionInput(interaction: InteractionTransition, state: RecipeInstanceState): Seq[IngredientInstance] = {

    // the process id is a special ingredient that is always available
    val recipeInstanceId: (String, Value) = il.recipeInstanceIdName -> PrimitiveValue(state.recipeInstanceId)

    // Only map the recipeInstanceEventList if is it required, otherwise give an empty list
    val recipeInstanceEventList: (String, Value) =
      if(interaction.requiredIngredients.exists(_.name == il.recipeInstanceEventListName))
        il.recipeInstanceEventListName -> ListValue(state.events.map(e => PrimitiveValue(e.name)).toList)
      else
        il.recipeInstanceEventListName -> ListValue(List())

    val processId: (String, Value) = il.processIdName -> PrimitiveValue(state.recipeInstanceId)

    // a map of all ingredients, the order is important, the predefined parameters and recipeInstanceId have precedence over the state ingredients.
    val allIngredients: Map[String, Value] =
      state.ingredients ++
        interaction.predefinedParameters +
        recipeInstanceId +
        processId +
        recipeInstanceEventList

    // arranges the ingredients in the expected order
    interaction.requiredIngredients.map {
      case IngredientDescriptor(name, _) =>
        IngredientInstance(name, allIngredients.getOrElse(name, throw new FatalInteractionException(s"Missing parameter '$name'")))
    }
  }

  // function that (optionally) transforms the output event using the event output transformers
  def transformInteractionEvent(interaction: InteractionTransition, runtimeEvent: EventInstance): EventInstance = {
    interaction.eventOutputTransformers
      .find { case (eventName, _) => runtimeEvent.name.equals(eventName) } match {
      case Some((_, eventOutputTransformer)) =>
        EventInstance(
          eventOutputTransformer.newEventName,
          runtimeEvent.providedIngredients.map { case (name, value) => eventOutputTransformer.ingredientRenames.getOrElse(name, name) -> value })
      case None => runtimeEvent
    }
  }
}

class RecipeRuntime(recipe: CompiledRecipe, interactionManager: InteractionManager[IO], eventStream: EventStream)(implicit ec: ExecutionContext) extends ProcessInstanceRuntime {

  protected implicit lazy val contextShift: ContextShift[IO] = IO.contextShift(ec)

  /**
    * All transitions except sensory event interactions are auto-fireable by the runtime
    */
  override def canBeFiredAutomatically(instance: Instance[RecipeInstanceState], t: Transition): Boolean = t match {
    case EventTransition(_, true, _) => false
    case _ => true
  }

  /**
    * Tokens which are inhibited by the Edge filter may not be consumed.
    */
  override def consumableTokens(petriNet: PetriNet)(marking: Marking[Place], p: Place, t: Transition): MultiSet[Any] = {
    val edge = petriNet.findPTEdge(p, t).map(_.asInstanceOf[Edge]).get

    marking.get(p) match {
      case None => MultiSet.empty
      case Some(tokens) => tokens.filter { case (e, _) => edge.isAllowed(e) }
    }
  }

  override val eventSource: (Long, Transition) => RecipeInstanceState => EventInstance => RecipeInstanceState = recipeEventSourceFn

  override def handleException(job: Job[RecipeInstanceState])
                              (throwable: Throwable, failureCount: Int, startTime: Long, outMarking: MultiSet[Place]): ExceptionStrategy = {

    if (throwable.isInstanceOf[Error])
      BlockTransition
    else
      job.transition match {
        case interaction: InteractionTransition =>

          // compute the interaction failure strategy outcome
          val failureStrategyOutcome = interaction.failureStrategy.apply(failureCount)

          val currentTime = System.currentTimeMillis()

          LogAndSendEvent.interactionFailed(InteractionFailed(currentTime, currentTime - startTime, recipe.name, recipe.recipeId,
            job.processState.recipeInstanceId, job.transition.label, failureCount, throwable.toString, failureStrategyOutcome), throwable, eventStream)

          // translates the recipe failure strategy to a petri net failure strategy
          failureStrategyOutcome match {
            case ExceptionStrategyOutcome.BlockTransition => BlockTransition
            case ExceptionStrategyOutcome.RetryWithDelay(delay) => RetryWithDelay(delay)
            case ExceptionStrategyOutcome.Continue(eventName) =>
              val runtimeEvent = EventInstance(eventName, Map.empty)
              Continue(createProducedMarking(outMarking, Some(runtimeEvent)), runtimeEvent)
            case ExceptionStrategyOutcome.ContinueAsFunctionalEvent(eventName) =>
              val runtimeEvent = EventInstance(eventName, Map.empty)
              ContinueAsFunctionalEvent(createProducedMarking(outMarking, Some(runtimeEvent)), runtimeEvent)
          }

        case _ => BlockTransition
      }
  }

  override def transitionTask(petriNet: PetriNet, t: Transition)(marking: Marking[Place], state: RecipeInstanceState, input: Any): IO[(Marking[Place], EventInstance)] =
    t match {
      case interaction: InteractionTransition => interactionTask(interaction, petriNet.outMarking(t), state)
      case eventTransition: EventTransition =>
        if(input != null) {
          // Send EventFired for SensoryEvents
          LogAndSendEvent.eventFired(EventFired(System.currentTimeMillis(), recipe.name, recipe.recipeId, state.recipeInstanceId,  input.asInstanceOf[EventInstance].name), eventStream)
        }
        IO.pure(petriNet.outMarking(eventTransition).toMarking, input.asInstanceOf[EventInstance])
      case t => IO.pure(petriNet.outMarking(t).toMarking, null.asInstanceOf[EventInstance])
    }

  def interactionTask(interaction: InteractionTransition,
                      outAdjacent: MultiSet[Place],
                      processState: RecipeInstanceState): IO[(Marking[Place], EventInstance)] = {
    // returns a delayed task that will get executed by the process instance

    // create the interaction input
    val input = createInteractionInput(interaction, processState)

    val timeStarted = System.currentTimeMillis()

    // publish the fact that we started the interaction
    LogAndSendEvent.interactionStarted(InteractionStarted(timeStarted, recipe.name, recipe.recipeId, processState.recipeInstanceId, interaction.interactionName), eventStream)

    // executes the interaction and obtain the (optional) output event
    interactionManager.execute(interaction, input, Some(processState.recipeInstanceMetadata)).map { interactionOutput =>

      // validates the event, throws a FatalInteraction exception if invalid
      RecipeRuntime.validateInteractionOutput(interaction, interactionOutput).foreach { validationError =>
        throw new FatalInteractionException(validationError)
      }

      // transform the event if there is one
      val outputEvent: Option[EventInstance] = interactionOutput
        .map(e => transformInteractionEvent(interaction, e))

      val timeCompleted = System.currentTimeMillis()

      // publish the fact that the interaction completed
      LogAndSendEvent.interactionCompleted(InteractionCompleted(timeCompleted, timeCompleted - timeStarted, recipe.name, recipe.recipeId, processState.recipeInstanceId, interaction.interactionName, outputEvent.map(_.name)), eventStream)

      // create the output marking for the petri net
      val outputMarking: Marking[Place] = RecipeRuntime.createProducedMarking(outAdjacent, outputEvent)

      outputEvent.foreach { event: EventInstance =>
        // Send EventFired for Interaction output events
        LogAndSendEvent.eventFired(EventFired(timeCompleted, recipe.name, recipe.recipeId, processState.recipeInstanceId, event.name), eventStream)
      }

      val reproviderMarkings: Marking[Place] = if (interaction.isReprovider) {
        outAdjacent.toMarking.filter((input: (Place, MultiSet[Any])) => input._1.placeType == IngredientPlace)
      } else Map.empty

      (outputMarking ++ reproviderMarkings, outputEvent.orNull)
    }
  } handleErrorWith {
    case e: InvocationTargetException => IO.raiseError(e.getCause)
    case e: Throwable => IO.raiseError(e)
  }
}
