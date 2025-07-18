package com.ing.baker.runtime.akka.actor.process_instance.internal

import com.ing.baker.petrinet.api.Marking

object ExceptionStrategy {

  /**
   * Indicates that this transition should not be retried but other transitions in the petri net still can.
   */
  case object BlockTransition extends ExceptionStrategy

  /**
   * Retries firing the transition after some delay.
   */
  case class RetryWithDelay(delay: Long) extends ExceptionStrategy {
    require(delay >= 0, "Delay must be greater then zero")
  }

  case class Continue[X, O](marking: Marking[X], output: O) extends ExceptionStrategy

  case class ContinueAsFunctionalEvent[X, O](marking: Marking[X], output: O) extends ExceptionStrategy
}

sealed trait ExceptionStrategy