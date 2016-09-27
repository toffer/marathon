package mesosphere.marathon
package tasks

import java.util

import mesosphere.marathon.state.Container.{ Docker, PortMapping }
import mesosphere.marathon.state.{ AppDefinition, PathId, PortDefinitions, ResourceRole }
import mesosphere.marathon.tasks.PortsMatcher.PortWithRole
import mesosphere.marathon.test.{ MarathonSpec, MarathonTestHelper }
import mesosphere.mesos.ResourceMatcher.ResourceSelector
import mesosphere.mesos.protos
import mesosphere.mesos.protos._
import org.apache.mesos.Protos.Offer
import org.scalatest.Matchers

import scala.collection.immutable.Seq
import scala.util.Random

class PortsMatcherTest extends MarathonSpec with Matchers {

  import mesosphere.mesos.protos.Implicits._

  val runSpecId = PathId("/test")

  def matchPorts(app: AppDefinition, offer: Offer,
    resourceSelector: ResourceSelector = ResourceSelector.any(Set(ResourceRole.Unreserved)),
    random: Random = Random): Option[PortsMatch] = {
    app.container.get.portMappings

    val portMappings: Seq[PortsMatcher.PortAsk] = for {
      c <- app.container.toList
      pms <- c.portMappings
    } yield PortsMatcher.PortAsk(pms.hostPort)

    PortsMatcher(
      s"run spec [${app.id}]",
      app.portNumbers,
      Some(portMappings),
      app.requirePorts,
      resourceSelector,
      random).apply(offer)
  }

  test("get random ports from single range") {
    val app = AppDefinition(id = runSpecId, portDefinitions = PortDefinitions(80, 81))
    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 31000, endPort = 32000).build
    val result = matchPorts(app, offer)

