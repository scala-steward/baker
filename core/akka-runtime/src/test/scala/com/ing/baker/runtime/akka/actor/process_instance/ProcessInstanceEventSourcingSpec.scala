package com.ing.baker.runtime.akka.actor.process_instance

import akka.persistence.inmemory.extension.{InMemoryJournalStorage, StorageExtension}
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.scaladsl._
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestProbe
import akka.util.Timeout
import com.ing.baker.il.petrinet.Place
import com.ing.baker.petrinet.api._
import com.ing.baker.runtime.akka.actor.AkkaTestBase
import com.ing.baker.runtime.akka.actor.process_instance.ProcessInstanceEventSourcing._
import com.ing.baker.runtime.akka.actor.process_instance.ProcessInstanceProtocol._
import com.ing.baker.runtime.akka.actor.process_instance.ProcessInstanceSpec._
import com.ing.baker.runtime.akka.actor.process_instance.dsl.TestUtils.{PlaceMethods, place}
import com.ing.baker.runtime.akka.actor.process_instance.dsl._
import com.ing.baker.runtime.scaladsl.{EventInstance, RecipeInstanceState}
import com.ing.baker.runtime.serialization.Encryption.NoEncryption
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers._

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class ProcessInstanceEventSourcingSpec extends AkkaTestBase("ProcessQuerySpec") with BeforeAndAfterEach {

  private implicit val akkaTimout: Timeout = Timeout(2 seconds)
  private val timeOut: Duration = akkaTimout.duration

  private implicit val ec: ExecutionContext = system.dispatcher

  override protected def beforeEach(): Unit = {
    // Clean the journal before each test
    val tp = TestProbe()
    tp.send(StorageExtension(system).journalStorage, InMemoryJournalStorage.ClearJournal)
    tp.expectMsg(akka.actor.Status.Success(""))
  }

  "The query package" should {

    "return a source of events for a petriNet instance" in new StateTransitionNet {

      override val eventSourceFunction: RecipeInstanceState => EventInstance => RecipeInstanceState = s => _ => s

      val readJournal = PersistenceQuery(system).readJournalFor[ReadJournal with CurrentEventsByPersistenceIdQuery]("inmemory-read-journal")

      val p1 = place(1)
      val p2 = place(2)
      val p3 = place(3)
      val t1 = emptyTransition(id = 1, automated = true)
      val t2 = emptyTransition(id = 2, automated = true)

      val petriNet = createPetriNet(p1 ~> t1, t1 ~> p2, p2 ~> t2, t2 ~> p3)
      val recipeInstanceId = UUID.randomUUID().toString
      val instance = createProcessInstance(petriNet, runtime, recipeInstanceId)

      val state = RecipeInstanceState(recipeInstanceId, recipeInstanceId, Map.empty, Map.empty, Seq.empty)

      instance ! Initialize(p1.markWithN(1), state)

      expectMsg(Initialized(p1.markWithN(1), state))
      expectMsgPF(timeOut) { case TransitionFired(_, 1, _, _, _, _, _) => }
      expectMsgPF(timeOut) { case TransitionFired(_, 2, _, _, _, _, _) => }

      ProcessInstanceEventSourcing.eventsForInstance(
        processTypeName = "test",
        recipeInstanceId = recipeInstanceId,
        topology = petriNet,
        encryption = NoEncryption,
        readJournal = readJournal,
        eventSourceFn = (l, t) => eventSourceFunction)
        .map(_._2) // Get the event from the tuple
        .runWith(TestSink.probe)
        .request(3)
        .expectNext(InitializedEvent(marking = p1.markWithN(1).marshall, state = state))
        .expectNextChainingPF {
          case TransitionFiredEvent(_, 1, _, _, _, consumed, produced, _) =>
            consumed shouldBe p1.markWithN(1).marshall
            produced shouldBe p2.markWithN(1).marshall
        }.expectNextChainingPF {
        case TransitionFiredEvent(_, 2, _, _, _, consumed, produced, _) =>
          consumed shouldBe p2.markWithN(1).marshall
          produced shouldBe p3.markWithN(1).marshall
      }
    }
  }
}
