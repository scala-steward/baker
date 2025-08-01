package com.ing.baker.runtime.common

import com.ing.baker.il.{CompiledRecipe, RecipeVisualStyle}
import com.ing.baker.runtime.common.LanguageDataStructures.LanguageApi
import com.ing.baker.types.Value

/**
  * The BakerInterface is a class we use to ensure the Scala and Java Baker classes have the same methods.
  *
  * @tparam F the type of Future to use in the return types
  */
trait Baker[F[_]] extends LanguageApi {
  self =>

  type SensoryEventResultType <: SensoryEventResult {type Language <: self.Language}

  type EventResolutionsType <: EventResolutions[F] {type Language <: self.Language}

  type EventInstanceType <: EventInstance {type Language <: self.Language}

  type RecipeInstanceStateType <: RecipeInstanceState {type Language <: self.Language}

  type InteractionInstanceType <: InteractionInstance[F] {type Language <: self.Language}

  type InteractionInstanceDescriptorType <: InteractionInstanceDescriptor {type Language <: self.Language}

  type IngredientInstanceType <: IngredientInstance { type Language <: self.Language }

  type BakerEventType <: BakerEvent {type Language <: self.Language}

  type RecipeInstanceMetadataType <: RecipeInstanceMetadata {type Language <: self.Language}

  type RecipeInformationType <: RecipeInformation {type Language <: self.Language}

  type EventMomentType <: EventMoment { type Language <: self.Language}

  type RecipeMetadataType <: RecipeEventMetadata { type Language <: self.Language }

  type InteractionExecutionResultType <: InteractionExecutionResult { type Language <: self.Language }

  /**
    * Adds a recipe to baker and returns a recipeId for the recipe.
    *
    * This function is idempotent, if the same (equal) recipe was added earlier this will return the same recipeId
    *
    * @param compiledRecipe The compiled recipe.
    * @return A recipeId
    */
  def addRecipe(compiledRecipe: CompiledRecipe, timeCreated: Long, validate: Boolean): F[String] = addRecipe(RecipeRecord.of(compiledRecipe, updated = timeCreated, validate = validate))

  /**
    * Adds a recipe to baker and returns a recipeId for the recipe.
    *
    * This function is idempotent, if the same (equal) recipe was added earlier this will return the same recipeId
    *
    * @param compiledRecipe The compiled recipe.
    * @return A recipeId
    */
  def addRecipe(compiledRecipe: CompiledRecipe, validate: Boolean): F[String] = addRecipe(compiledRecipe, System.currentTimeMillis(), validate)

  /**
    * Adds recipe as a record
    * @param recipe
    * @return
    */
  def addRecipe(recipe: RecipeRecord): F[String]

  /**
    * Returns the recipe information for the given RecipeId
    *
    * @param recipeId
    * @return
    */
  def getRecipe(recipeId: String): F[RecipeInformationType]

  def getRecipeVisual(recipeId: String, style: RecipeVisualStyle = RecipeVisualStyle.default): F[String]

  /**
    * Returns all 'active' recipes added to this baker instance.
    *
    * @return All recipes in the form of map of recipeId -> CompiledRecipe
    */
  def getAllRecipes: F[language.Map[String, RecipeInformationType]]

  def getAllInteractions: F[language.Seq[InteractionInstanceDescriptorType]]

  def getInteraction(interactionName: String): F[language.Option[InteractionInstanceDescriptorType]]

  def executeSingleInteraction(interactionId : String, ingredients : language.Seq[IngredientInstanceType]): F[InteractionExecutionResultType]

  /**
    * Creates a process instance for the given recipeId with the given RecipeInstanceId as identifier
    *
    * @param recipeId  The recipeId for the recipe to bake
    * @param recipeInstanceId The identifier for the newly baked process
    * @return
    */
  def bake(recipeId: String, recipeInstanceId: String): F[Unit]


