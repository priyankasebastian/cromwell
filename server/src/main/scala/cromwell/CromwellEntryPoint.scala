package cromwell

import akka.actor.ActorSystem
import akka.actor.CoordinatedShutdown.JvmExitReason
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.pattern.GracefulStopSupport
import akka.stream.ActorMaterializer
import cats.data.Validated._
import cats.effect.{ContextShift, IO}
import cats.syntax.apply._
import cats.syntax.validated._
import com.typesafe.config.{Config, ConfigFactory}
import common.exception.MessageAggregation
import common.validation.ErrorOr._
import cromwell.CommandLineArguments.{ValidSubmission, WorkflowSourceOrUrl}
import cromwell.CromwellApp._
import cromwell.api.CromwellClient
import cromwell.api.model.{Label, LabelsJsonFormatter, WorkflowSingleSubmission}
import cromwell.core.path.{DefaultPathBuilder, Path}
import cromwell.core.{WorkflowSourceFilesCollection, WorkflowSourceFilesWithDependenciesZip, WorkflowSourceFilesWithoutImports}
import cromwell.engine.workflow.SingleWorkflowRunnerActor
import cromwell.engine.workflow.SingleWorkflowRunnerActor.RunWorkflow
import cromwell.server.{CromwellServer, CromwellShutdown, CromwellSystem}
import net.ceedubs.ficus.Ficus._
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object CromwellEntryPoint extends GracefulStopSupport {

  private lazy val EntryPointLogger = LoggerFactory.getLogger("Cromwell EntryPoint")
  private lazy val config = ConfigFactory.load()

  // Only abort jobs on SIGINT if the config explicitly sets system.abort-jobs-on-terminate = true.
  private val abortJobsOnTerminate = config.as[Option[Boolean]]("system.abort-jobs-on-terminate")

  private val gracefulShutdown = config.as[Boolean]("system.graceful-server-shutdown")

  // 3 minute DNS TTL down from JVM default of infinite [BA-6454]
  private val dnsCacheTtl = config.getOrElse("system.dns-cache-ttl", 3 minutes)
  java.security.Security.setProperty("networkaddress.cache.ttl", dnsCacheTtl.toSeconds.toString)

  /**
    * Run Cromwell in server mode.
    */
  def runServer(): Unit = {
    initLogging(Server)

    val system = buildCromwellSystem()
    waitAndExit(CromwellServer.run(gracefulShutdown, abortJobsOnTerminate.getOrElse(false)) _, system)
  }

  /**
    * Run a single workflow using the successfully parsed but as yet not validated arguments.
    */
  def runSingle(args: CommandLineArguments): Unit = {
    initLogging(Run)

    val sources = validateRunArguments(args)

    val cromwellSystem = buildCromwellSystem()
    implicit val actorSystem: ActorSystem = cromwellSystem.actorSystem

    val runnerProps = SingleWorkflowRunnerActor.props(
      source = sources,
      metadataOutputFile = args.metadataOutput,
      terminator = cromwellSystem,
      gracefulShutdown = gracefulShutdown,
      abortJobsOnTerminate = abortJobsOnTerminate.getOrElse(true),
      config = cromwellSystem.config
    )(cromwellSystem.materializer)

    val runner = cromwellSystem.actorSystem.actorOf(runnerProps, "SingleWorkflowRunnerActor")

    import cromwell.util.PromiseActor.EnhancedActorRef
    waitAndExit(() => runner.askNoTimeout(RunWorkflow), () => CromwellShutdown.instance(cromwellSystem.actorSystem).run(JvmExitReason))
  }

  def submitToServer(args: CommandLineArguments): Unit = {
    initLogging(Submit)
    lazy val Log = LoggerFactory.getLogger("cromwell-submit")

    implicit val actorSystem: ActorSystem = ActorSystem("SubmitSystem")
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    implicit val ec: ExecutionContext = actorSystem.dispatcher
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)

    val cromwellClient = new CromwellClient(args.host, "v2")

    val singleSubmission = validateSubmitArguments(args)
    val submissionFuture = () => {
      val io = cromwellClient.submit(singleSubmission).value.attempt flatMap {
        case Right(Right(submitted)) =>
          IO {
            Log.info(s"Workflow ${submitted.id} submitted to ${args.host}")
          }
        case Right(Left(httpResponse)) =>
          IO.fromFuture(IO {
            Unmarshal(httpResponse.entity).to[String] map { stringEntity =>
              Log.error(s"Workflow failed to submit to ${args.host}: $stringEntity")
            }
          })
        case Left(exception: Exception) =>
          IO {
            Log.error(s"Workflow failed to submit to ${args.host}: ${exception.getMessage}", exception)
          }
        case Left(throwable) => throw throwable
      }
      io.unsafeToFuture()
    }

    waitAndExit(submissionFuture, () => actorSystem.terminate())
  }

  private def buildCromwellSystem(): CromwellSystem = {
    lazy val Log = LoggerFactory.getLogger("cromwell")
    Try {
      new CromwellSystem {
        override lazy val config: Config = CromwellEntryPoint.config
      }
    } recoverWith {
      case t: Throwable =>
        Log.error(s"Failed to instantiate Cromwell System. Shutting down Cromwell.", t)
        System.exit(1)
        Failure(t)
    } get
  }

  /**
    * If a cromwell server is going to be run, makes adjustments to the default logback configuration.
    * Overwrites LOG_MODE system property used in our logback.xml, _before_ the logback classes load.
    * Restored from similar functionality in
    *   https://github.com/broadinstitute/cromwell/commit/2e3f45b#diff-facc2160a82442932c41026c9a1e4b2bL28
    * TODO: Logback is configurable programmatically. We don't have to overwrite system properties like this.
    *
    * Also copies variables from config/system/environment/defaults over to the system properties.
    * Fixes issue where users are trying to specify Java properties as environment variables.
    */
  private def initLogging(command: Command): Unit = {
    val logbackSetting = command match {
      case Server => "STANDARD"
      case Submit => "PRETTY"
      case Run => "PRETTY"
    }

    val defaultProps = Map(
      "LOG_MODE" -> logbackSetting,
      "LOG_LEVEL" -> "INFO"
    )

    val configWithFallbacks = config
      .withFallback(ConfigFactory.systemEnvironment())
      .withFallback(ConfigFactory.parseMap(defaultProps.asJava, "Defaults"))

    val props = sys.props
    defaultProps.keys foreach { key =>
      props += key -> configWithFallbacks.getString(key)
    }

    /*
    We've possibly copied values from the environment, or our defaults, into the system properties.
    Make sure that the next time one uses the ConfigFactory that our updated system properties are loaded.
     */
    ConfigFactory.invalidateCaches()
    ()
  }

  protected def waitAndExit[A](operation: () => Future[A], shutdown: () => Future[Any]): Nothing = {
    val futureResult = operation()
    Await.ready(futureResult, Duration.Inf)

    try {
      Await.ready(shutdown(), 30.seconds)
    } catch {
      case _: TimeoutException => Console.err.println("Timed out trying to shutdown actor system")
      case other: Exception => Console.err.println(s"Unexpected error trying to shutdown actor system: ${other.getMessage}")
    }

    val returnCode = futureResult.value.get match {
      case Success(_) => 0
      case Failure(e) =>
        Console.err.println(e.getMessage)
        1
    }

    sys.exit(returnCode)
  }

  private def waitAndExit(runner: CromwellSystem => Future[Any], workflowManagerSystem: CromwellSystem): Unit = {
    waitAndExit(() => runner(workflowManagerSystem), () => workflowManagerSystem.shutdownActorSystem())
  }

  def validateSubmitArguments(args: CommandLineArguments): WorkflowSingleSubmission = {
    import LabelsJsonFormatter._
    import spray.json._

    val validation = args.validateSubmission(EntryPointLogger) map {
      case ValidSubmission(s, u, r, i, o, l, z) =>
        val finalWorkflowSourceAndUrl: WorkflowSourceOrUrl =
          (s, u) match {
            case (None, Some(url)) if !url.startsWith("http") => //case where url is a WDL/CWL file
              WorkflowSourceOrUrl(Option(DefaultPathBuilder.get(url).contentAsString), None)
            case _ =>
              WorkflowSourceOrUrl(s, u)
          }

        WorkflowSingleSubmission(
          workflowSource = finalWorkflowSourceAndUrl.source,
          workflowUrl = finalWorkflowSourceAndUrl.url,
          workflowRoot = r,
          workflowType = args.workflowType,
          workflowTypeVersion = args.workflowTypeVersion,
          inputsJson = Option(i),
          options = Option(o.asPrettyJson),
          labels = Option(l.parseJson.convertTo[List[Label]]),
          zippedImports = z)
    }

    validOrFailSubmission(validation)
  }

  def validateRunArguments(args: CommandLineArguments): WorkflowSourceFilesCollection = {

    val sourceFileCollection = (args.validateSubmission(EntryPointLogger), writeableMetadataPath(args.metadataOutput)) mapN {
      case (ValidSubmission(s, u, r, i, o, l, Some(z)), _) =>
        //noinspection RedundantDefaultArgument
        WorkflowSourceFilesWithDependenciesZip.apply(
          workflowSource = s,
          workflowUrl = u,
          workflowRoot = r,
          workflowType = args.workflowType,
          workflowTypeVersion = args.workflowTypeVersion,
          inputsJson = i,
          workflowOptions = o,
          labelsJson = l,
          importsZip = z.loadBytes,
          warnings = Vector.empty,
          workflowOnHold = false,
          requestedWorkflowId = None)
      case (ValidSubmission(s, u, r, i, o, l, None), _) =>
        //noinspection RedundantDefaultArgument
        WorkflowSourceFilesWithoutImports.apply(
          workflowSource = s,
          workflowUrl = u,
          workflowRoot = r,
          workflowType = args.workflowType,
          workflowTypeVersion = args.workflowTypeVersion,
          inputsJson = i,
          workflowOptions = o,
          labelsJson = l,
          warnings = Vector.empty,
          workflowOnHold = false,
          requestedWorkflowId = None)
    }

    val sourceFiles = for {
      sources <- sourceFileCollection
      _ <- writeableMetadataPath(args.metadataOutput)
    } yield sources

    validOrFailSubmission(sourceFiles)
  }

  def validOrFailSubmission[A](validation: ErrorOr[A]): A = {
    validation.valueOr(errors => throw new RuntimeException with MessageAggregation {
      override def exceptionContext: String = "ERROR: Unable to submit workflow to Cromwell:"
      override def errorMessages: Traversable[String] = errors.toList
    })
  }

  private def writeableMetadataPath(path: Option[Path]): ErrorOr[Unit] = {
    path match {
      case Some(p) if !metadataPathIsWriteable(p) => s"Unable to write to metadata directory: $p".invalidNel
      case _ => ().validNel
    }
  }

  private def metadataPathIsWriteable(metadataPath: Path): Boolean =
    Try(metadataPath.createIfNotExists(createParents = true).append("")).isSuccess
}
