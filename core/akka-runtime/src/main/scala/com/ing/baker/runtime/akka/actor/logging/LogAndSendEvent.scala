package com.ing.baker.runtime.akka.actor.logging

import akka.event.EventStream
import com.ing.baker.il.petrinet.Transition
import com.ing.baker.runtime.common.{EventFired, EventReceived, EventRejected, InteractionCompleted, InteractionFailed, InteractionStarted, RecipeAdded, RecipeInstanceCreated}
import com.ing.baker.runtime.model.BakerLogging

object LogAndSendEvent {

  val bakerLogging: BakerLogging = BakerLogging.default
  //TODO get this on startup instead of requiring for each call
  //val eventStream: EventStream = context.system.eventStream

  def recipeAdded(recipeAdded: RecipeAdded, eventStream: EventStream): Unit = {
    eventStream.publish(recipeAdded)
    bakerLogging.addedRecipe(recipeAdded)
  }

  def recipeInstanceCreated(recipeInstanceCreated: RecipeInstanceCreated, eventStream: EventStream): Unit = {
    eventStream.publish(recipeInstanceCreated)
    bakerLogging.recipeInstanceCreated(recipeInstanceCreated)
  }

  def interactionStarted(interactionStarted: InteractionStarted, eventStream: EventStream): Unit = {
    eventStream.publish(interactionStarted)
    bakerLogging.interactionStarted(interactionStarted)
  }

  def interactionCompleted(interactionCompleted: InteractionCompleted, eventStream: EventStream): Unit = {
    eventStream.publish(interactionCompleted)
    bakerLogging.interactionFinished(interactionCompleted)
  }

  def interactionFailed(interactionFailed: InteractionFailed, reason: Throwable, eventStream: EventStream): Unit = {
    eventStream.publish(interactionFailed)
    bakerLogging.interactionFailed(interactionFailed, reason)
  }

  def eventReceived(eventReceived: EventReceived, eventStream: EventStream): Unit = {
    eventStream.publish(eventReceived)
    bakerLogging.eventReceived(eventReceived)
  }

  def eventRejected(eventRejected: EventRejected, eventStream: EventStream): Unit = {
    eventStream.publish(eventRejected)
    bakerLogging.eventRejected(eventRejected)
  }

  def eventFired(eventFired: EventFired,
                  eventStream: EventStream): Unit = {
    eventStream.publish(eventFired)
    bakerLogging.eventFired(eventFired)
  }
}