  /**
    * Creates a process instance for the given recipeId with the given RecipeInstanceId as identifier
    * This variant also gets a metadata map added on bake.
    * This is similar to calling addMetaData after doing the regular bake but depending on the implementation this can be more optimized.
    * @param recipeId         The recipeId for the recipe to bake
    * @param recipeInstanceId The identifier for the newly baked process
    * @param metadata
    * @return
    */
  def bake(recipeId: String, recipeInstanceId: String, metadata: language.Map[String, String]): F[Unit]

  /**
   * Deletes a recipeInstance. Once deleted the instance will be marked as `Deleted` in the index and then removed after a while.
   * Use `removeFromIndex` to remove all references to the instance directly allowing you to create a new instance with the same id again.
   * @param recipeInstanceId The identifier for the newly baked process
   * @param removeFromIndex If enabled removes all references to the id directly
   * @return
   */
  def deleteRecipeInstance(recipeInstanceId: String, removeFromIndex: Boolean): F[Unit]

  /**
    * Notifies Baker that an event has happened and waits until the event was accepted but not executed by the process.
    *
    * Possible failures:
    * `NoSuchProcessException` -> When no process exists for the given id
    * `ProcessDeletedException` -> If the process is already deleted
    *
    * @param recipeInstanceId The process identifier
    * @param event     The event object
    */
  def fireEventAndResolveWhenReceived(recipeInstanceId: String, event: EventInstanceType): F[SensoryEventStatus]

  /**
    * Notifies Baker that an event has happened and waits until all the actions which depend on this event are executed.
    *
    * Possible failures:
    * `NoSuchProcessException` -> When no process exists for the given id
    * `ProcessDeletedException` -> If the process is already deleted
    *
    * @param recipeInstanceId The process identifier
    * @param event     The event object
    */
  def fireEventAndResolveWhenCompleted(recipeInstanceId: String, event: EventInstanceType): F[SensoryEventResultType]

  /**
    * Notifies Baker that an event has happened and waits until an specific event has executed.
    *
    * Possible failures:
    * `NoSuchProcessException` -> When no process exists for the given id
    * `ProcessDeletedException` -> If the process is already deleted
    *
    * @param recipeInstanceId The process identifier
    * @param event     The event object
    * @param on        The name of the event to wait for
    */
  def fireEventAndResolveOnEvent(recipeInstanceId: String, event: EventInstanceType, on: String): F[SensoryEventResultType]

  /**
    * Notifies Baker that an event has happened and provides 2 async handlers, one for when the event was accepted by
    * the process, and another for when the event was fully executed by the process.
    *
    * Possible failures:
    * `NoSuchProcessException` -> When no process exists for the given id
    * `ProcessDeletedException` -> If the process is already deleted
    *
    * @param recipeInstanceId The process identifier
    * @param event     The event object
    */
  def fireEvent(recipeInstanceId: String, event: EventInstanceType): EventResolutionsType

  /**
    * Notifies Baker that an event has happened and waits until the event was accepted but not executed by the process.
    *
    * Possible failures:
    * `NoSuchProcessException` -> When no process exists for the given id
    * `ProcessDeletedException` -> If the process is already deleted
    *
    * @param recipeInstanceId     The process identifier
    * @param event         The event object
    * @param correlationId Id used to ensure the process instance handles unique events
    */
  def fireEventAndResolveWhenReceived(recipeInstanceId: String, event: EventInstanceType, correlationId: String): F[SensoryEventStatus]

  /**
    * Notifies Baker that an event has happened and waits until all the actions which depend on this event are executed.
    *
    * Possible failures:
    * `NoSuchProcessException` -> When no process exists for the given id
    * `ProcessDeletedException` -> If the process is already deleted
    *
    * @param recipeInstanceId     The process identifier
    * @param event         The event object
    * @param correlationId Id used to ensure the process instance handles unique events
    */
  def fireEventAndResolveWhenCompleted(recipeInstanceId: String, event: EventInstanceType, correlationId: String): F[SensoryEventResultType]

