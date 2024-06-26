package com.ing.baker.runtime.common

import com.ing.baker.il.CompiledRecipe
import com.ing.baker.il.failurestrategy.ExceptionStrategyOutcome
import com.ing.baker.runtime.common.LanguageDataStructures.LanguageApi

// TODO: rename subtypes of BakerEvent to resamble the new names
trait BakerEvent extends LanguageApi {
}

/**
  * Event describing the fact that a sensory event was received for a process.
  */
trait EventReceived extends BakerEvent {
  val timeStamp: Long
  val recipeName: String
  val recipeId: String
  val recipeInstanceId: String
  val correlationId: language.Option[String]
  val eventName: String
}

/**
  * Event describing the fact that an sensory event was received but rejected for a process
  */
trait EventRejected extends BakerEvent {
  val timeStamp: Long
  val recipeInstanceId: String
  val correlationId: language.Option[String]
  val eventName: String
  val reason: RejectReason
}

/**
  * Event describing the fact that an interaction outcome event was fired for a process
  */
trait EventFired extends BakerEvent {
  val timeStamp: Long
  val recipeName: String
  val recipeId: String
  val recipeInstanceId: String
  val eventName: String
}

/**
  * Event describing the fact that an interaction failed during execution
  */
trait InteractionFailed extends BakerEvent {
  val timeStamp: Long
  val duration: Long
  val recipeName: String
  val recipeId: String
  val recipeInstanceId: String
  val interactionName: String
  val failureCount: Int
  val errorMessage: String
  val exceptionStrategyOutcome: ExceptionStrategyOutcome
}

/**
  * Event describing the fact that an interaction has started executing
  */
trait InteractionStarted extends BakerEvent {
  val timeStamp: Long
  val recipeName: String
  val recipeId: String
  val recipeInstanceId: String
  val interactionName: String
}

/**
  * Event describing the fact that an interaction was executed successfully
  */
trait InteractionCompleted extends BakerEvent {
  val timeStamp: Long
  val duration: Long
  val recipeName: String
  val recipeId: String
  val recipeInstanceId: String
  val interactionName: String
  val eventName: language.Option[String]
}

/**
  * Event describing the fact that a baker process was created
  */
trait RecipeInstanceCreated extends BakerEvent {
  val timeStamp: Long
  val recipeId: String
  val recipeName: String
  val recipeInstanceId: String
}

/**
  * An event describing the fact that a recipe was added to baker.
  */
trait RecipeAdded extends BakerEvent {
  val recipeName: String
  val recipeId: String
  val date: Long
  val compiledRecipe: CompiledRecipe
}
