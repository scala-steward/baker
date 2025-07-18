package com.ing.baker.http.client.javadsl

import cats.effect.{ContextShift, IO, Timer}
import com.ing.baker.http.client.common.TLSConfig
import com.ing.baker.http.client.scaladsl.{EndpointConfig, BakerClient => ScalaClient}
import com.ing.baker.runtime.javadsl.{Baker => JavaBaker}
import org.http4s.blaze.client.BlazeClientBuilder
import org.http4s.{Request, Uri}

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors.newCachedThreadPool
import java.util.{Optional, List => JList}
import scala.annotation.nowarn
import scala.jdk.CollectionConverters._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.jdk.FunctionConverters.enrichAsScalaFromFunction
import scala.jdk.FutureConverters.FutureOps


object BakerClient {
  type Filter = java.util.function.Function[Request[IO], Request[IO]]

  //TODO add resource cleanup possibility.
  /**
   * Return async result with JavaBaker instance, using hosts, filters and tlsConfig
   *
   * @param hosts     List of hosts for baker cluster (more than 1 hosts is supported for multi-dc option)
   * @param filters   Http filters
   * @param tlsConfig TLS context for connection
   * @return JavaBaker
   */
  @nowarn
  def build(hosts: JList[String],
            apiUrlPrefix: String,
            fallbackHosts: JList[String],
            fallbackApiUrlPrefix: String,
            filters: JList[Filter],
            tlsConfig: Optional[TLSConfig],
            apiLoggingEnabled: Boolean): CompletableFuture[JavaBaker] = {

    val connectionPool: ExecutionContextExecutor = ExecutionContext.fromExecutor(newCachedThreadPool())
    val sslContext = if (tlsConfig.isPresent) Some(tlsConfig.get()).map(_.loadSSLContext) else None

    implicit val ec: ExecutionContext = ExecutionContext.Implicits.global
    implicit val contextShift: ContextShift[IO] = IO.contextShift(ec)
    implicit val timer: Timer[IO] = IO.timer(ec)

    val future = sslContext
      .fold(BlazeClientBuilder[IO](connectionPool))(BlazeClientBuilder[IO](connectionPool).withSslContext)
      .resource
      .map { client =>
        new ScalaClient(
          client = client,
          EndpointConfig(hosts.asScala.map(Uri.unsafeFromString).toIndexedSeq, apiUrlPrefix, apiLoggingEnabled),
          if (fallbackHosts.size == 0) None
          else Some(EndpointConfig(fallbackHosts.asScala.map(Uri.unsafeFromString).toIndexedSeq, fallbackApiUrlPrefix, apiLoggingEnabled)),
          filters = filters.asScala.map(_.asScala).toIndexedSeq)
      }
      .allocated
      .map {
        case (client, _) => new JavaBaker(client)
      }
      .unsafeToFuture()

    future.asJava.toCompletableFuture
  }
}