  /**
    * Notifies Baker that an event has happened and waits until an specific event has executed.
    *
    * Possible failures:
    * `NoSuchProcessException` -> When no process exists for the given id
    * `ProcessDeletedException` -> If the process is already deleted
    *
    * @param recipeInstanceId The process identifier
    * @param event     The event object
    * @param onEvent        The name of the event to wait for
    * @param correlationId Id used to ensure the process instance handles unique events
    */
  def fireEventAndResolveOnEvent(recipeInstanceId: String, event: EventInstanceType, onEvent: String, correlationId: String): F[SensoryEventResultType]

  /**
    * Notifies Baker that an event has happened and provides 2 async handlers, one for when the event was accepted by
    * the process, and another for when the event was fully executed by the process.
    *
    * Possible failures:
    * `NoSuchProcessException` -> When no process exists for the given id
    * `ProcessDeletedException` -> If the process is already deleted
    *
    * @param recipeInstanceId     The process identifier
    * @param event         The event object
    * @param correlationId Id used to ensure the process instance handles unique events
    */
  def fireEvent(recipeInstanceId: String, event: EventInstanceType, correlationId: String): EventResolutionsType

  /**
    * Notifies Baker that an event has happened and waits until the event was accepted but not executed by the process.
    *
    * Possible failures:
    * `NoSuchProcessException` -> When no process exists for the given id
    * `ProcessDeletedException` -> If the process is already deleted
    *
    * @param recipeInstanceId     The process identifier
    * @param event         The event object
    * @param correlationId Id used to ensure the process instance handles unique events
    */
  def fireEventAndResolveWhenReceived(recipeInstanceId: String, event: EventInstanceType, correlationId: language.Option[String]): F[SensoryEventStatus]

  /**
    * Notifies Baker that an event has happened and waits until all the actions which depend on this event are executed.
    *
    * Possible failures:
    * `NoSuchProcessException` -> When no process exists for the given id
    * `ProcessDeletedException` -> If the process is already deleted
    *
    * @param recipeInstanceId     The process identifier
    * @param event         The event object
    * @param correlationId Id used to ensure the process instance handles unique events
    */
  def fireEventAndResolveWhenCompleted(recipeInstanceId: String, event: EventInstanceType, correlationId: language.Option[String]): F[SensoryEventResultType]

  /**
    * Notifies Baker that an event has happened and waits until an specific event has executed.
    *
    * Possible failures:
    * `NoSuchProcessException` -> When no process exists for the given id
    * `ProcessDeletedException` -> If the process is already deleted
    *
    * @param recipeInstanceId The process identifier
    * @param event     The event object
    * @param onEvent        The name of the event to wait for
    * @param correlationId Id used to ensure the process instance handles unique events
    */
  def fireEventAndResolveOnEvent(recipeInstanceId: String, event: EventInstanceType, onEvent: String, correlationId: language.Option[String]): F[SensoryEventResultType]

  /**
    * Notifies Baker that an event has happened and provides 2 async handlers, one for when the event was accepted by
    * the process, and another for when the event was fully executed by the process.
    *
    * Possible failures:
    * `NoSuchProcessException` -> When no process exists for the given id
    * `ProcessDeletedException` -> If the process is already deleted
    *
    * @param recipeInstanceId     The process identifier
    * @param event         The event object
    * @param correlationId Id used to ensure the process instance handles unique events
    */
  def fireEvent(recipeInstanceId: String, event: EventInstanceType, correlationId: language.Option[String]): EventResolutionsType

  /**
    * This method is used to add metadata to your request. This will be added to the ingredients map in Baker.
    * Since this is meant to be used as metadata this should not
    * These cannot be ingredients already found in your recipe.
    * @param metadata
    */
  def addMetaData(recipeInstanceId: String, metadata: language.Map[String, String]): F[Unit]

