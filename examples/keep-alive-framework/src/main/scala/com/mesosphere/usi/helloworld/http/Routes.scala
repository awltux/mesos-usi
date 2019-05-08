package com.mesosphere.usi.helloworld.http

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import io.circe.generic.auto._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import JsonDTO._
import com.mesosphere.usi.core.models.RunSpec
import com.mesosphere.usi.core.models.resources.{ResourceType, ScalarRequirement}
import com.mesosphere.usi.helloworld.Configuration
import com.mesosphere.usi.helloworld.runspecs.{RunSpecId, RunSpecInfo, RunSpecService, LaunchResults}

class Routes(appsService: RunSpecService) {

  val root =
    pathPrefix("v0") {
      pathPrefix("start") {
        post {
          entity(as[JsonRunSpecDefinition]) { jsonApp =>
            val id = RunSpecId(jsonApp.id)

            val requirements = List(
              ScalarRequirement(ResourceType.CPUS, jsonApp.cpus),
              ScalarRequirement(ResourceType.MEM, jsonApp.mem),
              ScalarRequirement(ResourceType.DISK, jsonApp.disk)
            )

            val runSpec = RunSpec(
              requirements,
              jsonApp.command,
              Configuration.role
            )

            onSuccess(appsService.launchRunSpec(id, runSpec)) {
              case LaunchResults.AlreadyExist =>
                complete(StatusCodes.Conflict -> "runspec already exists")

              case LaunchResults.Launched(id) =>
                complete(StatusCodes.Created -> DeploymentResult(id.toString))

              case LaunchResults.TooMuchLoad =>
                complete(StatusCodes.ServiceUnavailable -> "Load is too high, please try again later")

              case LaunchResults.Failed(ex) =>
                complete(StatusCodes.InternalServerError -> ex.getMessage)
            }
          }
        }
      } ~
        pathPrefix("list") {
          get {
            onSuccess(appsService.listRunSpecs()) { apps =>
              val jsonApps = apps.map(appInfo2Json)

              complete(jsonApps)
            }
          }
        } ~
        pathPrefix("remove" / Segment) { appId =>
          post {
            onSuccess(appsService.wipeRunspec(RunSpecId(appId))) { _ =>
              complete(StatusCodes.OK)
            }
          }
        }
    }

  def appInfo2Json(appInfo: RunSpecInfo): JsonAppInfo = {
    val jsonRequirements = appInfo.runSpec.resourceRequirements.map {
      case ScalarRequirement(resource, amount) =>
        JsonResourceRequirement(resource.name, amount)

      case _ => ???
    }

    val runSpec = JsonRunSpec(
      jsonRequirements,
      appInfo.runSpec.shellCommand,
    )
    JsonAppInfo(appInfo.id.value, runSpec, appInfo.status)
  }

}