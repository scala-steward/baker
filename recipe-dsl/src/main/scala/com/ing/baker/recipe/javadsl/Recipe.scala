package com.ing.baker.recipe.javadsl

import com.ing.baker.recipe.common

import scala.annotation.varargs
import scala.collection.JavaConverters._
import scala.concurrent.duration
import scala.concurrent.duration.Duration

case class Recipe(
    override val name: String,
    override val interactions: Seq[common.InteractionDescriptor],
    override val sieves: Seq[common.InteractionDescriptor],
    override val sensoryEvents: Set[common.Event],
    override val defaultFailureStrategy: common.InteractionFailureStrategy,
    override val eventReceivePeriod: Duration) extends common.Recipe {

  def this(name: String) = this(name, Seq.empty, Seq.empty, Set.empty, InteractionFailureStrategy.BlockInteraction(), Duration.Undefined)

  def getInteractions: java.util.List[common.InteractionDescriptor] = interactions.asJava

  def getSieves: java.util.List[common.InteractionDescriptor] = sieves.asJava

  def getEvents: java.util.List[common.Event] = sensoryEvents.toList.asJava

  /**
    * This adds all interactions and sieves of the recipe to this recipe
    * Sensory Events are not added and are expected to be given by the recipe itself
    *
    * @param recipe
    * @return
    */
  def withRecipe(recipe: common.Recipe) = {
    copy(interactions = interactions ++ recipe.interactions, sieves = sieves ++ recipe.sieves)
  }

  /**
    * Adds the interaction to the recipe.
    * To get a JInteractionDescriptor from a JInteraction call the of method on JInteractionDescriptor
    *
    * @param newInteraction the interaction to add
    * @return
    */
  def withInteraction(newInteraction: common.InteractionDescriptor): Recipe =
    withInteractions(Seq(newInteraction): _*)

  /**
    * Adds the interactions to the recipe.
    * To get a JInteractionDescriptor from a JInteraction call the of method on JInteractionDescriptor
    *
    * @param newInteractions The interactions to add
    * @return
    */
  @SafeVarargs
  @varargs
  def withInteractions(newInteractions: common.InteractionDescriptor*): Recipe =
  copy(interactions = interactions ++ newInteractions)

  /**
    * Adds a sieve function to the recipe.
    *
    * @param sieveDescriptor
    * @return
    */
  def withSieve(sieveDescriptor: common.InteractionDescriptor): Recipe =
    withSieves(Seq(sieveDescriptor.asInstanceOf[InteractionDescriptor]): _*)

  /**
    * Adds a sieves function to the recipe.
    *
    * @param newSieves
    * @return
    */
  @SafeVarargs
  @varargs
  def withSieves(newSieves: common.InteractionDescriptor*): Recipe = {
    copy(sieves = sieves ++ newSieves)
  }

  /**
    * Adds the sensory event to the recipe
    * The firing limit is set to 1 by default
    * @param newEvent
    * @return
    */
  def withSensoryEvent(newEvent: Class[_]): Recipe =
    withSensoryEvents(newEvent)

  /**
    * Adds the sensory event to the recipe
    * The firing limit is set to what is given
    * @param newEvent
    * @param maxFiringLimit
    * @return
    */
  def withSensoryEvent(newEvent: Class[_], maxFiringLimit: Int): Recipe =
    copy(sensoryEvents = sensoryEvents + eventClassToCommonEvent(newEvent, Some(maxFiringLimit)))

  /**
    * Adds the sensory events to the recipe with the firing limit set to 1
    *
    * @param eventsToAdd
    * @return
    */
  @SafeVarargs
  @varargs
  def withSensoryEvents(eventsToAdd: Class[_]*): Recipe =
  copy(sensoryEvents = sensoryEvents ++ eventsToAdd.map(eventClassToCommonEvent(_, Some(1))))

  /**
    * Adds the sensory event to the recipe with firing limit set to unlimited
    *
    * @param newEvent
    * @return
    */
  def withSensoryEventNoFiringLimit(newEvent: Class[_]): Recipe =
    withSensoryEventsNoFiringLimit(newEvent)


  /**
    * Adds the sensory events to the recipe with firing limit set to unlimited
    *
    * @param eventsToAdd
    * @return
    */
  @SafeVarargs
  @varargs
  def withSensoryEventsNoFiringLimit(eventsToAdd: Class[_]*): Recipe =
  copy(sensoryEvents = sensoryEvents ++ eventsToAdd.map(eventClassToCommonEvent(_, None)))


  /**
    * This set the failure strategy as default for this recipe.
    * If a failure strategy is set for the Interaction itself that is taken.
    *
    * @param interactionFailureStrategy The failure strategy to follow
    * @return
    */
  def withDefaultFailureStrategy(interactionFailureStrategy: common.InteractionFailureStrategy): Recipe =
    copy(defaultFailureStrategy = interactionFailureStrategy)

  /**
    * This actives the incremental backup retry strategy for all the interactions if failure occurs
    *
    * @param initialDelay the initial delay before the first retry starts
    * @param deadline     the deadline for how long the retry should run
    * @return
    * @deprecated Replaced by withDefaultFailureStrategy
    */
  @Deprecated
  def withDefaultRetryFailureStrategy(initialDelay: java.time.Duration,
                                      deadline: java.time.Duration,
                                      maxTimeBetweenRetries: java.time.Duration): Recipe =
    copy(
      defaultFailureStrategy =
        InteractionFailureStrategy.RetryWithIncrementalBackoff(
          initialDelay,
          deadline,
          maxTimeBetweenRetries))

  /**
    * This actives the incremental backup retry strategy for all the interactions if failure occurs
    *
    * @param initialDelay the initial delay before the first retry starts
    * @param deadline     the deadline for how long the retry should run
    * @return
    * @deprecated Replaced by withDefaultFailureStrategy
    */
  @Deprecated
  def withDefaultRetryFailureStrategy(initialDelay: java.time.Duration,
                                      deadline: java.time.Duration) =
  copy(
    defaultFailureStrategy =
      InteractionFailureStrategy.RetryWithIncrementalBackoff(
        initialDelay,
        deadline))

  /**
    * Sets the event receive period. This is the period for which processes can receive sensory events.
    *
    * @param recivePeriod The period
    * @return
    */
  def withEventReceivePeriod(recivePeriod: java.time.Duration) =
    copy(eventReceivePeriod = Duration(recivePeriod.toMillis, duration.MILLISECONDS))
}
