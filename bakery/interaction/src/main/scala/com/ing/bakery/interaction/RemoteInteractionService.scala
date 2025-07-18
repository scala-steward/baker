package com.ing.bakery.interaction

import cats.effect.{ContextShift, IO, Resource, Timer}
import com.ing.baker.runtime.model.BakerLogging
import com.ing.baker.runtime.scaladsl.InteractionInstance
import com.ing.baker.runtime.serialization.InteractionExecutionJsonCodecs._
import com.ing.baker.runtime.serialization.{InteractionExecution => I}
import com.ing.bakery.metrics.MetricService
import com.typesafe.scalalogging.LazyLogging
import io.circe.parser._
import io.prometheus.client.CollectorRegistry
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.metrics.prometheus.Prometheus
import org.http4s.blaze.server._
import org.http4s.server.middleware.{Logger, Metrics}
import org.http4s.server.{Router, Server}

import java.lang.reflect.InvocationTargetException
import java.net.{InetSocketAddress, URLEncoder}
import java.util.concurrent.CompletableFuture
import scala.annotation.nowarn
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._
import scala.jdk.FutureConverters.FutureOps

object RemoteInteractionService {

  def resource(interactions: List[InteractionInstance],
               address: InetSocketAddress,
               tlsConfig: Option[BakeryHttp.TLSConfig],
               apiLoggingEnabled: Boolean = false,
               interactionPerTypeMetricsEnabled: Boolean = true,
               metricsPort: Int = 9096,
               metricsEnabled: Boolean = false,
               apiUrlPrefix: String = "/api/bakery/interactions",
               registry: Option[CollectorRegistry] = None)(implicit timer: Timer[IO], cs: ContextShift[IO], executionContext: ExecutionContext): Resource[IO, Server] = {

    val idToNameMap = interactions.map(i => URLEncoder.encode(i.shaBase64, "UTF-8").take(8) -> i.name).toMap

    val tls = tlsConfig.map { tlsConfig =>
      val sslConfig = BakeryHttp.loadSSLContext(tlsConfig)
      val sslParams = sslConfig.getDefaultSSLParameters
      sslParams.setNeedClientAuth(true)
      (sslConfig, sslParams)
    }
    val service = new RemoteInteractionService(interactions)

    def interactionRequestClassifier(request: Request[IO]): Option[String] = if (interactionPerTypeMetricsEnabled) {
      val segments = request.pathInfo.segments // ... /interactions/<id>/execute - we take ID part we care most about
      (if (segments.length == 4) Some(segments(2)) else None).map(v => idToNameMap.getOrElse(v.toString.take(8), "unknown"))
    } else None

    val collectorRegistry = registry.getOrElse(CollectorRegistry.defaultRegistry)

    for {
      metrics <- Prometheus.metricsOps[IO](collectorRegistry, "http_interactions")
      app = BlazeServerBuilder[IO](ExecutionContext.global)
        .bindSocketAddress(address)
        .withHttpApp(
          Logger.httpApp(
            logHeaders = apiLoggingEnabled,
            logBody = apiLoggingEnabled,
            logAction = if (apiLoggingEnabled) Some((x: String) => IO(println(x))) else None
          )(Router(apiUrlPrefix -> Metrics[IO](metrics,
            classifierF = interactionRequestClassifier)(service.routes)) orNotFound)
        )
      server <- (tls match {
        case Some((sslConfig, sslParams)) => app.withSslContextAndParameters(sslConfig, sslParams)
        case None => app
      }).resource
      _ <- if (metricsEnabled) {
        MetricService
          .resourceServer(InetSocketAddress.createUnresolved("0.0.0.0", metricsPort), collectorRegistry, ExecutionContext.global)(cs, timer)
      }
      else Resource.eval(IO.unit)

    } yield server

  }
}

abstract class InteractionExecutor extends LazyLogging {

  def interactions: List[InteractionInstance]
  def executionContext: ExecutionContext
  implicit val contextShift: ContextShift[IO] = IO.contextShift(executionContext)
  implicit val timer: Timer[IO] = IO.timer(executionContext)

  val bakerLogging: BakerLogging = BakerLogging(logger)

  protected val CurrentInteractions: I.Interactions =
    I.Interactions(System.currentTimeMillis,
      interactions.map(interaction =>
        I.Descriptor(interaction.shaBase64, interaction.name, interaction.input, interaction.output))
    )

  protected def executionFailure(interactionName: String, message: String): IO[I.ExecutionResult] = IO(I.ExecutionResult(
    Left(I.Failure(I.InteractionError(
      interactionName = interactionName,
      message = Option(message).getOrElse("NullPointerException"))
    ))))

  protected def execute(request: I.ExecutionRequest): IO[I.ExecutionResult] = {
    val metadata = request.metaData.getOrElse(Map())
    bakerLogging.withMDC(metadata, _.debug(s"Trying to execute interaction: ${request.id}"))
    interactions.find(_.shaBase64 == request.id) match {
      case Some(interaction) =>
        bakerLogging.withMDC(metadata, _.info(s"Executing interaction: ${interaction.name}"))
        IO.fromFuture(IO(interaction.execute(request.ingredients, request.metaData.getOrElse(Map())))).attempt.flatMap {
          case Right(value) =>
            bakerLogging.withMDC(metadata, _.info(s"Interaction ${interaction.name} executed correctly"))
            IO(I.ExecutionResult(Right(I.Success(value))))
          case Left(e) =>
            val rootCause = e match {
              case _: InvocationTargetException if Option(e.getCause).isDefined => e.getCause
              case _ => e
            }
            bakerLogging.withMDC(metadata, _.error(s"Interaction ${interaction.name} failed with an exception: ${rootCause.getMessage}", rootCause))
            executionFailure(interaction.name, rootCause.getMessage)

        }
      case None =>
        bakerLogging.withMDC(metadata, _.error(s"No implementation found for execution for id: ${request.id}"))
        IO(I.ExecutionResult(Left(I.Failure(I.NoInstanceFound))))

    }
  }
}

class InteractionExecutorJava(implementations: java.util.List[InteractionInstance],
                              val executionContext: ExecutionContext)
  extends InteractionExecutor {
  @nowarn
  private lazy val javaInteractions = implementations.asScala.toList
  def interactions: List[InteractionInstance] = javaInteractions

  def list: String = interactionsCodec(CurrentInteractions).noSpaces

  def run(requestJson: String): CompletableFuture[String] = ((for {
    json <- parse(requestJson)
    request <- json.as[I.ExecutionRequest]
  } yield {
    execute(request)
  }) match {
    case Right(result) => result
    case Left(error) => executionFailure("?", error.getMessage)
  }).map(executionResultCodec.apply)
    .map(_.noSpaces)
    .unsafeToFuture().asJava.toCompletableFuture
}

final class RemoteInteractionService(val interactions: List[InteractionInstance])(implicit val executionContext: ExecutionContext)
  extends InteractionExecutor {

  implicit val interactionEntityEncoder: EntityEncoder[IO, I.Interactions] = jsonEncoderOf[IO, I.Interactions]
  implicit val executeRequestEntityDecoder: EntityDecoder[IO, I.ExecutionRequest] = jsonOf[IO, I.ExecutionRequest]
  implicit val executeResponseEntityEncoder: EntityEncoder[IO, I.ExecutionResult] = jsonEncoderOf[IO, I.ExecutionResult]

  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root => Ok(CurrentInteractions)

    case req@POST -> Root =>
      for {
        req <- req.as[I.ExecutionRequest]
        result <- execute(req)
        response <- Ok(result)
      } yield response
  }

}
