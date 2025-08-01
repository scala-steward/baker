package com.ing.baker.runtime.akka.actor.process_index

import akka.actor.{ActorRef, NoSerializationVerificationNeeded, Props, Terminated}
import akka.cluster.sharding.ShardRegion.Passivate
import akka.event.{DiagnosticLoggingAdapter, Logging}
import akka.pattern.{BackoffOpts, BackoffSupervisor, ask}
import akka.persistence._
import akka.sensors.actor.PersistentActorMetrics
import cats.data.{EitherT, OptionT}
import cats.effect.IO
import cats.instances.future._
import com.ing.baker.il.petrinet.{InteractionTransition, Transition}
import com.ing.baker.il.{CompiledRecipe, EventDescriptor}
import com.ing.baker.petrinet.api._
import com.ing.baker.runtime.akka._
import com.ing.baker.runtime.akka.actor.{ActorBasedBakerCleanup, BakerCleanup, CassandraBakerCleanup}
import com.ing.baker.runtime.akka.actor.Util.logging._
import com.ing.baker.runtime.akka.actor.delayed_transition_actor.DelayedTransitionActor
import com.ing.baker.runtime.akka.actor.delayed_transition_actor.DelayedTransitionActorProtocol.{FireDelayedTransition, StartTimer}
import com.ing.baker.runtime.akka.actor.logging.LogAndSendEvent
import com.ing.baker.runtime.akka.actor.process_index.ProcessIndex._
import com.ing.baker.runtime.akka.actor.process_index.ProcessIndexProtocol._
import com.ing.baker.runtime.akka.actor.process_instance.ProcessInstanceProtocol.ExceptionStrategy.{BlockTransition, Continue, RetryWithDelay}
import com.ing.baker.runtime.akka.actor.process_instance.ProcessInstanceProtocol._
import com.ing.baker.runtime.akka.actor.process_instance.ProcessInstanceLogger._
import com.ing.baker.runtime.akka.actor.process_instance.{ProcessInstance, ProcessInstanceProtocol, ProcessInstanceRuntime}
import com.ing.baker.runtime.akka.actor.recipe_manager.RecipeManagerProtocol._
import com.ing.baker.runtime.akka.actor.serialization.BakerSerializable
import com.ing.baker.runtime.akka.internal.RecipeRuntime
import com.ing.baker.runtime.common.RecipeInstanceState.RecipeInstanceMetadataName
import com.ing.baker.runtime.common.RecipeRecord
import com.ing.baker.runtime.model.InteractionManager
import com.ing.baker.runtime.recipe_manager.RecipeManager
import com.ing.baker.runtime.scaladsl.{EventInstance, RecipeInstanceCreated, RecipeInstanceState}
import com.ing.baker.runtime.serialization.Encryption
import com.ing.baker.types.Value
import com.typesafe.config.Config

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}


object ProcessIndex {

  def props(recipeInstanceIdleTimeout: Option[FiniteDuration],
            retentionCheckInterval: Option[FiniteDuration],
            configuredEncryption: Encryption,
            interactions: InteractionManager[IO],
            recipeManager: RecipeManager,
            getIngredientsFilter: Seq[String],
            providedIngredientFilter: Seq[String],
            blacklistedProcesses: Seq[String],
            rememberProcessDuration: Option[Duration]): Props =
    Props(new ProcessIndex(
      recipeInstanceIdleTimeout,
      retentionCheckInterval,
      configuredEncryption,
      interactions,
      recipeManager,
      getIngredientsFilter,
      providedIngredientFilter,
      blacklistedProcesses,
      rememberProcessDuration))

  sealed trait ProcessStatus

  //message
  case object CheckForProcessesToBeDeleted extends NoSerializationVerificationNeeded

  //The process is created and not deleted
  case object Active extends ProcessStatus

  //The process was deleted
  case object Deleted extends ProcessStatus

  //The process was passivated
  case object Passivated extends ProcessStatus

  case class ActorMetadata(recipeId: String,
                           recipeInstanceId: String,
                           createdDateTime: Long,
                           processStatus: ProcessStatus) extends BakerSerializable {

    def isDeleted: Boolean = processStatus == Deleted

    def isPassivated: Boolean = processStatus == Passivated
  }

  // --- Events

