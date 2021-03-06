package com.mesosphere.mesos.client

import java.util.UUID

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import com.mesosphere.mesos.conf.MesosClientSettings
import com.mesosphere.utils.AkkaUnitTest
import com.mesosphere.utils.mesos.{MesosClusterTest, MesosConfig}
import org.apache.mesos.v1.Protos.{Filters, FrameworkID, FrameworkInfo}
import org.apache.mesos.v1.scheduler.Protos.Event

import scala.annotation.tailrec
import scala.concurrent.Future

class MesosClientIntegrationTest extends AkkaUnitTest with MesosClusterTest {

  override lazy val mesosConfig = MesosConfig(numMasters = 3)

  "Mesos client should successfully subscribe to Mesos without framework Id" in withFixture() { f =>
    Then("a framework successfully subscribes without a framework Id")
    f.client.frameworkId.getValue shouldNot be(empty)

    And("connection context should be initialized")
    f.client.session.baseUri shouldBe f.baseUri
    f.client.session.streamId.length should be > 1
    f.client.frameworkId.getValue.length should be > 1
  }

  "Mesos client should successfully subscribe to Mesos with framework Id" in {
    val frameworkID = FrameworkID.newBuilder.setValue(UUID.randomUUID().toString)

    When("a framework subscribes with a framework Id")
    withFixture(Some(frameworkID)) { f =>
      Then("the client should identify as the specified frameworkId")
      f.client.frameworkId.getValue shouldBe frameworkID.getValue
    }
  }

  "Mesos client should successfully receive heartbeat" in withFixture() { f =>
    When("a framework subscribes")
    val heartbeat = f.pullUntil(_.getType == Event.Type.HEARTBEAT)

    Then("a heartbeat event should arrive")
    heartbeat shouldNot be(empty)
  }

  "Mesos client should follow redirect" in {
    Given("client settings with non-leader URL")
    val nonLeader = mesosCluster.masters.find(_.port != mesosFacade.url.getPort).value.host()
    val settings = MesosClientSettings.load().withMasters(nonLeader)

    When(s"we connect to $nonLeader")
    withFixture(None, Some(settings)) { f =>
      Then("a new framework should register")
      f.client.frameworkId.getValue shouldNot be(empty)

      And("a heartbeat should arrive")
      f.pullUntil(_.getType == Event.Type.HEARTBEAT) shouldNot be(empty)
    }
  }

  "Mesos client should successfully receive offers" in withFixture() { f =>
    When("a framework subscribes")
    val offer = f.pullUntil(_.getType == Event.Type.OFFERS)

    And("an offer should arrive")
    offer shouldNot be(empty)
  }

  "Mesos client should successfully declines offers" in withFixture() { f =>
    When("a framework subscribes")
    And("an offer event is received")
    val Some(offer) = f.pullUntil(_.getType == Event.Type.OFFERS)
    val offerId = offer.getOffers.getOffers(0).getId

    And("and an offer is declined")
    val decline = f.client.calls
      .newDecline(offerIds = Seq(offerId), filters = Some(Filters.newBuilder.setRefuseSeconds(0.0).build()))

    Source.single(decline).runWith(f.client.mesosSink)

    Then("eventually a new offer event arrives")
    val nextOffer = f.pullUntil(_.getType == Event.Type.OFFERS)
    nextOffer shouldNot be(empty)
  }

  "Mesos client publisher sink and event source are both stopped with the kill switch" in withFixture() { f =>
    val sinkDone = Source.fromFuture(Future.never).runWith(f.client.mesosSink)

    f.client.killSwitch.shutdown()
    sinkDone.futureValue.shouldBe(Done)
    eventually {
      f.queue.pull().futureValue shouldBe None
    }
  }

  def withFixture(frameworkId: Option[FrameworkID.Builder] = None, settings: Option[MesosClientSettings] = None)(
      fn: Fixture => Unit
  ): Unit = {
    val f = new Fixture(frameworkId, settings)
    try fn(f)
    finally {
      f.client.killSwitch.shutdown()
    }
  }

  class Fixture(
      existingFrameworkId: Option[FrameworkID.Builder] = None,
      maybeSettings: Option[MesosClientSettings] = None
  ) {
    implicit val system: ActorSystem = ActorSystem()
    implicit val materializer: ActorMaterializer = ActorMaterializer()

    val frameworkInfo = FrameworkInfo
      .newBuilder()
      .setUser("test")
      .setName("Mesos Client Integration Tests")
      .setId(existingFrameworkId.getOrElse(FrameworkID.newBuilder.setValue(UUID.randomUUID().toString)))
      .addRoles("test")
      .setFailoverTimeout(30.0f)
      .addCapabilities(FrameworkInfo.Capability.newBuilder().setType(FrameworkInfo.Capability.Type.MULTI_ROLE))
      .build()

    lazy val baseUri = Uri.from(scheme = "http", host = mesosFacade.url.getHost, port = mesosFacade.url.getPort)

    val settings: MesosClientSettings = maybeSettings.getOrElse(MesosClientSettings.load().withMasters(mesosFacade.url))

    val client = MesosClient(settings, frameworkInfo).runWith(Sink.head).futureValue

    val queue = client.mesosSource.runWith(Sink.queue())

    /**
      * Pull (and drop) elements from the queue until the predicate returns true. Does not cancel the upstream.
      *
      * Returns Some(element) when queue emits an event which matches the predicate
      * Returns None if queue ends (client closes) before the predicate matches
      * TimeoutException is thrown if no event is available within the `patienceConfig.timeout` duration.
      *
      * @param predicate Function to evaluate to see if event matches
      * @return matching event, if any
      */
    @tailrec final def pullUntil(predicate: Event => Boolean): Option[Event] =
      queue.pull().futureValue match {
        case e @ Some(event) if (predicate(event)) =>
          e
        case None =>
          None
        case _ =>
          pullUntil(predicate)
      }
  }

}
