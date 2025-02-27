package cromwell.engine.workflow.tokens

import java.time.OffsetDateTime

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated, Timers}
import cats.data.NonEmptyList
import cromwell.core.Dispatcher.EngineDispatcher
import cromwell.core.JobToken._
import cromwell.core.instrumentation.InstrumentationPrefixes.ServicesPrefix
import cromwell.core.{ExecutionStatus, HogGroup, JobToken}
import cromwell.engine.instrumentation.JobInstrumentation
import cromwell.engine.workflow.tokens.DynamicRateLimiter.TokensAvailable
import cromwell.engine.workflow.tokens.JobTokenDispenserActor._
import cromwell.engine.workflow.tokens.TokenQueue.{LeasedActor, TokenQueuePlaceholder, TokenQueueState}
import cromwell.services.instrumentation.CromwellInstrumentation._
import cromwell.services.instrumentation.{CromwellInstrumentation, CromwellInstrumentationScheduler}
import cromwell.services.loadcontroller.LoadControllerService.ListenToLoadController
import cromwell.util.GracefulShutdownHelper.ShutdownCommand
import io.circe.generic.JsonCodec
import io.circe.Printer
import io.github.andrebeat.pool.Lease
import io.circe.syntax._
import io.circe.generic.semiauto._

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class JobTokenDispenserActor(override val serviceRegistryActor: ActorRef,
                             override val dispensingRate: DynamicRateLimiter.Rate,
                             logInterval: Option[FiniteDuration],
                             dispenserType: String,
                             tokenAllocatedDescription: String)
  extends Actor
    with ActorLogging
    with JobInstrumentation
    with CromwellInstrumentationScheduler
    with Timers
    with DynamicRateLimiter
    with CromwellInstrumentation
{

  // Metrics paths are based on the dispenser type
  private val tokenDispenserMetricsBasePath: NonEmptyList[String] = NonEmptyList.of("token_dispenser", dispenserType)

  private val tokenLeaseDurationMetricPath: NonEmptyList[String] = tokenDispenserMetricsBasePath :+ "token_hold_duration"

  private val tokenDispenserMetricsActivityRates: NonEmptyList[String] = tokenDispenserMetricsBasePath :+ "activity_rate"
  private val requestsEnqueuedMetricPath: NonEmptyList[String] = tokenDispenserMetricsActivityRates :+ "requests_enqueued"
  private val tokensLeasedMetricPath: NonEmptyList[String] = tokenDispenserMetricsActivityRates :+ "tokens_dispensed"
  private val tokensReturnedMetricPath: NonEmptyList[String] = tokenDispenserMetricsActivityRates :+ "tokens_returned"

  /**
    * Lazily created token queue. We only create a queue for a token type when we need it
    */
  var tokenQueues: Map[JobTokenType, TokenQueue] = Map.empty
  var currentTokenQueuePointer: Int = 0
  var tokenAssignments: Map[ActorRef, TokenLeaseRecord] = Map.empty

  val instrumentationAction = () => {
    sendGaugeJob(tokenAllocatedDescription, tokenAssignments.size.toLong)
    sendGaugeJob(ExecutionStatus.QueuedInCromwell.toString, tokenQueues.values.map(_.size).sum.toLong)
  }

  lazy val effectiveLogInterval: Option[FiniteDuration] = logInterval.filterNot(_ == 0.seconds)

  lazy val tokenEventLogger = effectiveLogInterval match {
    case Some(someInterval) => new CachingTokenEventLogger(log, someInterval)
    case None => NullTokenEventLogger
  }

  // Give the actor time to warm up, then start scheduling token allocation logging:
  context.system.scheduler.scheduleOnce(5.seconds) {
    effectiveLogInterval match {
      case Some(someInterval) =>
        log.info(s"Triggering log of $dispenserType token queue status. Effective log interval = $someInterval")
        context.system.scheduler.scheduleOnce(someInterval) {
          self ! LogJobTokenAllocation(someInterval)
        }(context.dispatcher)
        ()
      case None =>
        log.info(s"Not triggering log of $dispenserType token queue status. Effective log interval = None")
        ()
    }
  }(context.dispatcher)

  override def preStart() = {
    ratePreStart()
    serviceRegistryActor ! ListenToLoadController
    startInstrumentationTimer()
    super.preStart()
  }

  override def receive: Actor.Receive = tokenDispensingReceive.orElse(rateReceive).orElse(instrumentationReceive(instrumentationAction))

  private def tokenDispensingReceive: Receive = {
    case JobTokenRequest(hogGroup, tokenType) => enqueue(sender, hogGroup.value, tokenType)
    case JobTokenReturn => release(sender)
    case TokensAvailable(n) =>
      emitHeartbeatMetrics()
      dispense(n)
    case Terminated(terminee) => onTerminate(terminee)
    case LogJobTokenAllocation(nextInterval) => logTokenAllocation(nextInterval)
    case ShutdownCommand => context stop self
  }

  // This makes sure the metric paths are being used even if there's no other activity on the token dispenser:
  private def emitHeartbeatMetrics(): Unit = {
    count(requestsEnqueuedMetricPath, 0L, ServicesPrefix)
    count(tokensLeasedMetricPath, 0L, ServicesPrefix)
    count(tokensReturnedMetricPath, 0L, ServicesPrefix)
  }

  private def enqueue(sndr: ActorRef, hogGroup: String, tokenType: JobTokenType): Unit = {
    if (tokenAssignments.contains(sndr)) {
      sndr ! JobTokenDispensed
    } else {
      val queue = tokenQueues.getOrElse(tokenType, TokenQueue(tokenType, tokenEventLogger))
      tokenQueues += tokenType -> queue.enqueue(TokenQueuePlaceholder(sndr, hogGroup))
      context.watch(sndr)
      increment(requestsEnqueuedMetricPath, ServicesPrefix)
      ()
    }
  }

  private def dispense(n: Int) = if (tokenQueues.nonEmpty) {

    // Sort by backend name to avoid re-ordering across iterations:
    val iterator = new RoundRobinQueueIterator(tokenQueues.toList.sortBy(_._1.backend).map(_._2), currentTokenQueuePointer)

    // In rare cases, an abort might empty an inner queue between "available" and "dequeue", which could cause an
    // exception.
    // If we do nothing now then when we rebuild the iterator next time we run dispense(), that won't happen again next time.
    val nextTokens = Try(iterator.take(n)) match {
      case Success(tokens) => tokens.toList
      case Failure(e) =>
        log.warning(s"Failed to take($n): ${e.getMessage}")
        List.empty
    }

    if (nextTokens.nonEmpty) {
      val hogGroupCounts = nextTokens.groupBy(t => t.queuePlaceholder.hogGroup).map { case (hogGroup, list) => s"$hogGroup: ${list.size}" }
      log.info(s"Assigned new job $dispenserType tokens to the following groups: ${hogGroupCounts.mkString(", ")}")
    }

    nextTokens.foreach({
      case LeasedActor(queuePlaceholder, lease) if !tokenAssignments.contains(queuePlaceholder.actor) =>
        tokenAssignments = tokenAssignments + (queuePlaceholder.actor -> TokenLeaseRecord(lease, OffsetDateTime.now()))
        incrementJob("Started")
        increment(tokensLeasedMetricPath, ServicesPrefix)
        queuePlaceholder.actor ! JobTokenDispensed
      // Only one token per actor, so if you've already got one, we don't need to use this new one:
      case LeasedActor(queuePlaceholder, lease) =>
        log.error(s"Programmer Error: Actor ${queuePlaceholder.actor.path} requested a job $dispenserType token more than once.")
        // Because this actor already has a lease assigned to it:
        // a) tell the actor that it has a lease
        // b) don't hold onto this new lease - release it and let some other actor take it instead
        queuePlaceholder.actor ! JobTokenDispensed
        lease.release()
    })

    tokenQueues = iterator.updatedQueues.map(queue => queue.tokenType -> queue).toMap
    currentTokenQueuePointer = iterator.updatedPointer
  }

  private def release(actor: ActorRef): Unit = {
    tokenAssignments.get(actor) match {
      case Some(TokenLeaseRecord(leasedToken, timestamp)) =>
        tokenAssignments -= actor
        leasedToken.release()
        context.unwatch(actor)
        increment(tokensReturnedMetricPath, ServicesPrefix)
        sendTiming(tokenLeaseDurationMetricPath, calculateTimeSince(timestamp), ServicesPrefix)
        ()
      case None =>
        log.error(s"Job {} token returned from incorrect actor: {}", dispenserType, actor.path.name)
    }
  }

  private def onTerminate(terminee: ActorRef): Unit = {
    tokenAssignments.get(terminee) match {
      case Some(_) =>
        log.debug("Actor {} stopped without returning its Job Execution Token. Reclaiming it!", terminee)
        self.tell(msg = JobTokenReturn, sender = terminee)
      case None =>
        log.debug("Actor {} stopped before receiving a token, removing it from any queues if necessary", terminee)
        // This is a very inefficient way to remove the actor from the queue and can lead to very poor performance for a large queue and a large number of actors to remove
        tokenQueues = tokenQueues map {
          case (tokenType, tokenQueue) => tokenType -> tokenQueue.removeTokenlessActor(terminee)
        }
    }
    context.unwatch(terminee)
    ()
  }

  def tokenDispenserState: TokenDispenserState = {
    val queueStates: Vector[TokenTypeState] = tokenQueues.toVector.map { case (tokenType, queue) =>
      TokenTypeState(tokenType, queue.tokenQueueState)
    }

    TokenDispenserState(dispenserType, queueStates, currentTokenQueuePointer, tokenAssignments.size)
  }

  private def logTokenAllocation(someInterval: FiniteDuration): Unit = {

    log.info(tokenDispenserState.asJson.printWith(Printer.spaces2))

    // Schedule the next log event:
    context.system.scheduler.scheduleOnce(someInterval) { self ! LogJobTokenAllocation(someInterval) }(context.dispatcher)
    ()
  }
}

object JobTokenDispenserActor {
  case object TokensTimerKey

  def props(serviceRegistryActor: ActorRef, rate: DynamicRateLimiter.Rate, logInterval: Option[FiniteDuration],
            dispenserType: String, tokenAllocatedDescription: String): Props =
    Props(new JobTokenDispenserActor(serviceRegistryActor, rate, logInterval, dispenserType, tokenAllocatedDescription)).withDispatcher(EngineDispatcher)

  case class JobTokenRequest(hogGroup: HogGroup, jobTokenType: JobTokenType)

  case object JobTokenReturn
  case object JobTokenDispensed
  final case class LogJobTokenAllocation(someInterval: FiniteDuration)

  implicit val tokenEncoder = deriveEncoder[JobTokenType]

  @JsonCodec(encodeOnly = true)
  final case class TokenDispenserState(dispenserType: String, tokenTypes: Vector[TokenTypeState], pointer: Int, leased: Int)

  @JsonCodec(encodeOnly = true)
  final case class TokenTypeState(tokenType: JobTokenType, queue: TokenQueueState)

  final case class TokenLeaseRecord(tokenLease: Lease[JobToken], time: OffsetDateTime)
}
