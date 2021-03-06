package com.mesosphere.usi.metrics.dropwizard

import com.mesosphere.usi.metrics.UnitOfMeasurement
import com.mesosphere.utils.UnitTest

class DropwizardMetricsTest extends UnitTest {

  "DropwizardMetrics.constructName" should {
    "not append a unit of measurement suffix, when none is given" in {
      val name =
        DropwizardMetrics.constructName("marathon", "metric", "counter", UnitOfMeasurement.None)
      name shouldBe "marathon.metric.counter"
    }

    "append the memory unit of measurement suffix, when it is given" in {
      val name =
        DropwizardMetrics.constructName("marathon", "metric", "counter", UnitOfMeasurement.Memory)
      name shouldBe "marathon.metric.counter.bytes"
    }

    "append the time unit of measurement suffix, when it is given" in {
      val name =
        DropwizardMetrics.constructName("marathon", "metric", "counter", UnitOfMeasurement.Time)
      name shouldBe "marathon.metric.counter.seconds"
    }

    "throw an exception if metric name components contain a disallowed character" in {
      assertThrows[IllegalArgumentException] {
        DropwizardMetrics.constructName("marathon#", "metric", "counter", UnitOfMeasurement.None)
      }
      assertThrows[IllegalArgumentException] {
        DropwizardMetrics.constructName("marathon", "metric$", "counter", UnitOfMeasurement.None)
      }
      assertThrows[IllegalArgumentException] {
        DropwizardMetrics.constructName("marathon", "metric", "counter%", UnitOfMeasurement.None)
      }
    }
  }
}