  /**
    * Returns an index of all running processes.
    *
    * Can potentially return a partial index when baker runs in cluster mode
    * and not all shards can be reached within the given timeout.
    *
    * Does not include deleted processes.
    *
    * @return An index of all processes
    */
  def getAllRecipeInstancesMetadata: F[language.Set[RecipeInstanceMetadataType]]

  /**
    * Returns the process state.
    *
    * @param recipeInstanceId The process identifier
    * @return The process state.
    */
  def getRecipeInstanceState(recipeInstanceId: String): F[RecipeInstanceStateType]

  /**
    * Returns a specific ingredient for a given RecipeInstance id.
    *
    * @param recipeInstanceId The recipeInstance Id.
    * @param name The name of the ingredient.
    * @return The provided ingredients.
    */
  def getIngredient(recipeInstanceId: String, name: String): F[Value]

  /**
    * Returns all provided ingredients for a given RecipeInstance id.
    *
    * @param recipeInstanceId The process id.
    * @return The provided ingredients.
    */
  def getIngredients(recipeInstanceId: String): F[language.Map[String, Value]]

  /**
    * Returns all fired events for a given RecipeInstance id.
    *
    * @param recipeInstanceId The process id.
    * @return The events
    */
  def getEvents(recipeInstanceId: String): F[language.Seq[EventMomentType]]

  /**
    * Returns all names of fired events for a given RecipeInstance id.
    *
    * @param recipeInstanceId The process id.
    * @return The event names
    */
  def getEventNames(recipeInstanceId: String): F[language.Seq[String]]

  /**
    * Returns the visual state (.dot) for a given process.
    *
    * @param recipeInstanceId The process identifier.
    * @return A visual (.dot) representation of the process state.
    */
  def getVisualState(recipeInstanceId: String, style: RecipeVisualStyle = RecipeVisualStyle.default): F[String]

  /**
    * Registers a listener to all runtime events for recipes with the given name run in this baker instance.
    *
    * Note that the delivery guarantee is *AT MOST ONCE*. Do not use it for critical functionality
    */
  def registerEventListener(recipeName: String, listenerFunction: language.BiConsumerFunction[RecipeMetadataType, String]): F[Unit]

  /**
    * Registers a listener to all runtime events for all recipes that run in this Baker instance.
    *
    * Note that the delivery guarantee is *AT MOST ONCE*. Do not use it for critical functionality
    */
  def registerEventListener(listenerFunction: language.BiConsumerFunction[RecipeMetadataType, String]): F[Unit]

  /**
    * Registers a listener function that listens to all BakerEvents
    *
    * Note that the delivery guarantee is *AT MOST ONCE*. Do not use it for critical functionality
    *
    * @param listenerFunction
    * @return
    */
  def registerBakerEventListener(listenerFunction: language.ConsumerFunction[BakerEventType]): F[Unit]

  /**
    * Attempts to gracefully shutdown the baker system.
    */
  def gracefulShutdown(): F[Unit]

  /**
    * Retries a blocked interaction.
    *
    * @return
    */
  def retryInteraction(recipeInstanceId: String, interactionName: String): F[Unit]

  /**
    * Resolves a blocked interaction by specifying it's output.
    *
    * !!! You should provide an event of the original interaction. Event / ingredient renames are done by Baker.
    *
    * @return
    */
  def resolveInteraction(recipeInstanceId: String, interactionName: String, event: EventInstanceType): F[Unit]

  /**
    * Stops the retrying of an interaction.
    *
    * @return
    */
  def stopRetryingInteraction(recipeInstanceId: String, interactionName: String): F[Unit]
}

case class RecipeRecord(
                         recipeId: String,
                         name: String,
                         updated: Long,
                         recipe: CompiledRecipe,
                         validate: Boolean,
                         isActive: Boolean = true
                       )
object RecipeRecord {
  def of(recipe: CompiledRecipe, updated: Long = System.currentTimeMillis(), validate: Boolean = true, isActive: Boolean = true) =
    RecipeRecord(recipe.recipeId, recipe.name, updated, recipe, validate, isActive)
}
