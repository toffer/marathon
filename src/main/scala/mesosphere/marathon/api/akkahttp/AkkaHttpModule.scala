package mesosphere.marathon
package api.akkahttp

import akka.actor.ActorSystem
import akka.event.EventStream
import com.google.inject.AbstractModule
import com.google.inject.{ Provides, Scopes, Singleton }
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.api.MarathonHttpService
import mesosphere.marathon.core.appinfo._
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.plugin.auth._
import v2.AppsController

class AkkaHttpModule(conf: MarathonConf with HttpConf) extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[MarathonHttpService]).to(classOf[AkkaHttpMarathonService]).in(Scopes.SINGLETON)
  }

  @Provides
  @Singleton
  def provideAkkaHttpMarathonService(
    clock: Clock,
    eventBus: EventStream,
    appInfoService: AppInfoService,
    groupManager: GroupManager,
    pluginManager: PluginManager,
    marathonSchedulerService: MarathonSchedulerService,
    appTasksRes: mesosphere.marathon.api.v2.AppTasksResource)(implicit
    actorSystem: ActorSystem,
    authenticator: Authenticator,
    authorizer: Authorizer,
    electionService: ElectionService): AkkaHttpMarathonService = {

    import actorSystem.dispatcher
    val appsController = new AppsController(
      clock = clock,
      eventBus = eventBus,
      appTasksRes = appTasksRes,
      service = marathonSchedulerService,
      appInfoService = appInfoService,
      config = conf,
      groupManager = groupManager,
      pluginManager = pluginManager)

    val v2Controller = new V2Controller(appsController)
    new AkkaHttpMarathonService(
      conf,
      v2Controller)
  }
}