  // when an actor is requested again after passivation
  case class ActorActivated(recipeInstanceId: String) extends BakerSerializable

  // when an actor is passivated
  case class ActorPassivated(recipeInstanceId: String) extends BakerSerializable

  // when an actor is deleted
  case class ActorDeleted(recipeInstanceId: String) extends BakerSerializable

  // when an actor is created
  case class ActorCreated(recipeId: String, recipeInstanceId: String, createdDateTime: Long) extends BakerSerializable

  // Used for creating a snapshot of the index.
  case class ProcessIndexSnapShot(index: Map[String, ActorMetadata]) extends BakerSerializable

  case object StopProcessIndexShard extends BakerSerializable

  private val bakerExecutionContext: ExecutionContext = namedCachedThreadPool(s"Baker.CachedThreadPool")
}

//noinspection ActorMutableStateInspection
class ProcessIndex(recipeInstanceIdleTimeout: Option[FiniteDuration],
                   retentionCheckInterval: Option[FiniteDuration],
                   configuredEncryption: Encryption,
                   interactionManager: InteractionManager[IO],
                   recipeManager: RecipeManager,
                   getIngredientsFilter: Seq[String],
                   providedIngredientFilter: Seq[String],
                   blacklistedProcesses: Seq[String],
                   rememberProcessDuration: Option[Duration]) extends PersistentActor with PersistentActorMetrics {

  override val log: DiagnosticLoggingAdapter = Logging.getLogger(logSource = this)


  override def preStart(): Unit = {
    log.info(s"ProcessIndex started: $self")
  }

  override def postStop(): Unit = {
    log.info(s"ProcessIndex stopped: $self")
  }

  import context.dispatcher

  private val config: Config = context.system.settings.config

  private val snapShotInterval: Int = config.getInt("baker.actor.snapshot-interval")
  private val snapshotCount: Int = config.getInt("baker.actor.snapshot-count")

  private val processInquireTimeout: FiniteDuration = config.getDuration("baker.process-inquire-timeout").toScala
  private val updateCacheTimeout: FiniteDuration =  config.getDuration("baker.process-index-update-cache-timeout").toScala

  private val restartMinBackoff: FiniteDuration =  config.getDuration("baker.process-instance.restart-minBackoff").toScala
  private val restartMaxBackoff: FiniteDuration =  config.getDuration("baker.process-instance.restart-maxBackoff").toScala
  private val restartRandomFactor: Double =  config.getDouble("baker.process-instance.restart-randomFactor")

  private val index: mutable.Map[String, ActorMetadata] = mutable.Map[String, ActorMetadata]()

  //TODO chose if to use the CassandraBakerCleanup or the ActorBasedBakerCleanup
  private val cleanup: BakerCleanup = {
    if(config.hasPath("akka.persistence.journal.plugin") &&
      config.getString("akka.persistence.journal.plugin") == "akka.persistence.cassandra.journal")
      new CassandraBakerCleanup(context.system)
    else
      new ActorBasedBakerCleanup()
  }

  private val delayedTransitionActor: ActorRef = context.actorOf(
    props = DelayedTransitionActor.props(this.self, cleanup, snapShotInterval, snapshotCount),
    name = s"${self.path.name}-timer")

  // if there is a retention check interval defined we schedule a recurring message
  retentionCheckInterval.foreach { interval =>
    context.system.scheduler.scheduleAtFixedRate(interval, interval, context.self, CheckForProcessesToBeDeleted)
  }

  // TODO this is a synchronous ask on an actor which createProcessActor is considered bad practice, alternative?
  private def getRecipeRecord(recipeId: String, reactivate: Boolean): Option[RecipeRecord] = {
    val eventualRecord = recipeManager.get(recipeId).flatMap {
      case Some(recipeRecord) if recipeRecord.isActive => Future.successful(Some(recipeRecord))
      case Some(recipeRecord) if !reactivate => Future.successful(Some(recipeRecord)) // inactive recipe, but reactivation is not needed
      case Some(recipeRecord) => // inactive recipe, reactivate it
        log.info(s"Inactive recipe $recipeId being reactivated.")
        val activeRecipe = recipeRecord.copy(isActive = true)
        recipeManager.put(activeRecipe)
        Future.successful(Some(activeRecipe))
      case None => Future.successful(None)
    }
    Await.result(eventualRecord, updateCacheTimeout)
  }

  def getRecipeIdFromActor(actorRef: ActorRef) : String = actorRef.path.name

  private def getCompiledRecipe(recipeId: String, reactivate: Boolean = true): Option[CompiledRecipe] = {
    getRecipeRecord(recipeId, reactivate).fold[Option[CompiledRecipe]] {
      log.warning(s"No recipe found for $recipeId")
      None
    } {
      case r:RecipeRecord => Some(r.recipe)
      case _ => None
    }
  }

  def getProcessActor(recipeInstanceId: String): Option[ActorRef] =
    context.child(recipeInstanceId)

  private def createProcessActor(recipeInstanceId: String, reactivateRecipe: Boolean = true): Option[ActorRef] =
    getCompiledRecipe(index(recipeInstanceId).recipeId, reactivateRecipe).map(createProcessActor(recipeInstanceId, _))

  // creates a ProcessInstanceActor, does not do any validation
  def createProcessActor(recipeInstanceId: String, compiledRecipe: CompiledRecipe): ActorRef = {
    val runtime: RecipeRuntime =
      new RecipeRuntime(compiledRecipe, interactionManager, context.system.eventStream)

    val processActorProps =
      BackoffSupervisor.props(
        BackoffOpts.onStop(
          ProcessInstance.props[RecipeInstanceState, EventInstance](
            compiledRecipe.name,
            compiledRecipe,
            runtime,
            ProcessInstance.Settings(
              executionContext = bakerExecutionContext,
              encryption = configuredEncryption,
              idleTTL = recipeInstanceIdleTimeout,
              getIngredientsFilter = getIngredientsFilter,
              providedIngredientFilter = providedIngredientFilter,
            ),
            delayedTransitionActor = delayedTransitionActor
          ),
          childName = recipeInstanceId,
          minBackoff = restartMinBackoff,
          maxBackoff = restartMaxBackoff,
          randomFactor = restartRandomFactor)
          .withFinalStopMessage(_.isInstanceOf[ProcessInstanceProtocol.Stop])
      )
    val processActor = context.actorOf(props = processActorProps, name = recipeInstanceId)
    context.watchWith(processActor, TerminatedWithReplyTo(processActor))
    processActor
  }

  def shouldDelete(meta: ActorMetadata): Boolean = {
    if(meta.processStatus != Deleted)
      getCompiledRecipe(meta.recipeId, reactivate = false) match {
        case Some(recipe) =>
          recipe.retentionPeriod.exists { p => meta.createdDateTime + p.toMillis < System.currentTimeMillis() }
        case None =>
          log.error(s"Could not find recipe: ${meta.recipeId} during deletion for recipeInstanceId: ${meta.recipeInstanceId} using default 14 days")
          meta.createdDateTime + (14 days).toMillis < System.currentTimeMillis()
      }
    else false
  }

  private def deleteProcess(meta: ActorMetadata): Unit = {
    //The new way of cleaning ProcessInstances, this can only be done if the datastore is Cassandra
    if(cleanup.supportsCleanupOfStoppedActors) {
      getProcessActor(meta.recipeInstanceId) match {
        case Some(actorRef: ActorRef) =>
          log.debug(s"Deleting ${meta.recipeInstanceId} via actor message")
          actorRef ! Stop(delete = true)
        case None =>
          log.debug(s"Deleting ${meta.recipeInstanceId} via cleanup tool")
          getCompiledRecipe(meta.recipeId, reactivate = false) match {
            case Some(compiledRecipe) =>
              val persistenceId = ProcessInstance.recipeInstanceId2PersistenceId(compiledRecipe.name, meta.recipeInstanceId)
              log.debug(s"Deleting with persistenceId: ${persistenceId}")
              persistWithSnapshot(ActorDeleted(meta.recipeInstanceId)) { _ =>
                //Using deleteAllEvents since we do not use Snapshots for ProcessInstances
                cleanup.deleteAllEvents(persistenceId, neverUsePersistenceIdAgain = false)
                  .map(_ -> {
                    log.processHistoryDeletionSuccessful(meta.recipeInstanceId, 0)
                    index.update(meta.recipeInstanceId, meta.copy(processStatus = Deleted))
                  })
              }
            case None =>
              log.debug(s"Recipe not found for ${meta.recipeInstanceId}, marking as deleted")
              persistWithSnapshot(ActorDeleted(meta.recipeInstanceId)) { _ =>
                index.update(meta.recipeInstanceId, meta.copy(processStatus = Deleted))
              }
          }
      }
    }
    // The old way to cleanup ProcessInstances, we will let Akka Persistence handle the cleanup.
    // This will first start the ProcessInstance actor even if is passivated so will have bigger memory impact and more reads on the datastore.
    else {
      getOrCreateProcessActor(meta.recipeInstanceId).foreach(_ ! Stop(delete = true))
      index.update(meta.recipeInstanceId, meta.copy(processStatus = Deleted))
    }
  }

  // This util function is used only for delete process functionality, therefore passing reactivateRecipe=false to avoid reactivating the recipe
  private def getOrCreateProcessActor(recipeInstanceId: String): Option[ActorRef] =
    context.child(recipeInstanceId).orElse(createProcessActor(recipeInstanceId, reactivateRecipe = false))

  private def forgetProcesses(): Unit = {
    rememberProcessDuration.map {
      duration: Duration =>
        val currentTime = System.currentTimeMillis()
        val toBeForgotten = index.filter { case (id, metadata) =>
          metadata.isDeleted && currentTime >= (metadata.createdDateTime + duration.toMillis)
        }
        index --= toBeForgotten.keys
    }
  }

  private def withActiveProcess(recipeInstanceId: String)(fn: ActorRef => Unit): Unit = {
    context.child(recipeInstanceId) match {
      case None if !index.contains(recipeInstanceId) => sender() ! NoSuchProcess(recipeInstanceId)
      case None if index(recipeInstanceId).isDeleted => sender() ! ProcessDeleted(recipeInstanceId)
      case None =>
        persistWithSnapshot(ActorActivated(recipeInstanceId)) { _ =>
          updateWithStatus(recipeInstanceId, Active)
          val actor = createProcessActor(recipeInstanceId)
          if (actor.isEmpty) {
            log.error(s"Can't create actor for instance $recipeInstanceId")
          }
          actor.foreach(fn)
        }
      case Some(actorRef) => fn(actorRef)
    }
  }

  def getInteractionJob(recipeInstanceId: String, interactionName: String, processActor: ActorRef): OptionT[Future, (InteractionTransition, Id)] = {
    // we find which job correlates with the interaction
    for {
      recipe <- OptionT.fromOption(getCompiledRecipe(index(recipeInstanceId).recipeId))
      transition <- OptionT.fromOption(recipe.interactionTransitions.find(_.interactionName == interactionName))
      state <- OptionT(processActor.ask(GetState)(processInquireTimeout).mapTo[InstanceState].map(Option(_)))
      jobId <- OptionT.fromOption(state.jobs.collectFirst { case (jobId, job) if job.transitionId == transition.id => jobId })
    } yield (transition, jobId)
  }

  override def receiveCommand: Receive =  {
    case SaveSnapshotSuccess(metadata) =>
      log.debug("Snapshot saved & cleaning old processes")
      cleanup.deleteEventsAndSnapshotBeforeSnapshot(metadata.persistenceId, snapshotCount)

    case SaveSnapshotFailure(_, _) =>
      log.error("Saving snapshot failed")

    case GetIndex =>
      sender() ! Index(index.values.filter(_.processStatus == Active).toSeq)

    case CheckForProcessesToBeDeleted =>
      val toBeDeleted = index.values.filter(shouldDelete)
      if (toBeDeleted.nonEmpty)
        log.debug(s"Deleting recipe instance: {}", toBeDeleted.mkString(","))
      toBeDeleted.foreach(meta => deleteProcess(meta))
      forgetProcesses()

    case TerminatedWithReplyTo(actorRef, replyToWhenDone) =>
      val recipeInstanceId = getRecipeIdFromActor(actorRef)

      val mdc = Map(
        "processId" -> recipeInstanceId,
        "recipeInstanceId" -> recipeInstanceId,
      )

      log.logWithMDC(Logging.DebugLevel, s"Actor terminated: $actorRef", mdc)

      index.get(recipeInstanceId) match {
        case Some(meta) if shouldDelete(meta) =>
          persistWithSnapshot(ActorDeleted(recipeInstanceId)) { _ =>
            index.update(recipeInstanceId, meta.copy(processStatus = Deleted))
          }
        case Some(meta) if meta.isDeleted =>
          log.logWithMDC(Logging.WarningLevel, s"Received Terminated message for already deleted recipe instance: ${meta.recipeInstanceId}", mdc)
        case Some(meta) =>
          persistWithSnapshot(ActorPassivated(recipeInstanceId)) { _ =>
            index.update(recipeInstanceId, meta.copy(processStatus = Passivated))
          }
        case None =>
          log.logWithMDC(Logging.DebugLevel, s"Received Terminated message for non indexed actor: $actorRef. This can happen through manual deletion with removeFromIndex enabled.", mdc)
      }

      replyToWhenDone.map {
        replyToRef =>  replyToRef ! ProcessDeleted(recipeInstanceId)
      }

    case CreateProcess(recipeId, recipeInstanceId, recipeInstanceMetadata) =>
      context.child(recipeInstanceId) match {
        case None if !index.contains(recipeInstanceId) =>

          // First check if the recipe exists
          getCompiledRecipe(recipeId) match {
            case Some(compiledRecipe) =>

              val createdTime = System.currentTimeMillis()

              // this persists the fact that we created a process instance
              persistWithSnapshot(ActorCreated(recipeId, recipeInstanceId, createdTime)) { _ =>

                // after that we actually create the ProcessInstance actor
                val processState = RecipeInstanceState(
                  recipeId = recipeId,
                  recipeInstanceId = recipeInstanceId,
                  ingredients =
                    if (recipeInstanceMetadata.isEmpty) Map.empty[String, Value]
                    else Map(RecipeInstanceMetadataName -> com.ing.baker.types.Converters.toValue(recipeInstanceMetadata)),
                  recipeInstanceMetadata = recipeInstanceMetadata,
                  events = List.empty)
                val initializeCmd = Initialize(compiledRecipe.initialMarking, processState)

                //TODO ensure the initialiseCMD is accepted before we add it ot the index
                createProcessActor(recipeInstanceId, compiledRecipe)
                  .forward(initializeCmd)

                val actorMetadata = ActorMetadata(recipeId, recipeInstanceId, createdTime, Active)

                LogAndSendEvent.recipeInstanceCreated(
                  RecipeInstanceCreated(System.currentTimeMillis(), recipeId, compiledRecipe.name, recipeInstanceId),
                  context.system.eventStream)

                index += recipeInstanceId -> actorMetadata
              }

            case None =>
              sender() ! NoRecipeFound(recipeId)
          }
        case _ if index.get(recipeInstanceId).exists(_.isDeleted) =>
          sender() ! ProcessDeleted(recipeInstanceId)
        case None =>
          //Temporary solution for the situation that the initializeCmd is not send in the original Bake
          getCompiledRecipe(recipeId) match {
            case Some(compiledRecipe) =>
              val processState = RecipeInstanceState(recipeId, recipeInstanceId, Map.empty[String, Value], Map.empty[String, String], List.empty)
              val initializeCmd = Initialize(compiledRecipe.initialMarking, processState)
              createProcessActor(recipeInstanceId, compiledRecipe) ! initializeCmd
              sender() ! ProcessAlreadyExists(recipeInstanceId)
            case None =>
              //Kept the ProcessAlreadyExists since this was the original error
              sender() ! ProcessAlreadyExists(recipeInstanceId)
          }
        case Some(actorRef: ActorRef) =>
          //Temporary solution for the situation that the initializeCmd is not send in the original Bake
          getCompiledRecipe(recipeId) match {
            case Some(compiledRecipe) =>
              val processState = RecipeInstanceState(recipeId, recipeInstanceId, Map.empty[String, Value], Map.empty[String, String], List.empty)
              val initializeCmd = Initialize(compiledRecipe.initialMarking, processState)
              actorRef ! initializeCmd
              sender() ! ProcessAlreadyExists(recipeInstanceId)
            case None =>
              sender() ! NoRecipeFound(recipeId)
          }
      }

    case command@ProcessEvent(recipeInstanceId, event, correlationId, _, _) =>
      run ({ responseHandler =>
        for {
          instanceAndMeta <- fetchInstance(recipeInstanceId)
          (processInstance, metadata) = instanceAndMeta
          recipe <- fetchRecipe(metadata)
          _ <- initializeResponseHandler(recipe, responseHandler)
          transitionAndDescriptor <- validateEventIsInRecipe(recipe, event, recipeInstanceId)
          (transition, descriptor) = transitionAndDescriptor
          _ <- validateEventIsSound(descriptor, event, recipeInstanceId)
          _ <- validateWithinReceivePeriod(recipe, metadata, recipeInstanceId)
          fireTransitionCommand = FireTransition(transition.id, event, correlationId)
          _ <- forwardToProcessInstance(fireTransitionCommand, processInstance, responseHandler)
        } yield ()
      }, command)

    case FireDelayedTransition(recipeInstanceId, jobId, transitionId, eventToFire, originalSender) =>
      withActiveProcess(recipeInstanceId) { processActor =>
        processActor.tell(DelayedTransitionFired(jobId, transitionId, eventToFire), originalSender)
      }

    case StopRetryingInteraction(recipeInstanceId, interactionName) =>

      withActiveProcess(recipeInstanceId) { processActor =>

        val originalSender = sender()

        // we find which job correlates with the interaction
        getInteractionJob(recipeInstanceId, interactionName, processActor).value.onComplete {
          case Success(Some((_, jobId))) => processActor.tell(OverrideExceptionStrategy(jobId, BlockTransition), originalSender)
          case Success(_) => originalSender ! akka.actor.Status.Failure(new IllegalArgumentException("Interaction is not retrying"))
          case Failure(exception) => originalSender ! akka.actor.Status.Failure(exception)
        }
      }

    case RetryBlockedInteraction(recipeInstanceId, interactionName) =>

      withActiveProcess(recipeInstanceId) { processActor =>

        val originalSender = sender()

        getInteractionJob(recipeInstanceId, interactionName, processActor).value.onComplete {
          case Success(Some((_, jobId))) => processActor.tell(OverrideExceptionStrategy(jobId, RetryWithDelay(0)), originalSender)
          case Success(_) => originalSender ! akka.actor.Status.Failure(new IllegalArgumentException("Interaction is not blocked"))
          case Failure(exception) => originalSender ! akka.actor.Status.Failure(exception)
        }
      }

    case ResolveBlockedInteraction(recipeInstanceId, interactionName, event) =>

      withActiveProcess(recipeInstanceId) { processActor =>

        val originalSender = sender()

        getInteractionJob(recipeInstanceId, interactionName, processActor).value.onComplete {
          case Success(Some((interaction, jobId))) =>
            RecipeRuntime.validateInteractionOutput(interaction, Some(event)) match {

              case None =>
                val petriNet = getCompiledRecipe(index(recipeInstanceId).recipeId).get.petriNet
                val producedMarking = RecipeRuntime.createProducedMarking(petriNet.outMarking(interaction), Some(event))
                val transformedEvent = RecipeRuntime.transformInteractionEvent(interaction, event)

                processActor.tell(OverrideExceptionStrategy(jobId, Continue(producedMarking.marshall, transformedEvent)), originalSender)
              case Some(error) =>
                log.warning("Invalid event given: " + error)
                originalSender ! InvalidEventWhenResolveBlocked(recipeInstanceId, error)
            }
          case Success(_) => originalSender ! akka.actor.Status.Failure(new IllegalArgumentException("Interaction is not blocked"))
          case Failure(exception) => originalSender ! akka.actor.Status.Failure(exception)
        }
      }

    case AddRecipeInstanceMetaData(recipeInstanceId, metaData) =>
      withActiveProcess(recipeInstanceId) { actorRef =>
        actorRef.forward(AddMetaData(metaData))
      }

    case GetProcessState(recipeInstanceId) =>
      withActiveProcess(recipeInstanceId) { actorRef => actorRef.forward(GetState) }

    case GetProcessIngredient(recipeInstanceId, name) =>
      withActiveProcess(recipeInstanceId) { actorRef => actorRef.forward(GetIngredient(name)) }

    case GetCompiledRecipe(recipeInstanceId) =>
      index.get(recipeInstanceId) match {
        case Some(processMetadata) if processMetadata.isDeleted => sender() ! ProcessDeleted(recipeInstanceId)
        case Some(processMetadata) =>
          getRecipeRecord(processMetadata.recipeId, reactivate = true) match {
            case Some(RecipeRecord(_, _, updated, recipe, _, _)) => sender() ! RecipeFound(recipe, updated)
            case None => sender() ! NoSuchProcess(recipeInstanceId)
          }
        case None => sender() ! NoSuchProcess(recipeInstanceId)
      }

    case Passivate(ProcessInstanceProtocol.Stop) =>
      context.stop(sender())

    case DeleteProcess(recipeInstanceId, removeFromIndex) =>
      index.get(recipeInstanceId) match {
        case Some(processState) =>
          // The process exists, so we can delete it.
          getProcessActor(processState.recipeInstanceId) map {
            actorRef: ActorRef =>
              context.unwatch(actorRef)
              context.watchWith(actorRef, TerminatedWithReplyTo(actorRef, Some(sender())))
          }
          deleteProcess(processState)
          if (removeFromIndex) {
            index -= recipeInstanceId
          }

        case None =>
          // The process was not found in the index.
          log.warning(s"Attempted to delete non-existent process '$recipeInstanceId'.")
          sender() ! NoSuchProcess(recipeInstanceId)
      }

    case StopProcessIndexShard =>
      log.info("StopProcessIndexShard received, stopping self")
      context.stop(delayedTransitionActor)
      context.stop(self)

    case cmd =>
      log.error(s"Unrecognized command $cmd")
  }

  type FireEventIO[A] = EitherT[IO, FireSensoryEventRejection, A]

  def run(program: ActorRef => FireEventIO[Unit], command: ProcessEvent): Unit = {
    val responseHandler = context.actorOf(
      SensoryEventResponseHandler(sender(), command))
    program(responseHandler).value.unsafeRunAsync {
      case Left(exception) =>
        throw exception // TODO decide what to do, might never happen, except if we generalize it as a runtime for the actor
      case Right(Left(rejection)) =>
        responseHandler ! rejection
      case Right(Right(())) =>
        ()
    }
  }

  def reject[A](rejection: FireSensoryEventRejection): FireEventIO[A] =
    EitherT.leftT(rejection)

  def accept[A](a: A): FireEventIO[A] =
    EitherT.rightT(a)

  def continue: FireEventIO[Unit] =
    accept(())

  def sync[A](thunk: => A): FireEventIO[A] =
    EitherT.liftF(IO(thunk))

  def async[A](callback: (Either[Throwable, A] => Unit) => Unit): FireEventIO[A] =
    EitherT.liftF(IO.async(callback))

  def fetchCurrentTime: FireEventIO[Long] =
    EitherT.liftF(IO {
      System.currentTimeMillis()
    })

  def fetchInstance(recipeInstanceId: String): FireEventIO[(ActorRef, ActorMetadata)] =
    context.child(recipeInstanceId) match {
      case Some(process) =>
        accept(process -> index(recipeInstanceId))
      case None if !index.contains(recipeInstanceId) =>
        reject(FireSensoryEventRejection.NoSuchRecipeInstance(recipeInstanceId))
      case None if index(recipeInstanceId).isDeleted =>
        reject(FireSensoryEventRejection.RecipeInstanceDeleted(recipeInstanceId))
      case None =>
        async { callback =>
          createProcessActor(recipeInstanceId).foreach { actor =>
            persistWithSnapshot(ActorActivated(recipeInstanceId)) { _ =>
              updateWithStatus(recipeInstanceId, Active)
              callback(Right(actor -> index(recipeInstanceId)))
            }
          }
        }
    }

  def fetchRecipe(metadata: ActorMetadata): FireEventIO[CompiledRecipe] =
    accept(getCompiledRecipe(metadata.recipeId).get)

  def initializeResponseHandler(recipe: CompiledRecipe, handler: ActorRef): FireEventIO[Unit] =
    sync(handler ! recipe)

  def validateEventIsInRecipe(recipe: CompiledRecipe, event: EventInstance, recipeInstanceId: String): FireEventIO[(Transition, EventDescriptor)] = {
    val transition0 = recipe.petriNet.transitions.find(_.label == event.name)
    val sensoryEvent0 = recipe.sensoryEvents.find(_.name == event.name)
    (transition0, sensoryEvent0) match {
      case (Some(transition), Some(sensoryEvent)) =>
        accept(transition -> sensoryEvent)
      case _ =>
        reject(FireSensoryEventRejection.InvalidEvent(
          recipeInstanceId,
          s"No event with name '${event.name}' found in recipe '${recipe.name}'"
        ))
    }
  }

  def validateEventIsSound(descriptor: EventDescriptor, event: EventInstance, recipeInstanceId: String): FireEventIO[Unit] = {
    val eventValidationErrors = event.validate(descriptor)
    if (eventValidationErrors.nonEmpty)
      reject(FireSensoryEventRejection.InvalidEvent(
        recipeInstanceId,
        s"Invalid event: " + eventValidationErrors.mkString(",")
      ))
    else continue
  }

  def validateWithinReceivePeriod(recipe: CompiledRecipe, metadata: ActorMetadata, recipeInstanceId: String): FireEventIO[Unit] = {
    def outOfReceivePeriod(current: Long, period: FiniteDuration): Boolean =
      current - metadata.createdDateTime > period.toMillis

    for {
      currentTime <- fetchCurrentTime
      _ <- recipe.eventReceivePeriod match {
        case Some(receivePeriod) if outOfReceivePeriod(currentTime, receivePeriod) =>
          reject(FireSensoryEventRejection.ReceivePeriodExpired(recipeInstanceId))
        case _ => continue
      }
    } yield ()
  }

  def forwardToProcessInstance(command: Any, processInstance: ActorRef, responseHandler: ActorRef): FireEventIO[Unit] =
    sync(processInstance.tell(command, responseHandler))

  private def updateWithStatus(recipeInstanceId: String, processStatus: ProcessStatus): Unit = {
    index.get(recipeInstanceId) match {
      case Some(metadata) => index.update(recipeInstanceId, metadata.copy(processStatus = processStatus))
      case None => log.error(s"Trying to update recipeInstanceId $recipeInstanceId, with status: $processStatus but no metadata found for instance")
    }
  }

  override def receiveRecover: Receive = {
    case SnapshotOffer(_, processIndexSnapShot: ProcessIndexSnapShot) =>
      index.clear()
      index ++= processIndexSnapShot.index
    case SnapshotOffer(_, _) =>
      val message = "could not load snapshot because snapshot was not of type ProcessIndexSnapShot"
      log.error(message)
      throw new IllegalArgumentException(message)
    case ActorCreated(recipeId, recipeInstanceId, createdTime) =>
      index += recipeInstanceId -> ActorMetadata(recipeId, recipeInstanceId, createdTime, Active)
    case ActorPassivated(recipeInstanceId) =>
      updateWithStatus(recipeInstanceId, Passivated)
    case ActorActivated(recipeInstanceId) =>
      updateWithStatus(recipeInstanceId, Active)
    case ActorDeleted(recipeInstanceId) =>
      updateWithStatus(recipeInstanceId, Deleted)
    case RecoveryCompleted =>
      // Delete all blacklisted processes.
      index.foreach(process =>
        if(blacklistedProcesses.contains(process._1) && !process._2.isDeleted) {
          val id = process._1
          log.info(s"Deleting blacklistedProcesses $id")
          deleteProcess(process._2)
        }
      )

      // Start the active processes
      index
        .filter(process => process._2.processStatus == Active).keys
        .foreach(id => {
          log.info(s"Starting child actor after recovery: $id")
          createProcessActor(id)
        })
      delayedTransitionActor ! StartTimer
  }

  def persistWithSnapshot[A](event: A)(handler: A => Unit): Unit = {
    persist(event)(handler)
    if (lastSequenceNr % snapShotInterval == 0 && lastSequenceNr != 0) {
      saveSnapshot(ProcessIndexSnapShot(index.toMap))
    }
  }

  override def persistenceId: String = self.path.name

  override def onPersistFailure(cause: Throwable, event: Any, seqNr: Id): Unit = {
    super.onPersistFailure(cause, event, seqNr)
    log.debug("Failure of persisting event in ProcessIndex")
  }
}
