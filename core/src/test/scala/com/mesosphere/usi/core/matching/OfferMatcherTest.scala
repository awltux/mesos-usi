package com.mesosphere.usi.core.matching

import com.mesosphere.usi.core.helpers.MesosMock
import com.mesosphere.usi.core.models.constraints.AttributeStringIsFilter
import com.mesosphere.usi.core.models.{PodId, RunningPodSpec}
import com.mesosphere.usi.core.models.faultdomain.HomeRegionFilter
import com.mesosphere.usi.core.models.resources.{ResourceType, ScalarRequirement}
import com.mesosphere.usi.core.models.template.{RunTemplate, SimpleRunTemplateFactory}
import com.mesosphere.usi.core.protos.ProtoBuilders.newTextAttribute
import com.mesosphere.utils.UnitTest

class OfferMatcherTest extends UnitTest {
  val agentAttributes = List(newTextAttribute("rack", "a"), newTextAttribute("version", "1.2.3"))
  val offer = MesosMock.createMockOffer(cpus = 4, mem = 4096, attributes = agentAttributes)
  val testPodId = PodId("mock-podId")
  def testRunTemplate(cpus: Int = Integer.MAX_VALUE, mem: Int = 256): RunTemplate = {
    SimpleRunTemplateFactory(
      resourceRequirements = List(ScalarRequirement(ResourceType.CPUS, cpus), ScalarRequirement(ResourceType.MEM, mem)),
      shellCommand = "sleep 3600",
      "test"
    )
  }
  val offerMatcher = new OfferMatcher(MesosMock.masterDomainInfo)

