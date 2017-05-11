package mesosphere

import java.util.concurrent.{ LinkedBlockingDeque, TimeUnit }

import akka.actor.{ ActorSystem, Scheduler }
import akka.stream.{ ActorMaterializer, Materializer }
import akka.testkit.{ TestActor, TestActorRef, TestKitBase }
import akka.util.Timeout
import com.typesafe.config.{ Config, ConfigFactory }
import com.typesafe.scalalogging.StrictLogging
import com.wix.accord.{ Failure, Result, Success }
import kamon.Kamon
import mesosphere.marathon.Normalization
import mesosphere.marathon.ValidationFailedException
import mesosphere.marathon.api.v2.Validation
import mesosphere.marathon.test.{ ExitDisabledTest, Mockito }
import org.scalatest.matchers.{ Matcher, MatchResult }
import org.scalatest._
import org.scalatest.concurrent.{ JavaFutures, ScalaFutures, TimeLimitedTests }
import org.scalatest.time.{ Minutes, Seconds, Span }
import mesosphere.marathon.api.v2.ValidationHelper

import scala.concurrent.ExecutionContextExecutor

/**
  * Tests which are still unreliable should be marked with this tag until
  * they sufficiently pass on master. Prefer this over ignored.
  */
object Unstable extends Tag("mesosphere.marathon.UnstableTest")

/**
  * All integration tests should be marked with this tag.
  * Integration tests need a special set up and can take a long time.
  * So it is not desirable, that these kind of tests run every time all the unit tests run.
  */
object IntegrationTag extends Tag("mesosphere.marathon.IntegrationTest")

/**
  * Tag that will conditionally enable a specific test case if an environment variable is set.
  * @param envVarName The name of the environment variable to check if it is set to "true"
  * {{{
  *   "Something" should "do something" taggedAs WhenEnvSet("ABC") in {...}
  * }}}
  */
case class WhenEnvSet(envVarName: String) extends Tag(if (sys.env.getOrElse(envVarName, "false") == "true") "" else classOf[Ignore].getName)

trait ValidationTestLike extends Validation {
  this: Assertions =>

  protected implicit val normalizeResult: Normalization[Result] = Normalization {
    // normalize failures => human readable error messages
    case f: Failure => Failure(f.violations.flatMap(allRuleViolationsWithFullDescription(_)))
    case x => x
  }

  def withValidationClue[T](f: => T): T = scala.util.Try { f }.recover {
    // handle RAML validation errors
    case vfe: ValidationFailedException => fail(vfe.failure.violations.toString())
    case th => throw th
  }.get

  def containViolation(tuple: (String, String)): Matcher[Result] = containViolation(tuple._1, tuple._2)

  def containViolation(path: String, message: String): Matcher[Result] = {
    Matcher {
      case Success =>
        MatchResult(
          false,
          s"result had no violations; expected ${path} -> ${message}",
          s"result was success")

      case f: Failure =>
        val violations = ValidationHelper.getAllRuleConstrains(f)

        MatchResult(
          violations.exists { v =>
            v.path.contains(path) && v.message == message
          },
          s"Violations:\n${violations.mkString("\n")} did not contain ${path} -> ${message}",
          s"Violation contains ${path} -> ${message}"
        )
    }
  }
}

/**
  * Base trait for all unit tests in WordSpec style with common matching/before/after and Option/Try/Future
  * helpers all mixed in.
  */
trait UnitTestLike extends WordSpecLike
    with GivenWhenThen
    with ScalaFutures
    with JavaFutures
    with Matchers
    with BeforeAndAfter
    with BeforeAndAfterEach
    with OptionValues
    with TryValues
    with AppendedClues
    with StrictLogging
    with Mockito
    with ExitDisabledTest
    with TimeLimitedTests {

  override val timeLimit = Span(30, Seconds)

  override def beforeAll(): Unit = {
    Kamon.start()
    super.beforeAll()
  }

  override implicit lazy val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(3, Seconds))
}

abstract class UnitTest extends WordSpec with UnitTestLike

trait AkkaUnitTestLike extends UnitTestLike with TestKitBase {
  protected lazy val akkaConfig: Config = ConfigFactory.parseString(
    s"""
      |akka.test.default-timeout=${patienceConfig.timeout.millisPart}
    """.stripMargin).withFallback(ConfigFactory.load())
  implicit lazy val system: ActorSystem = {
    Kamon.start()
    ActorSystem(suiteName, akkaConfig)
  }
  implicit lazy val scheduler: Scheduler = system.scheduler
  implicit lazy val mat: Materializer = ActorMaterializer()
  implicit lazy val ctx: ExecutionContextExecutor = system.dispatcher
  implicit val askTimeout: Timeout = Timeout(patienceConfig.timeout.toMillis, TimeUnit.MILLISECONDS)

  def newTestActor() =
    TestActorRef[TestActor](TestActor.props(new LinkedBlockingDeque()))

  abstract override def afterAll(): Unit = {
    super.afterAll()
    // intentionally shutdown the actor system last.
    system.terminate().futureValue
  }
}

abstract class AkkaUnitTest extends UnitTest with AkkaUnitTestLike

trait IntegrationTestLike extends UnitTestLike {
  override val timeLimit = Span(15, Minutes)

  override implicit lazy val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(300, Seconds))
}

abstract class IntegrationTest extends WordSpec with IntegrationTestLike

trait AkkaIntegrationTestLike extends AkkaUnitTestLike with IntegrationTestLike {
  protected override lazy val akkaConfig: Config = ConfigFactory.parseString(
    s"""
       |akka.test.default-timeout=${patienceConfig.timeout.toMillis}
    """.stripMargin).withFallback(ConfigFactory.load())
}

abstract class AkkaIntegrationTest extends IntegrationTest with AkkaIntegrationTestLike