    assert(result.isDefined)
    assert(2 == result.get.hostPorts.size)
    assert(result.get.resources.map(_.getRole) == Seq(ResourceRole.Unreserved))
  }

  test("get ports from multiple ranges") {
    val app = AppDefinition(id = runSpecId, portDefinitions = PortDefinitions(80, 81, 82, 83, 84))
    val portsResource = RangesResource(
      Resource.PORTS,
      Seq(protos.Range(30000, 30003), protos.Range(31000, 31000))
    )
    val offer = Offer.newBuilder
      .setId(OfferID("1"))
      .setFrameworkId(FrameworkID("marathon"))
      .setSlaveId(SlaveID("slave0"))
      .setHostname("localhost")
      .addResources(portsResource)
      .build
    val result = matchPorts(app, offer)

    assert(result.isDefined)
    assert(5 == result.get.hostPorts.size)
    assert(result.get.resources.map(_.getRole) == Seq(ResourceRole.Unreserved))
  }

  test("get ports from multiple ranges, requirePorts") {
    val f = new Fixture
    val app = AppDefinition(id = runSpecId, portDefinitions = PortDefinitions(80, 81, 82, 83, 100), requirePorts = true)
    val portsResource = RangesResource(
      Resource.PORTS,
      Seq(protos.Range(80, 83), protos.Range(100, 100))
    )
    val offer = Offer.newBuilder
      .setId(OfferID("1"))
      .setFrameworkId(FrameworkID("marathon"))
      .setSlaveId(SlaveID("slave0"))
      .setHostname("localhost")
      .addResources(portsResource)
      .build
    val result = matchPorts(app, offer, f.wildcardResourceSelector)

    assert(result.isDefined)
    assert(result.get.hostPorts.flatten == Seq(80, 81, 82, 83, 100))
    assert(result.get.resources.map(_.getRole) == Seq(ResourceRole.Unreserved))
  }

  // #2865 Multiple explicit ports are mixed up in task json
  test("get ports with requirePorts preserves the ports order") {
    val f = new Fixture
    val app = AppDefinition(id = runSpecId, portDefinitions = PortDefinitions(100, 80), requirePorts = true)
    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 70, endPort = 200).build
    val result = matchPorts(app, offer, f.wildcardResourceSelector)

    assert(result.isDefined)
    assert(result.get.hostPorts.flatten == Seq(100, 80))
  }

  test("get ports from multiple resources, preserving role") {
    val app = AppDefinition(id = runSpecId, portDefinitions = PortDefinitions(80, 81, 82, 83, 84))
    val portsResource = RangesResource(
      Resource.PORTS,
      Seq(protos.Range(30000, 30003))
    )
    val portsResource2 = RangesResource(
      Resource.PORTS,
      Seq(protos.Range(31000, 31000)),
      "marathon"
    )
    val offer = Offer.newBuilder
      .setId(OfferID("1"))
      .setFrameworkId(FrameworkID("marathon"))
      .setSlaveId(SlaveID("slave0"))
      .setHostname("localhost")
      .addResources(portsResource)
      .addResources(portsResource2)
      .build
    val result = matchPorts(app, offer, resourceSelector = ResourceSelector.any(Set(ResourceRole.Unreserved, "marathon")))

    assert(result.isDefined)
    assert(5 == result.get.hostPorts.size)
    assert(result.get.resources.map(_.getRole).to[Set] == Set(ResourceRole.Unreserved, "marathon"))
  }

  test("get ports from multiple ranges, ignore ranges with unwanted roles") {
    val f = new Fixture
    val app = AppDefinition(id = runSpecId, portDefinitions = PortDefinitions(80, 81, 82, 83, 84))
    val portsResource = RangesResource(
      Resource.PORTS,
      Seq(protos.Range(30000, 30003), protos.Range(31000, 31009)),
      role = "marathon"
    )
    val offer = Offer.newBuilder
      .setId(OfferID("1"))
      .setFrameworkId(FrameworkID("marathon"))
      .setSlaveId(SlaveID("slave0"))
      .setHostname("localhost")
      .addResources(portsResource)
      .build
    val result = matchPorts(app, offer, f.wildcardResourceSelector)

    assert(result.isEmpty)
  }

  test("get no ports") {
    val app = AppDefinition(id = runSpecId, portDefinitions = Nil)
    val offer = MarathonTestHelper.makeBasicOffer().build
    val result = matchPorts(app, offer)

    assert(result.isDefined)
    assert(Nil == result.get.hostPorts)
  }

  test("get too many ports") {
    val app = AppDefinition(id = runSpecId, portDefinitions = PortDefinitions(80, 81, 82))
    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 31000, endPort = 31001).build
    val result = matchPorts(app, offer)

    assert(result.isEmpty)
  }

  test("fail if required ports are not available") {
    val app = AppDefinition(id = runSpecId, portDefinitions = PortDefinitions(80, 81, 82), requirePorts = true)
    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 31000, endPort = 32000).build
    val result = matchPorts(app, offer)

    assert(result.isEmpty)
  }

  test("fail if dynamic mapped port from container cannot be satisfied") {
    val app = AppDefinition(id = runSpecId, container = Some(Docker(
      portMappings = Seq(
        new PortMapping(containerPort = 1, hostPort = Some(0))
      )
    )))

    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 0, endPort = -1).build
    val result = matchPorts(app, offer)

    assert(result.isEmpty)
  }

  test("satisfy dynamic mapped port from container") {
    val app = AppDefinition(id = runSpecId, container = Some(Docker(
      portMappings = Seq(
        new PortMapping(containerPort = 1, hostPort = Some(0))
      )
    )))

    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 31000, endPort = 31000).build
    val result = matchPorts(app, offer)

    assert(result.isDefined)
    assert(result.get.hostPorts.flatten == Seq(31000))
  }

  test("randomly satisfy dynamic mapped port from container") {
    val app = AppDefinition(id = runSpecId, container = Some(Docker(
      portMappings = Seq(
        new PortMapping(containerPort = 1, hostPort = Some(0))
      )
    )))

    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 31000, endPort = 32000).build
    val rand = new Random(new util.Random(0))
    val result = matchPorts(app, offer, random = rand)

    assert(result.isDefined)
    val firstPort = result.get.hostPorts.head

    val differentMatchWithSameSeed: Option[Int] = (1 to 10).find { _ =>
      val rand = new Random(new util.Random(0))
      val result = matchPorts(app, offer, random = rand)
      result.get.hostPorts.head != firstPort
    }

    differentMatchWithSameSeed should be(empty)

    val differentMatchWithDifferentSeed = (1 to 1000).find { seed =>
      val rand = new Random(new util.Random(seed.toLong))
      val result = matchPorts(app, offer, random = rand)
      result.get.hostPorts.head != firstPort
    }

    differentMatchWithDifferentSeed should be(defined)
  }

  test("fail if fixed mapped port from container cannot be satisfied") {
    val app = AppDefinition(id = runSpecId, container = Some(Docker(
      portMappings = Seq(
        new PortMapping(containerPort = 1, hostPort = Some(8080))
      )
    )))

    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 31000, endPort = 32000).build
    val result = matchPorts(app, offer)

    assert(result.isEmpty)
  }

  test("satisfy fixed mapped port from container") {
    val app = AppDefinition(id = runSpecId, container = Some(Docker(
      portMappings = Seq(
        new PortMapping(containerPort = 1, hostPort = Some(31200))
      )
    )))

    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 31000, endPort = 32000).build
    val result = matchPorts(app, offer)

    assert(result.isDefined)
    assert(result.get.hostPorts.flatten == Seq(31200))
  }

  test("do not satisfy fixed mapped port from container with resource offer of incorrect role") {
    val app = AppDefinition(id = runSpecId, container = Some(Docker(
      portMappings = Seq(
        new PortMapping(containerPort = 1, hostPort = Some(31200))
      )
    )))

    val portsResource = RangesResource(
      Resource.PORTS,
      Seq(protos.Range(31001, 31001)),
      role = "marathon"
    )

    val offer = MarathonTestHelper.makeBasicOffer(endPort = -1).addResources(portsResource).build
    val result = matchPorts(app, offer)

    assert(result.isEmpty)
  }

  test("satisfy fixed and dynamic mapped port from container from one offered range") {
    val app = AppDefinition(id = runSpecId, container = Some(Docker(
      portMappings = Seq(
        new PortMapping(containerPort = 1, hostPort = Some(0)),
        new PortMapping(containerPort = 1, hostPort = Some(31000))
      )
    )))

    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 31000, endPort = 31001).build
    val result = matchPorts(app, offer)

    assert(result.isDefined)
    assert(result.get.hostPorts.flatten == Seq(31001, 31000))
  }

  test("satisfy fixed and dynamic mapped port from container from ranges with different roles") {
    val app = AppDefinition(id = runSpecId, container = Some(Docker(
      portMappings = Seq(
        new PortMapping(containerPort = 1, hostPort = Some(0)),
        new PortMapping(containerPort = 1, hostPort = Some(31000))
      )
    )))

    val portsResource = RangesResource(
      Resource.PORTS,
      Seq(protos.Range(31001, 31001)),
      role = "marathon"
    )

    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 31000, endPort = 31000).addResources(portsResource).build
    val result = matchPorts(app, offer, resourceSelector = ResourceSelector.any(Set(ResourceRole.Unreserved, "marathon")))

    assert(result.isDefined)
    assert(result.get.hostPorts.flatten.toSet == Set(31000, 31001))
    assert(result.get.hostPortsWithRole.toSet == Set( // linter:ignore:UnlikelyEquality
      Some(PortWithRole(ResourceRole.Unreserved, 31000)), Some(PortWithRole("marathon", 31001))
    ))
  }
}

class Fixture {
  lazy val wildcardResourceSelector = ResourceSelector.any(Set(ResourceRole.Unreserved))
}
