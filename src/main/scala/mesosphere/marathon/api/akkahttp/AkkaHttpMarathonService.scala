package mesosphere.marathon
package api.akkahttp

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.google.common.util.concurrent.AbstractIdleService
import com.typesafe.scalalogging.StrictLogging
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.api.MarathonHttpService
import scala.concurrent.Future
import scala.async.Async._

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server._

class AkkaHttpMarathonService(
    config: MarathonConf with HttpConf,
    v2Controller: V2Controller
)(
    implicit
    actorSystem: ActorSystem) extends AbstractIdleService with MarathonHttpService with StrictLogging {
  import actorSystem.dispatcher
  implicit val materializer = ActorMaterializer()
  private var handler: Option[Future[Http.ServerBinding]] = None
  import Directives._
  import EntityMarshallers._
  implicit def rejectionHandler =
    RejectionHandler.newBuilder()
      .handle(LeaderDirectives.handleNonLeader)
      .handle(EntityMarshallers.handleNonValid)
      .handle(AuthDirectives.handleAuthRejections)
      .handle {
        case ValidationRejection(msg, _) =>
          complete((InternalServerError, "That wasn't valid! " + msg))
        case Rejections.EntityNotFound(msg) =>
          complete(NotFound -> msg)
      }
      .handleAll[MethodRejection] { methodRejections =>
        val names = methodRejections.map(_.supported.name)
        complete((MethodNotAllowed, s"Can't do that! Supported: ${names mkString " or "}!"))
      }
      .handleNotFound { complete((NotFound, "Not here!")) }
      .result()

  val route: Route = {
    import Directives._
    pathPrefix("v2") {
      v2Controller.route
    }
  }

  override def startUp(): Unit = synchronized {
    if (handler.isEmpty) {
      logger.info(s"Listening via Akka HTTP on ${config.httpPort()}")
      handler = Some(Http().bindAndHandle(route, "localhost", config.httpPort()))
    } else {
      logger.error("Service already started")
    }
  }

  override def shutDown(): Unit = {
    val unset = synchronized {
      if (handler.isEmpty)
        None
      else {
        val oldHandler = handler
        handler = None
        oldHandler
      }
    }

    unset.foreach { oldHandlerF =>
      async {
        val oldHandler = await(oldHandlerF)
        logger.info(s"Shutting down Akka HTTP service on ${config.httpPort()}")
        val unbound = await(oldHandler.unbind())
        logger.info(s"Akka HTTP service on ${config.httpPort()} is stopped")
      }
    }
  }
}