  "agent attribute matching" should {
    "decline offers which don't match all of the attribute filters" in {
      val nonMatchingPodSpec = RunningPodSpec(
        testPodId,
        testRunTemplate(cpus = 1, mem = 256),
        HomeRegionFilter,
        List(AttributeStringIsFilter("rack", "a"), AttributeStringIsFilter("version", "0.0.0"))
      )

      val result = offerMatcher.matchOffer(offer, List(nonMatchingPodSpec))
      result shouldBe Map.empty
    }

    "accept offers when all of the attribute filters match" in {
      val nonMatchingPodSpec = RunningPodSpec(
        testPodId,
        testRunTemplate(cpus = 1, mem = 256),
        HomeRegionFilter,
        List(AttributeStringIsFilter("rack", "a"), AttributeStringIsFilter("version", "1.2.3"))
      )

      val result = offerMatcher.matchOffer(offer, List(nonMatchingPodSpec))
      result.nonEmpty shouldBe true
    }

    "decline offers when version attribute fails with non version eq" in {
      val nonMatchingPodSpec = RunningPodSpec(
        testPodId,
        testRunTemplate(cpus = 1, mem = 256),
        HomeRegionFilter,
        List(AttributeStringIsFilter("rack", "a"), AttributeStringIsFilter("version", "notVersionString?eq"))
      )

      val result = offerMatcher.matchOffer(offer, List(nonMatchingPodSpec))
      result shouldBe Map.empty
    }

    "decline offers when version attribute fails with version default operator eq" in {
      val nonMatchingPodSpec = RunningPodSpec(
        testPodId,
        testRunTemplate(cpus = 1, mem = 256),
        HomeRegionFilter,
        List(AttributeStringIsFilter("rack", "a"), AttributeStringIsFilter("version", "1.2.2?badOperatorUsesEq"))
      )

      val result = offerMatcher.matchOffer(offer, List(nonMatchingPodSpec))
      result shouldBe Map.empty
    }

    "accept offers when all of the attribute filters match with version default operator eq" in {
      val nonMatchingPodSpec = RunningPodSpec(
        testPodId,
        testRunTemplate(cpus = 1, mem = 256),
        HomeRegionFilter,
        List(AttributeStringIsFilter("rack", "a"), AttributeStringIsFilter("version", "1.2.3?badOperatorUsesEq"))
      )

      val result = offerMatcher.matchOffer(offer, List(nonMatchingPodSpec))
      result.nonEmpty shouldBe true
    }

    "accept offers when all of the attribute filters match with version lt" in {
      val nonMatchingPodSpec = RunningPodSpec(
        testPodId,
        testRunTemplate(cpus = 1, mem = 256),
        HomeRegionFilter,
        List(AttributeStringIsFilter("rack", "a"), AttributeStringIsFilter("version", "1.2.2?lt"))
      )

      val result = offerMatcher.matchOffer(offer, List(nonMatchingPodSpec))
      result.nonEmpty shouldBe true
    }

    "decline offers when version attribute fails with version lt" in {
      val nonMatchingPodSpec = RunningPodSpec(
        testPodId,
        testRunTemplate(cpus = 1, mem = 256),
        HomeRegionFilter,
        List(AttributeStringIsFilter("rack", "a"), AttributeStringIsFilter("version", "1.2.3?lt"))
      )

      val result = offerMatcher.matchOffer(offer, List(nonMatchingPodSpec))
      result shouldBe Map.empty
    }

    "accept offers when all of the attribute filters match with smaller version le" in {
      val nonMatchingPodSpec = RunningPodSpec(
        testPodId,
        testRunTemplate(cpus = 1, mem = 256),
        HomeRegionFilter,
        List(AttributeStringIsFilter("rack", "a"), AttributeStringIsFilter("version", "1.2.2?le"))
      )

      val result = offerMatcher.matchOffer(offer, List(nonMatchingPodSpec))
      result.nonEmpty shouldBe true
    }

    "accept offers when all of the attribute filters match with equal version le" in {
      val nonMatchingPodSpec = RunningPodSpec(
        testPodId,
        testRunTemplate(cpus = 1, mem = 256),
        HomeRegionFilter,
        List(AttributeStringIsFilter("rack", "a"), AttributeStringIsFilter("version", "1.2.3?le"))
      )

      val result = offerMatcher.matchOffer(offer, List(nonMatchingPodSpec))
      result.nonEmpty shouldBe true
    }

    "decline offers when version attribute fails with version le" in {
      val nonMatchingPodSpec = RunningPodSpec(
        testPodId,
        testRunTemplate(cpus = 1, mem = 256),
        HomeRegionFilter,
        List(AttributeStringIsFilter("rack", "a"), AttributeStringIsFilter("version", "1.2.4?le"))
      )

      val result = offerMatcher.matchOffer(offer, List(nonMatchingPodSpec))
      result shouldBe Map.empty
    }

    "accept offers when all of the attribute filters match with version eq" in {
      val nonMatchingPodSpec = RunningPodSpec(
        testPodId,
        testRunTemplate(cpus = 1, mem = 256),
        HomeRegionFilter,
        List(AttributeStringIsFilter("rack", "a"), AttributeStringIsFilter("version", "1.2.3?eq"))
      )

      val result = offerMatcher.matchOffer(offer, List(nonMatchingPodSpec))
      result.nonEmpty shouldBe true
    }

    "decline offers when version attribute fails with smaller version eq" in {
      val nonMatchingPodSpec = RunningPodSpec(
        testPodId,
        testRunTemplate(cpus = 1, mem = 256),
        HomeRegionFilter,
        List(AttributeStringIsFilter("rack", "a"), AttributeStringIsFilter("version", "1.2.2?eq"))
      )

      val result = offerMatcher.matchOffer(offer, List(nonMatchingPodSpec))
      result shouldBe Map.empty
    }

    "decline offers when version attribute fails with larger version eq" in {
      val nonMatchingPodSpec = RunningPodSpec(
        testPodId,
        testRunTemplate(cpus = 1, mem = 256),
        HomeRegionFilter,
        List(AttributeStringIsFilter("rack", "a"), AttributeStringIsFilter("version", "1.2.4?eq"))
      )

      val result = offerMatcher.matchOffer(offer, List(nonMatchingPodSpec))
      result shouldBe Map.empty
    }

    "accept offers when all of the attribute filters match with version ne" in {
      val nonMatchingPodSpec = RunningPodSpec(
        testPodId,
        testRunTemplate(cpus = 1, mem = 256),
        HomeRegionFilter,
        List(AttributeStringIsFilter("rack", "a"), AttributeStringIsFilter("version", "1.2.3?ne"))
      )

      val result = offerMatcher.matchOffer(offer, List(nonMatchingPodSpec))
      result shouldBe Map.empty
    }

    "decline offers when version attribute fails with smaller version ne" in {
      val nonMatchingPodSpec = RunningPodSpec(
        testPodId,
        testRunTemplate(cpus = 1, mem = 256),
        HomeRegionFilter,
        List(AttributeStringIsFilter("rack", "a"), AttributeStringIsFilter("version", "1.2.2?ne"))
      )

      val result = offerMatcher.matchOffer(offer, List(nonMatchingPodSpec))
      result.nonEmpty shouldBe true
    }

    "decline offers when version attribute fails with larger version ne" in {
      val nonMatchingPodSpec = RunningPodSpec(
        testPodId,
        testRunTemplate(cpus = 1, mem = 256),
        HomeRegionFilter,
        List(AttributeStringIsFilter("rack", "a"), AttributeStringIsFilter("version", "1.2.4?ne"))
      )

      val result = offerMatcher.matchOffer(offer, List(nonMatchingPodSpec))
      result.nonEmpty shouldBe true
    }

    "accept offers when all of the attribute filters match with smaller version ge" in {
      val nonMatchingPodSpec = RunningPodSpec(
        testPodId,
        testRunTemplate(cpus = 1, mem = 256),
        HomeRegionFilter,
        List(AttributeStringIsFilter("rack", "a"), AttributeStringIsFilter("version", "1.2.2?ge"))
      )

      val result = offerMatcher.matchOffer(offer, List(nonMatchingPodSpec))
      result shouldBe Map.empty
    }

    "accept offers when all of the attribute filters match with equal version ge" in {
      val nonMatchingPodSpec = RunningPodSpec(
        testPodId,
        testRunTemplate(cpus = 1, mem = 256),
        HomeRegionFilter,
        List(AttributeStringIsFilter("rack", "a"), AttributeStringIsFilter("version", "1.2.3?ge"))
      )

      val result = offerMatcher.matchOffer(offer, List(nonMatchingPodSpec))
      result.nonEmpty shouldBe true
    }

    "decline offers when version attribute fails with version ge" in {
      val nonMatchingPodSpec = RunningPodSpec(
        testPodId,
        testRunTemplate(cpus = 1, mem = 256),
        HomeRegionFilter,
        List(AttributeStringIsFilter("rack", "a"), AttributeStringIsFilter("version", "1.2.4?ge"))
      )

      val result = offerMatcher.matchOffer(offer, List(nonMatchingPodSpec))
      result.nonEmpty shouldBe true
    }

    "accept offers when all of the attribute filters match with equal version gt" in {
      val nonMatchingPodSpec = RunningPodSpec(
        testPodId,
        testRunTemplate(cpus = 1, mem = 256),
        HomeRegionFilter,
        List(AttributeStringIsFilter("rack", "a"), AttributeStringIsFilter("version", "1.2.3?gt"))
      )

      val result = offerMatcher.matchOffer(offer, List(nonMatchingPodSpec))
      result shouldBe Map.empty
    }

    "accept offers when all of the attribute filters match with larger version gt" in {
      val nonMatchingPodSpec = RunningPodSpec(
        testPodId,
        testRunTemplate(cpus = 1, mem = 256),
        HomeRegionFilter,
        List(AttributeStringIsFilter("rack", "a"), AttributeStringIsFilter("version", "1.2.4?gt"))
      )

      val result = offerMatcher.matchOffer(offer, List(nonMatchingPodSpec))
      result.nonEmpty shouldBe true
    }

    "accept offers when no attribute filters are specified" in {
      val nonMatchingPodSpec = RunningPodSpec(testPodId, testRunTemplate(cpus = 1, mem = 256), HomeRegionFilter, Nil)

      val result = offerMatcher.matchOffer(offer, List(nonMatchingPodSpec))
      result.nonEmpty shouldBe true
    }
  }
}
